package com.phoneshare.app.relay

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

// ============================================================================
// 调度器：RudpChannel 的全部状态只在调度线程上改动（真实环境单线程，测试用虚拟时钟）
// ============================================================================
interface Cancellable { fun cancel() }

interface Sched {
    fun now(): Long
    fun post(r: Runnable)
    fun after(delayMs: Long, r: Runnable): Cancellable
}

class RealSched(name: String = "rudp") : Sched {
    private val ex = ScheduledThreadPoolExecutor(1) { r -> Thread(r, name).also { it.isDaemon = true } }
    init { ex.removeOnCancelPolicy = true }
    override fun now(): Long = System.nanoTime() / 1_000_000
    override fun post(r: Runnable) { try { ex.execute(guard(r)) } catch (_: Exception) {} }
    override fun after(delayMs: Long, r: Runnable): Cancellable {
        return try {
            val f = ex.schedule(guard(r), max(0, delayMs), TimeUnit.MILLISECONDS)
            object : Cancellable { override fun cancel() { f.cancel(false) } }
        } catch (_: Exception) { object : Cancellable { override fun cancel() {} } }
    }
    fun shutdown() { try { ex.shutdownNow() } catch (_: Exception) {} }
    private fun guard(r: Runnable) = Runnable { try { r.run() } catch (_: Throwable) {} }
}

// ============================================================================
// 常量
// ============================================================================
object RudpConst {
    const val HDR = 6                    // type(1) dir(1) pn(4)，明文，作为 AAD
    const val TAG = 16
    const val LINK_OVERHEAD = 4          // TURN ChannelData 头
    const val DATA_PAYLOAD = 1100        // 单包文件数据
    const val MSG_CHUNK = 1000           // 小消息分片
    const val MSG_MAX = 16 * 1024
    const val SEND_BUF = 8 * 1024 * 1024 // 未确认 + 待重传 数据上限
    const val RECV_BUF = 4 * 1024 * 1024 // 接收重排窗口
    const val INIT_WINDOW = 64 * 1024L   // 收到接收方第一个窗口通告前的发送上限

    const val T_HS = 1
    const val T_DATA = 2
    const val T_ACK = 3
    const val T_MSG = 4
    const val T_KEEP = 5
    const val T_CLOSE = 6

    // 速率控制（字节/秒，按发出的整个 UDP 载荷 + ChannelData 头计）
    const val RATE_START = 64 * 1024
    const val RATE_MIN = 32 * 1024
    const val RATE_GROW = 2.0            // 启动阶段每个往返翻倍
    const val RATE_TARGET = 1.25         // 稳态：送达速率 × 1.25
    const val RTT_INFLATE = 1.2          // 往返时间明显变大：> 最小往返 × 1.2 + 25ms
    const val RTT_INFLATE_ADD = 25.0
    const val RATE_LAG = 0.7             // 启动阶段：两个区间前发出的包，确认比例 < 0.7 → 判定已满
}

class RudpConfig(
    var userCapKbps: Int = 2048,
    val handshakeTimeoutMs: Long = 30_000,
    val deadMs: Long = 60_000,        // 与网页通道「最长容忍中断 1 分钟」一致
    val stallGraceMs: Long = 5_000,   // 调度线程停了这么久以上（手机睡眠 / 进程被冻结）：这段时间不算对方没数据
    val keepMs: Long = 15_000,
    val progressMs: Long = 500,
    val statMs: Long = 10_000
)

// ============================================================================
// 回调（都在调度线程上调用，不要阻塞）
// ============================================================================
interface RudpSink {
    /** 顺序写入；返回 "ok" 或 "err:nospace" / "err:io" */
    fun write(buf: ByteArray, off: Int, len: Int): String
    /** 全部收齐后调用；返回 "ok:<最终文件名>" 或 "err:size" / "err:nospace" / "err:io" */
    fun finish(): String
    fun abort()
}

interface RudpSource {
    /** 顺序读；EOF 返回 -1；异常视为读取失败 */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    fun close()
}

interface RudpListener {
    fun onOpen(rttMs: Int)
    fun onFail(reason: String)               // 握手失败
    fun onClosed(why: String)                // 通道结束（对方中断 / 60 秒没有数据 / 本地关闭…）
    fun onMessage(text: String)
    fun onProgress(xid: Int, sending: Boolean, done: Long, total: Long, rateBps: Long, lossPct: Int, rttMs: Int)
    fun onFileDone(xid: Int, sending: Boolean, ok: Boolean, msg: String)
    fun onStat(sentWire: Long, recvWire: Long)
}

// ============================================================================
// 加解密：AES-256-GCM，nonce = 方向(1) + 0 0 0 + 包序号(4) + 0×4；每次发送（含重传）都用新包序号
// ============================================================================
class PacketCrypto(key: ByteArray) {
    init { require(key.size == 32) { "会话密钥必须是 32 字节" } }
    private val ks = SecretKeySpec(key, "AES")
    private val enc = Cipher.getInstance("AES/GCM/NoPadding")
    private val dec = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(12)
    private val hdr = ByteArray(RudpConst.HDR)

    class Opened(val type: Int, val dir: Int, val pn: Int, val body: ByteArray)

    private fun setNonce(dir: Int, pn: Int) {
        nonce[0] = dir.toByte(); nonce[1] = 0; nonce[2] = 0; nonce[3] = 0
        nonce[4] = (pn ushr 24).toByte(); nonce[5] = (pn ushr 16).toByte(); nonce[6] = (pn ushr 8).toByte(); nonce[7] = pn.toByte()
        nonce[8] = 0; nonce[9] = 0; nonce[10] = 0; nonce[11] = 0
    }

    fun seal(type: Int, dir: Int, pn: Int, body: ByteArray): ByteArray {
        val h = ByteArray(RudpConst.HDR)
        h[0] = type.toByte(); h[1] = dir.toByte()
        h[2] = (pn ushr 24).toByte(); h[3] = (pn ushr 16).toByte(); h[4] = (pn ushr 8).toByte(); h[5] = pn.toByte()
        setNonce(dir, pn)
        enc.init(Cipher.ENCRYPT_MODE, ks, GCMParameterSpec(128, nonce))
        enc.updateAAD(h)
        val out = ByteArray(RudpConst.HDR + body.size + RudpConst.TAG)
        System.arraycopy(h, 0, out, 0, RudpConst.HDR)
        enc.doFinal(body, 0, body.size, out, RudpConst.HDR)
        return out
    }

    /** 校验失败（被篡改 / 密钥不对 / 格式不对）返回 null。 */
    fun open(pkt: ByteArray): Opened? {
        if (pkt.size < RudpConst.HDR + RudpConst.TAG) return null
        val type = pkt[0].toInt() and 0xff
        val dir = pkt[1].toInt() and 0xff
        if (dir > 1 || type < 1 || type > 6) return null
        val pn = ((pkt[2].toInt() and 0xff) shl 24) or ((pkt[3].toInt() and 0xff) shl 16) or ((pkt[4].toInt() and 0xff) shl 8) or (pkt[5].toInt() and 0xff)
        System.arraycopy(pkt, 0, hdr, 0, RudpConst.HDR)
        setNonce(dir, pn)
        return try {
            dec.init(Cipher.DECRYPT_MODE, ks, GCMParameterSpec(128, nonce))
            dec.updateAAD(hdr)
            Opened(type, dir, pn, dec.doFinal(pkt, RudpConst.HDR, pkt.size - RudpConst.HDR))
        } catch (_: Exception) { null }
    }
}

// ============================================================================
// 已收包序号集合（区间）
// ============================================================================
class PnTracker {
    private val m = TreeMap<Int, Int>()
    var largest = 0; private set
    private var floor = 0

    fun contains(pn: Int): Boolean {
        if (pn <= floor) return true
        val e = m.floorEntry(pn) ?: return false
        return pn <= e.value
    }

    fun add(pn: Int) {
        if (pn <= floor) return
        var s = pn; var e = pn
        val lo = m.floorEntry(pn)
        if (lo != null && lo.value >= pn - 1) { s = lo.key; e = max(e, lo.value); m.remove(lo.key) }
        while (true) {
            val hi = m.ceilingEntry(e + 1) ?: break
            if (hi.key > e + 1) break
            e = max(e, hi.value); m.remove(hi.key)
        }
        m[s] = e
        if (pn > largest) largest = pn
        // 只保留最近 20000 个序号的历史
        val limit = largest - 20000
        while (m.size > 1) {
            val f = m.firstEntry()
            if (f.value < limit) { floor = max(floor, f.value); m.remove(f.key) } else break
        }
    }

    /** 从大到小的区间 [start,end]，最多 n 个。 */
    fun rangesDesc(n: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        for ((k, v) in m.descendingMap()) { out.add(intArrayOf(k, v)); if (out.size >= n) break }
        return out
    }
}

// ============================================================================
// 通道
// ============================================================================
class RudpChannel(
    key: ByteArray,
    private val dir: Int,
    private val sched: Sched,
    private val link: (ByteArray) -> Unit,
    private val ls: RudpListener,
    private val cfg: RudpConfig = RudpConfig()
) {
    private val crypto = PacketCrypto(key)

    // ---- 统计（调度线程写，其它线程可读）----
    @Volatile var wireSent = 0L; private set
    @Volatile var wireRecv = 0L; private set
    @Volatile var badPackets = 0L; private set
    @Volatile var lostPackets = 0L; private set
    @Volatile var sentPackets = 0L; private set
    @Volatile var retxPackets = 0L; private set
    @Volatile var peakBuffered = 0L; private set
    @Volatile var currentRateBps = RudpConst.RATE_START.toLong(); private set
    @Volatile var srttMs = 0L; private set

    // ---- 状态 ----
    private var state = 0 // 0 未开始 1 握手 2 已连通 3 已关闭
    private var nextPn = 1
    private var lastSendAt = 0L
    private var lastRecvAt = 0L
    private var lastTickAt = 0L
    private var hsStartAt = 0L
    private var peerSeen = false
    private var peerSeenAt = 0L
    private var peerKnowsUs = false
    private var openFired = false
    private var rttKnown = false
    private var lastPeerHsTs = 0L
    private var lastPeerHsAt = 0L
    private var lastWire = 0

    // ---- 发送侧 ----
    private class Sent(val pn: Int, val time: Long, val wire: Int, val kind: Int,
                       val xid: Int, val off: Long, val data: ByteArray?,
                       val mkind: Int, val mseq: Int, val midx: Int, val mcnt: Int, val ix: Int)
    private class Chunk(val xid: Int, val off: Long, val data: ByteArray)
    private class Frag(val kind: Int, val seq: Int, val idx: Int, val cnt: Int, val data: ByteArray)
    private class Outbound(val xid: Int, val size: Long, val src: RudpSource) {
        var next = 0L
        var peerNext = 0L
        var peerMax = RudpConst.INIT_WINDOW
        var progressRate = 0.0
        var lastDone = 0L
    }

    private val sent = LinkedHashMap<Int, Sent>()
    private val retxData = ArrayDeque<Chunk>()
    private val msgQueue = ArrayDeque<Frag>()
    private var inflightData = 0L
    private var retxBytes = 0L
    private var out: Outbound? = null
    private val earlyResults = HashMap<Int, Int>() // xid → code（接收方结果先于 sendFile 到达）
    private var msgSeqOut = 1
    private var largestAcked = 0
    private var srtt = 0.0
    private var rttvar = 0.0
    private var rtoBackoff = 1
    private var rtoTimer: Cancellable? = null
    private var pumpTimer: Cancellable? = null

    // ---- 速率 / pacing ----
    private var rate = RudpConst.RATE_START.toDouble()
    private var startup = true
    private var tokens = 0.0
    private var tokensAt = 0L
    private var tickAt = 0L
    private var tickDeliveredWire = 0L
    private var tickSentWire = 0L
    private var tickRttSum = 0L
    private var tickRttN = 0
    private var prevSentBps = 0.0
    private var tickIx = 0
    private val sentIx = ArrayList<Long>().also { it.add(0L) }
    private val ackedIx = ArrayList<Long>().also { it.add(0L) }
    private var lagTicks = 0
    private val dHist = ArrayDeque<Double>()
    private var minRtt = 0.0
    private var minRttAt = 0L
    private var lastRttSample = 0.0
    private var lossSent = 0L
    private var lossLost = 0L
    private var lossPct = 0

    // ---- 接收侧 ----
    private class Inbound(val xid: Int, val size: Long, val sink: RudpSink) {
        var next = 0L
        val buf = TreeMap<Long, ByteArray>()
        var bufBytes = 0L
        var finished = false
        var lastRate = 0.0
        var lastNext = 0L
    }
    private val rx = PnTracker()
    private var inb: Inbound? = null
    private val doneXids = HashSet<Int>()
    private var ackPending = 0
    private var ackTimer: Cancellable? = null
    private var lastEliciting = 0L
    private var msgExpect = 1
    private val msgAsm = TreeMap<Int, Array<ByteArray?>>()
    private val msgKinds = HashMap<Int, Int>()

    // ---- 定时器 ----
    private var hsTimer: Cancellable? = null
    private var tickTimer: Cancellable? = null
    private var rateTimer: Cancellable? = null
    private var progTimer: Cancellable? = null
    private var statTimer: Cancellable? = null

    // ============================================================ 公共 API（任意线程）
    fun start() = sched.post(Runnable { doStart() })
    fun onPacket(pkt: ByteArray) = sched.post(Runnable { handle(pkt) })
    /** 小消息 ≤16KB（UTF-8）；过长返回 false。 */
    fun sendMessage(text: String): Boolean {
        val b = text.toByteArray(Charsets.UTF_8)
        if (b.size > RudpConst.MSG_MAX) return false
        sched.post(Runnable { enqueueMsg(0, b) })
        return true
    }
    fun sendFile(xid: Int, size: Long, src: RudpSource) = sched.post(Runnable { doSendFile(xid, size, src) })
    fun recvFile(xid: Int, size: Long, sink: RudpSink) = sched.post(Runnable { doRecvFile(xid, size, sink) })
    fun cancel(xid: Int) = sched.post(Runnable { doCancel(xid) })
    fun setRateKbps(kbps: Int) = sched.post(Runnable {
        cfg.userCapKbps = kbps.coerceIn(64, 20480)
        val cap = cap()
        if (rate > cap) rate = cap
        currentRateBps = rate.toLong()
        pump()
    })
    fun close(why: String) = sched.post(Runnable { doClose(why, true) })

    // ============================================================ 握手
    private fun doStart() {
        if (state != 0) return
        state = 1
        val now = sched.now()
        hsStartAt = now; lastRecvAt = now; lastSendAt = now
        tokensAt = now; tokens = 3000.0
        sendHs(false)
        hsTimer = sched.after(500, Runnable { hsTick() })
        tickTimer = sched.after(1000, Runnable { slowTick() })
    }

    private fun hsTick() {
        hsTimer = null
        if (state == 3) return
        val now = sched.now()
        if (state == 1 && now - hsStartAt >= cfg.handshakeTimeoutMs) {
            state = 3
            cleanupTimers()
            ls.onFail("对方没有回握手")
            return
        }
        if (!peerKnowsUs || !peerSeen) sendHs(false)
        fireOpenIfReady()
        if (!peerKnowsUs || !openFired) hsTimer = sched.after(500, Runnable { hsTick() })
    }

    private fun sendHs(reply: Boolean) {
        val now = sched.now()
        val b = ByteBuffer.allocate(1 + 8 + 8 + 4)
        var flags = 0
        if (peerSeen) flags = flags or 1
        if (!rttKnown) flags = flags or 2
        b.put(flags.toByte())
        b.putLong(now)
        if (lastPeerHsTs != 0L) { b.putLong(lastPeerHsTs); b.putInt((now - lastPeerHsAt).toInt()) } else { b.putLong(0); b.putInt(0) }
        emit(RudpConst.T_HS, b.array())
    }

    private fun onHs(body: ByteArray) {
        if (body.size < 21) return
        val bb = ByteBuffer.wrap(body)
        val flags = bb.get().toInt()
        val ts = bb.long
        val echo = bb.long
        val delay = bb.int
        val now = sched.now()
        if (flags and 1 != 0) peerKnowsUs = true
        lastPeerHsTs = ts; lastPeerHsAt = now
        if (echo != 0L) {
            val r = now - echo - delay
            if (r in 0..60_000) {
                val rr = max(1L, r).toDouble()
                if (!rttKnown) { srtt = rr; rttvar = rr / 2; rttKnown = true; srttMs = srtt.toLong(); noteMinRtt(rr, now) }
            }
        }
        if (flags and 2 != 0) sendHs(true)   // 对方要回显：立即回（不会死循环：回显后对方不再带 2）
        fireOpenIfReady()
    }

    private fun fireOpenIfReady() {
        if (openFired || !peerSeen || state == 3) return
        val now = sched.now()
        if (rttKnown || now - peerSeenAt >= 2000) {
            openFired = true
            ls.onOpen(if (rttKnown) srtt.toInt() else 0)
        }
    }

    private fun markPeerSeen(type: Int) {
        val now = sched.now()
        if (!peerSeen) {
            peerSeen = true; peerSeenAt = now
            if (state == 1) {
                state = 2
                tickAt = now; tokensAt = now
                rateTimer = sched.after(100, Runnable { rateTick() })
                progTimer = sched.after(cfg.progressMs, Runnable { progTick() })
                statTimer = sched.after(cfg.statMs, Runnable { statTick() })
            }
        }
        if (type != RudpConst.T_HS) peerKnowsUs = true
    }

    // ============================================================ 发送：封包
    private fun refill() {
        val now = sched.now()
        val dt = now - tokensAt
        if (dt > 0) {
            tokens += dt * rate / 1000.0
            val burst = max(rate * 0.005, 4000.0)
            if (tokens > burst) tokens = burst
            tokensAt = now
        }
    }

    /** 加密并发出一个包，返回包序号；所有包（含 ACK / 保活 / 握手）都计入速率统计。 */
    private fun emit(type: Int, body: ByteArray): Int {
        val pn = nextPn++
        val pkt = crypto.seal(type, dir, pn, body)
        link(pkt)
        val w = pkt.size + RudpConst.LINK_OVERHEAD
        lastWire = w
        wireSent += w
        tickSentWire += w
        lastSendAt = sched.now()
        refill()
        tokens -= w
        val floor = -rate * 0.5
        if (tokens < floor) tokens = floor
        return pn
    }

    private fun sendDataChunk(c: Chunk, retx: Boolean) {
        val bb = ByteBuffer.allocate(12 + c.data.size)
        bb.putInt(c.xid); bb.putLong(c.off); bb.put(c.data)
        val pn = emit(RudpConst.T_DATA, bb.array())
        val wasEmpty = sent.isEmpty()
        sent[pn] = Sent(pn, sched.now(), lastWire, 0, c.xid, c.off, c.data, 0, 0, 0, 0, tickIx)
        sentIx[tickIx] = sentIx[tickIx] + lastWire
        inflightData += c.data.size
        sentPackets++; lossSent++
        if (retx) retxPackets++
        if (wasEmpty) armRto()
        trackBuffered()
    }

    private fun sendFrag(f: Frag, retx: Boolean) {
        val bb = ByteBuffer.allocate(9 + f.data.size)
        bb.put(f.kind.toByte()); bb.putInt(f.seq); bb.putShort(f.idx.toShort()); bb.putShort(f.cnt.toShort()); bb.put(f.data)
        val pn = emit(RudpConst.T_MSG, bb.array())
        val wasEmpty = sent.isEmpty()
        sent[pn] = Sent(pn, sched.now(), lastWire, 1, 0, 0, f.data, f.kind, f.seq, f.idx, f.cnt, tickIx)
        sentIx[tickIx] = sentIx[tickIx] + lastWire
        sentPackets++
        if (retx) retxPackets++
        if (wasEmpty) armRto()
    }

    private fun trackBuffered() {
        val rb = inb?.bufBytes ?: 0L
        val t = inflightData + retxBytes + rb
        if (t > peakBuffered) peakBuffered = t
    }

    private fun cap() = cfg.userCapKbps.coerceIn(64, 20480) * 1024.0

    // ============================================================ 发送：泵
    private fun schedulePump(delayMs: Long) {
        if (pumpTimer != null) return
        pumpTimer = sched.after(max(1, delayMs), Runnable { pumpTimer = null; pump() })
    }

    private fun pump() {
        if (state != 2) return
        refill()
        var guard = 0
        while (guard++ < 20000) {
            // 1) 小消息优先
            val f = msgQueue.peekFirst()
            if (f != null) {
                msgQueue.pollFirst()   // 小消息 / 控制消息不按令牌延迟（仍计入统计）
                sendFrag(f, false)
                continue
            }
            // 2) 重传
            val o = out
            var c = retxData.peekFirst()
            while (c != null && (o == null || c.xid != o.xid)) { retxBytes -= c.data.size; retxData.pollFirst(); c = retxData.peekFirst() }
            if (c != null) {
                val w = RudpConst.HDR + RudpConst.TAG + 12 + c.data.size + RudpConst.LINK_OVERHEAD
                if (tokens < w) { schedulePump(waitMs(w)); return }
                retxData.pollFirst(); retxBytes -= c.data.size
                sendDataChunk(c, true)
                continue
            }
            // 3) 新数据
            if (o == null || o.next >= o.size) return
            if (inflightData + retxBytes >= RudpConst.SEND_BUF) return
            val len = min(RudpConst.DATA_PAYLOAD.toLong(), o.size - o.next).toInt()
            if (o.next + len > o.peerMax) return            // 接收方窗口已满，等 ACK 更新
            val w = RudpConst.HDR + RudpConst.TAG + 12 + len + RudpConst.LINK_OVERHEAD
            if (tokens < w) { schedulePump(waitMs(w)); return }
            val buf = ByteArray(len)
            var got = 0
            try {
                while (got < len) {
                    val n = o.src.read(buf, got, len - got)
                    if (n < 0) break
                    got += n
                }
            } catch (_: Exception) { failSend(o, "读取文件失败", true); return }
            if (got < len) { failSend(o, "读取文件失败", true); return }
            val off = o.next
            o.next += len
            sendDataChunk(Chunk(o.xid, off, buf), false)
        }
    }

    private fun waitMs(w: Int): Long = max(1L, Math.ceil((w - tokens) * 1000.0 / rate).toLong())

    private fun failSend(o: Outbound, msg: String, notifyPeer: Boolean) {
        if (out !== o) return
        out = null
        try { o.src.close() } catch (_: Exception) {}
        dropRetxFor(o.xid)
        if (notifyPeer) enqueueCtl(1, o.xid, 2)
        ls.onFileDone(o.xid, true, false, msg)
    }

    private fun dropRetxFor(xid: Int) {
        val it = retxData.iterator()
        while (it.hasNext()) { val c = it.next(); if (c.xid == xid) { retxBytes -= c.data.size; it.remove() } }
    }

    // ============================================================ 发送：小消息 / 控制
    private fun enqueueMsg(kind: Int, data: ByteArray) {
        if (state == 3) return
        val seq = msgSeqOut++
        val cnt = max(1, (data.size + RudpConst.MSG_CHUNK - 1) / RudpConst.MSG_CHUNK)
        for (i in 0 until cnt) {
            val from = i * RudpConst.MSG_CHUNK
            val to = min(data.size, from + RudpConst.MSG_CHUNK)
            msgQueue.addLast(Frag(kind, seq, i, cnt, data.copyOfRange(from, to)))
        }
        pump()
    }

    /** kind: 1=取消 2=结果；body = xid(4) + ok/code(1) + code(1)。 */
    private fun enqueueCtl(kind: Int, xid: Int, code: Int, ok: Boolean = false) {
        val bb = ByteBuffer.allocate(6)
        bb.putInt(xid); bb.put((if (ok) 1 else 0).toByte()); bb.put(code.toByte())
        enqueueMsg(kind, bb.array())
    }

    // ============================================================ 文件：发送 / 接收 / 取消
    private fun doSendFile(xid: Int, size: Long, src: RudpSource) {
        if (state == 3) { try { src.close() } catch (_: Exception) {}; ls.onFileDone(xid, true, false, "通道已关闭"); return }
        if (out != null) { try { src.close() } catch (_: Exception) {}; ls.onFileDone(xid, true, false, "上一个文件还没传完"); return }
        val o = Outbound(xid, size, src)
        out = o
        val early = earlyResults.remove(xid)
        if (early != null) { finishSend(o, early); return }
        pump()
    }

    private fun finishSend(o: Outbound, code: Int) {
        if (out !== o) return
        out = null
        try { o.src.close() } catch (_: Exception) {}
        dropRetxFor(o.xid)
        if (code < 0) ls.onFileDone(o.xid, true, true, "完成")
        else ls.onFileDone(o.xid, true, false, ctlText(code))
    }

    private fun ctlText(code: Int) = when (code) {
        0 -> "对方取消"; 1 -> "对方存储空间不足"; 2 -> "对方保存失败"; 3 -> "大小不符"; else -> "对方失败"
    }

    private fun doRecvFile(xid: Int, size: Long, sink: RudpSink) {
        if (state == 3) { sink.abort(); ls.onFileDone(xid, false, false, "通道已关闭"); return }
        val prev = inb
        if (prev != null && !prev.finished) { sink.abort(); ls.onFileDone(xid, false, false, "上一个文件还没收完"); return }
        val r = Inbound(xid, size, sink)
        inb = r
        doneXids.remove(xid)
        if (size == 0L) completeRecv(r) else sendAckNow()
    }

    private fun doCancel(xid: Int) {
        val o = out
        if (o != null && o.xid == xid) {
            out = null
            try { o.src.close() } catch (_: Exception) {}
            dropRetxFor(xid)
            enqueueCtl(1, xid, 0)
            ls.onFileDone(xid, true, false, "已取消")
            return
        }
        val r = inb
        if (r != null && r.xid == xid && !r.finished) {
            r.finished = true
            try { r.sink.abort() } catch (_: Exception) {}
            r.buf.clear(); r.bufBytes = 0
            doneXids.add(xid)
            enqueueCtl(1, xid, 0)
            ls.onFileDone(xid, false, false, "已取消")
        }
    }

    // ============================================================ 接收
    private fun handle(pkt: ByteArray) {
        if (state == 3) return
        wireRecv += pkt.size + RudpConst.LINK_OVERHEAD
        val o = crypto.open(pkt)
        if (o == null) { badPackets++; return }
        if (o.dir == dir) return
        val now = sched.now()
        lastRecvAt = now
        markPeerSeen(o.type)
        if (state == 0) return
        val dup = rx.contains(o.pn)
        when (o.type) {
            RudpConst.T_HS -> { if (!dup) rx.add(o.pn); onHs(o.body) }
            RudpConst.T_KEEP -> { if (!dup) rx.add(o.pn) }
            RudpConst.T_CLOSE -> { doClose("对方中断", false); return }
            RudpConst.T_ACK -> { if (!dup) { rx.add(o.pn); onAck(o.body) } }
            RudpConst.T_DATA -> onData(o, dup)
            RudpConst.T_MSG -> onMsg(o, dup)
        }
        fireOpenIfReady()
    }

    private fun noteEliciting(pn: Int, dup: Boolean) {
        lastEliciting = sched.now()
        ackPending++
        val gap = pn != rx.largest   // 乱序（含重复）：尽快确认
        if (dup || gap || ackPending >= 4) sendAckNow()
        else if (ackTimer == null) ackTimer = sched.after(15, Runnable { ackTimer = null; if (ackPending > 0) sendAckNow() })
    }

    private fun onData(o: PacketCrypto.Opened, dup: Boolean) {
        val b = o.body
        if (b.size < 12) return
        val bb = ByteBuffer.wrap(b)
        val xid = bb.int
        val off = bb.long
        val len = b.size - 12
        val r = inb
        if (r == null || r.xid != xid) {
            if (doneXids.contains(xid) && !dup) { rx.add(o.pn); noteEliciting(o.pn, false) }
            return  // 未登记的 xid：不记录包序号 → 不确认 → 发送方会重传，等 recvFile 登记后收下
        }
        if (dup) { noteEliciting(o.pn, true); return }
        if (r.finished) { rx.add(o.pn); noteEliciting(o.pn, false); return }
        if (off < 0 || off + len > r.size) { rx.add(o.pn); return }
        if (off + len > r.next + RudpConst.RECV_BUF) return   // 超出接收窗口：不确认，等重传
        rx.add(o.pn)
        val data = ByteArray(len)
        System.arraycopy(b, 12, data, 0, len)
        if (off + len > r.next) {
            if (off <= r.next) {
                val skip = (r.next - off).toInt()
                if (!writeSink(r, data, skip, len - skip)) return
                drainInbound(r)
            } else if (!r.buf.containsKey(off)) {
                r.buf[off] = data; r.bufBytes += len
                trackBuffered()
            }
        }
        noteEliciting(o.pn, false)
        if (!r.finished && r.next >= r.size) completeRecv(r)
    }

    private fun writeSink(r: Inbound, data: ByteArray, off: Int, len: Int): Boolean {
        val res = try { r.sink.write(data, off, len) } catch (_: Exception) { "err:io" }
        if (res != "ok") {
            failRecv(r, if (res.contains("nospace")) 1 else 2, if (res.contains("nospace")) "存储空间不足" else "保存失败")
            return false
        }
        r.next += len
        return true
    }

    private fun drainInbound(r: Inbound) {
        while (!r.finished) {
            val e = r.buf.firstEntry() ?: break
            if (e.key > r.next) break
            r.buf.remove(e.key); r.bufBytes -= e.value.size
            val skip = (r.next - e.key).toInt()
            if (skip >= e.value.size) continue
            if (!writeSink(r, e.value, skip, e.value.size - skip)) return
        }
    }

    private fun failRecv(r: Inbound, code: Int, msg: String) {
        if (r.finished) return
        r.finished = true
        try { r.sink.abort() } catch (_: Exception) {}
        r.buf.clear(); r.bufBytes = 0
        doneXids.add(r.xid)
        enqueueCtl(2, r.xid, code, false)
        ls.onFileDone(r.xid, false, false, msg)
    }

    private fun completeRecv(r: Inbound) {
        if (r.finished) return
        r.finished = true
        r.buf.clear(); r.bufBytes = 0
        doneXids.add(r.xid)
        val res = try { r.sink.finish() } catch (_: Exception) { "err:io" }
        if (res.startsWith("ok")) {
            enqueueCtl(2, r.xid, 0, true)
            ls.onFileDone(r.xid, false, true, res.removePrefix("ok:"))
        } else {
            val code = when { res.contains("nospace") -> 1; res.contains("size") -> 3; else -> 2 }
            enqueueCtl(2, r.xid, code, false)
            ls.onFileDone(r.xid, false, false, ctlText(code).removePrefix("对方"))
        }
        sendAckNow()
    }

    private fun onMsg(o: PacketCrypto.Opened, dup: Boolean) {
        if (dup) { noteEliciting(o.pn, true); return }
        val b = o.body
        if (b.size < 9) { rx.add(o.pn); return }
        val bb = ByteBuffer.wrap(b)
        val kind = bb.get().toInt()
        val seq = bb.int
        val idx = bb.short.toInt() and 0xffff
        val cnt = bb.short.toInt() and 0xffff
        if (cnt == 0 || idx >= cnt || cnt > 32) { rx.add(o.pn); return }
        if (seq - msgExpect > 64) return     // 太超前：不确认，等重传
        rx.add(o.pn)
        if (seq >= msgExpect) {
            val arr = msgAsm.getOrPut(seq) { arrayOfNulls(cnt) }
            if (arr.size == cnt && arr[idx] == null) arr[idx] = b.copyOfRange(9, b.size)
            msgKinds[seq] = kind
            while (true) {
                val a = msgAsm[msgExpect] ?: break
                if (a.any { it == null }) break
                msgAsm.remove(msgExpect)
                val k = msgKinds.remove(msgExpect) ?: 0
                msgExpect++
                val total = a.sumOf { it!!.size }
                val bytes = ByteArray(total)
                var p = 0
                for (x in a) { System.arraycopy(x!!, 0, bytes, p, x.size); p += x.size }
                deliverMsg(k, bytes)
                if (state == 3) return
            }
        }
        noteEliciting(o.pn, false)
    }

    private fun deliverMsg(kind: Int, bytes: ByteArray) {
        when (kind) {
            0 -> ls.onMessage(String(bytes, Charsets.UTF_8))
            1 -> if (bytes.size >= 6) {   // 对方取消
                val bb = ByteBuffer.wrap(bytes); val xid = bb.int; bb.get(); val code = bb.get().toInt()
                val o = out
                if (o != null && o.xid == xid) finishSend(o, if (code == 2) 2 else 0)
                val r = inb
                if (r != null && r.xid == xid && !r.finished) {
                    r.finished = true
                    try { r.sink.abort() } catch (_: Exception) {}
                    r.buf.clear(); r.bufBytes = 0
                    doneXids.add(xid)
                    ls.onFileDone(xid, false, false, "对方取消")
                }
            }
            2 -> if (bytes.size >= 6) {   // 接收方结果
                val bb = ByteBuffer.wrap(bytes); val xid = bb.int; val ok = bb.get().toInt() == 1; val code = bb.get().toInt()
                val o = out
                if (o != null && o.xid == xid) finishSend(o, if (ok) -1 else code)
                else earlyResults[xid] = if (ok) -1 else code
            }
        }
    }

    // ============================================================ ACK
    private fun sendAckNow() {
        ackTimer?.cancel(); ackTimer = null
        ackPending = 0
        val ranges = rx.rangesDesc(48)
        if (ranges.isEmpty()) return
        val now = sched.now()
        val delay = min(60_000L, max(0L, now - lastEliciting)).toInt()
        val r = inb
        val bb = ByteBuffer.allocate(4 + 2 + 1 + 4 + (ranges.size - 1) * 8 + 4 + 8 + 8)
        bb.putInt(ranges[0][1])
        bb.putShort(delay.toShort())
        bb.put(ranges.size.toByte())
        bb.putInt(ranges[0][1] - ranges[0][0] + 1)
        for (i in 1 until ranges.size) {
            bb.putInt(ranges[i - 1][0] - ranges[i][1] - 1)   // gap
            bb.putInt(ranges[i][1] - ranges[i][0] + 1)       // len
        }
        bb.putInt(r?.xid ?: 0)
        bb.putLong(r?.next ?: 0L)
        bb.putLong(if (r == null) 0L else r.next + RudpConst.RECV_BUF)
        emit(RudpConst.T_ACK, bb.array())
    }

    private fun onAck(b: ByteArray) {
        if (b.size < 11) return
        val bb = ByteBuffer.wrap(b)
        val largest = bb.int
        val delay = bb.short.toInt() and 0xffff
        val n = bb.get().toInt() and 0xff
        if (n == 0 || b.size < 11 + (n - 1) * 8 + 20) return
        val starts = IntArray(n); val ends = IntArray(n)
        var hi = largest
        var len = bb.int
        ends[n - 1] = hi; starts[n - 1] = hi - len + 1
        var prevStart = starts[n - 1]
        for (i in 1 until n) {
            val gap = bb.int; len = bb.int
            val e = prevStart - gap - 1
            ends[n - 1 - i] = e; starts[n - 1 - i] = e - len + 1
            prevStart = starts[n - 1 - i]
        }
        val xid = bb.int
        val recvNext = bb.long
        val maxOff = bb.long
        val now = sched.now()

        // 按序号升序扫描未确认包，命中任一区间即已确认
        var ri = 0
        var progressed = false
        val it = sent.entries.iterator()
        var rttSample = -1.0
        while (it.hasNext()) {
            val s = it.next().value
            while (ri < n && ends[ri] < s.pn) ri++
            if (ri >= n) break
            if (s.pn >= starts[ri]) {
                it.remove()
                progressed = true
                tickDeliveredWire += s.wire
                ackedIx[s.ix] = ackedIx[s.ix] + s.wire
                if (s.kind == 0) inflightData -= s.data!!.size
                if (s.pn == largest) rttSample = max(1.0, (now - s.time - delay).toDouble())
            }
        }
        if (largest > largestAcked) largestAcked = largest
        if (rttSample > 0) onRttSample(rttSample, now)
        if (progressed) rtoBackoff = 1

        // 接收方窗口 / 进度
        val o = out
        if (o != null && xid != 0 && xid == o.xid) {
            if (recvNext > o.peerNext) o.peerNext = recvNext
            if (maxOff > o.peerMax) o.peerMax = maxOff
        }
        detectLoss(now)
        armRto()
        pump()
    }

    private fun onRttSample(r: Double, now: Long) {
        lastRttSample = r
        tickRttSum += r.toLong(); tickRttN++
        if (srtt == 0.0 || !rttKnown) { srtt = r; rttvar = r / 2; rttKnown = true }
        else { rttvar = 0.75 * rttvar + 0.25 * Math.abs(srtt - r); srtt = 0.875 * srtt + 0.125 * r }
        srttMs = srtt.toLong()
        noteMinRtt(r, now)
        fireOpenIfReady()
    }

    private fun noteMinRtt(r: Double, now: Long) {
        if (minRtt == 0.0 || r <= minRtt || now - minRttAt > 10_000) { minRtt = r; minRttAt = now }
    }

    // ============================================================ 丢包判断 / RTO
    private fun rto(): Long {
        val base = if (!rttKnown) 1000.0 else srtt + max(4 * rttvar, 20.0) + 25
        return min(4000.0, max(150.0, base * rtoBackoff)).toLong()
    }

    private fun detectLoss(now: Long) {
        if (sent.isEmpty()) return
        val timeThresh = 1.25 * max(srtt, lastRttSample) + 10
        val it = sent.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (s.pn >= largestAcked) break
            if (s.pn <= largestAcked - 3 || now - s.time >= timeThresh) { it.remove(); onLost(s) } else break
        }
    }

    private fun onLost(s: Sent) {
        lostPackets++; lossLost++
        if (s.kind == 0) {
            inflightData -= s.data!!.size
            val o = out
            if (o != null && o.xid == s.xid) { retxData.addLast(Chunk(s.xid, s.off, s.data)); retxBytes += s.data.size }
        } else {
            msgQueue.addFirst(Frag(s.mkind, s.mseq, s.midx, s.mcnt, s.data!!))
        }
    }

    private fun armRto() {
        rtoTimer?.cancel(); rtoTimer = null
        val first = sent.values.firstOrNull() ?: return
        val at = first.time + rto()
        rtoTimer = sched.after(max(1, at - sched.now()), Runnable { rtoTimer = null; onRto() })
    }

    private fun onRto() {
        if (state != 2) return
        val now = sched.now()
        val r = rto()
        var any = false
        val it = sent.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (now - s.time >= r) { it.remove(); onLost(s); any = true } else break
        }
        if (any) { rtoBackoff = min(rtoBackoff * 2, 16) }
        armRto()
        pump()
    }

    // ============================================================ 速率控制
    private fun rateTick() {
        rateTimer = null
        if (state != 2) return
        val now = sched.now()
        val dt = max(1L, now - tickAt)
        val deliveredBps = tickDeliveredWire * 1000.0 / dt
        val sentBps = tickSentWire * 1000.0 / dt
        val rttNow = if (tickRttN > 0) tickRttSum.toDouble() / tickRttN else srtt
        val appLimited = sentBps < 0.6 * rate
        val cap = cap()
        if (tickRttN > 0 && deliveredBps > 0 && !appLimited) {
            dHist.addLast(deliveredBps)
            while (dHist.size > 4) dHist.pollFirst()
            var dmax = 0.0
            for (d in dHist) if (d > dmax) dmax = d
            val inflated = minRtt > 0 && rttNow > minRtt * RudpConst.RTT_INFLATE + RudpConst.RTT_INFLATE_ADD
            if (startup) {
                // 两个区间前发出的包的确认比例（那时的包已有足够时间被确认）
                val j = tickIx - 2
                var lagging = false
                if (j >= 0 && sentIx[j] > 30 * 1200L && ackedIx[j] < RudpConst.RATE_LAG * sentIx[j]) lagging = true
                if (lagging) lagTicks++ else lagTicks = 0
                if (lagTicks >= 1 || inflated || rate >= cap) {
                    startup = false
                    rate = min(cap, max(RudpConst.RATE_MIN.toDouble(), dmax * RudpConst.RATE_TARGET))
                } else rate = min(cap, rate * RudpConst.RATE_GROW)
            } else {
                rate = min(cap, max(RudpConst.RATE_MIN.toDouble(), if (inflated) dmax else dmax * RudpConst.RATE_TARGET))
            }
            currentRateBps = rate.toLong()
        }
        prevSentBps = sentBps
        tickIx++; sentIx.add(0L); ackedIx.add(0L)
        tickAt = now; tickDeliveredWire = 0; tickSentWire = 0; tickRttSum = 0; tickRttN = 0
        val interval = min(1000.0, max(50.0, srtt)).toLong()
        rateTimer = sched.after(interval, Runnable { rateTick() })
        pump()
    }

    // ============================================================ 进度 / 统计 / 保活 / 关闭
    private fun progTick() {
        progTimer = null
        if (state != 2) return
        val loss = if (lossSent > 20) (100 * lossLost / lossSent).toInt() else lossPct
        lossPct = loss; lossSent = 0; lossLost = 0
        val secs = cfg.progressMs / 1000.0
        val o = out
        if (o != null) {
            val done = min(o.size, o.peerNext)
            val r = (done - o.lastDone) / secs
            o.progressRate = if (o.progressRate == 0.0) r else 0.5 * o.progressRate + 0.5 * r
            o.lastDone = done
            ls.onProgress(o.xid, true, done, o.size, o.progressRate.toLong(), lossPct, srtt.toInt())
        }
        val r = inb
        if (r != null && !r.finished) {
            val rate2 = (r.next - r.lastNext) / secs
            r.lastRate = if (r.lastRate == 0.0) rate2 else 0.5 * r.lastRate + 0.5 * rate2
            r.lastNext = r.next
            ls.onProgress(r.xid, false, r.next, r.size, r.lastRate.toLong(), lossPct, srtt.toInt())
        }
        progTimer = sched.after(cfg.progressMs, Runnable { progTick() })
    }

    private fun statTick() {
        statTimer = null
        if (state != 2) return
        ls.onStat(wireSent, wireRecv)
        statTimer = sched.after(cfg.statMs, Runnable { statTick() })
    }

    private fun slowTick() {
        tickTimer = null
        if (state == 3) return
        val now = sched.now()
        val stalled = lastTickAt != 0L && now - lastTickAt > cfg.stallGraceMs
        lastTickAt = now
        if (state == 2) {
            // 本端自己停过（慢滴答本该 1 秒一次）：对方的包可能还堵在系统缓冲里，重新给一个完整窗口
            if (stalled) lastRecvAt = now
            if (now - lastRecvAt >= cfg.deadMs) { doClose("${cfg.deadMs / 1000} 秒没有数据", false); return }
            if (now - lastSendAt >= cfg.keepMs) emit(RudpConst.T_KEEP, ByteArray(0))
        }
        tickTimer = sched.after(1000, Runnable { slowTick() })
    }

    private fun cleanupTimers() {
        for (t in listOf(hsTimer, tickTimer, rateTimer, progTimer, statTimer, rtoTimer, pumpTimer, ackTimer)) t?.cancel()
        hsTimer = null; tickTimer = null; rateTimer = null; progTimer = null; statTimer = null; rtoTimer = null; pumpTimer = null; ackTimer = null
    }

    private fun doClose(why: String, notifyPeer: Boolean) {
        if (state == 3) return
        val wasOpen = state != 0
        if (notifyPeer && state == 2) { repeat(3) { emit(RudpConst.T_CLOSE, ByteArray(0)) } }
        state = 3
        cleanupTimers()
        val o = out
        if (o != null) { out = null; try { o.src.close() } catch (_: Exception) {}; ls.onFileDone(o.xid, true, false, why) }
        val r = inb
        if (r != null && !r.finished) {
            r.finished = true
            try { r.sink.abort() } catch (_: Exception) {}
            r.buf.clear(); r.bufBytes = 0
            ls.onFileDone(r.xid, false, false, why)
        }
        sent.clear(); retxData.clear(); msgQueue.clear(); msgAsm.clear()
        inflightData = 0; retxBytes = 0
        if (wasOpen) ls.onClosed(why)
    }
}

fun randomKey(): ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
