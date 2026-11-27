package com.phoneshare.app.relay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** code = TURN 错误码（0 = 不是 TURN 错误回应）；timeout = 请求超时没有任何回应。 */
class TurnException(message: String, val code: Int = 0, val timeout: Boolean = false) : Exception(message)

/** 时间参数；单元测试里缩短。 */
class TurnConfig(
    val rtoStartMs: Long = 500,
    val rtoMaxMs: Long = 8000,
    val allocateTotalMs: Long = 20_000,
    val requestTotalMs: Long = 10_000,
    val allocRefreshMs: Long = 5 * 60_000,
    val permRefreshMs: Long = 4 * 60_000,
    val bindRefreshMs: Long = 9 * 60_000,
    val refreshRetryGapMs: Long = 2_000,
    val releaseWaitMs: Long = 1_000
) {
    fun withAllocateTotal(ms: Long) = TurnConfig(rtoStartMs, rtoMaxMs, ms, requestTotalMs, allocRefreshMs, permRefreshMs, bindRefreshMs, refreshRetryGapMs, releaseWaitMs)
}

/**
 * TURN over UDP 客户端（ChannelData），纯 Kotlin。
 * 阻塞方法（allocate / permitAndBind / releaseAndClose）必须在后台线程调用。
 * 只有一个接收线程读 socket：STUN 响应按事务 ID 分发，ChannelData 交给 onData。
 * 日志不含用户名、密码、地址。
 */
class TurnClient(
    private val server: InetSocketAddress,
    private val user: String,
    private val pass: String,
    private val cfg: TurnConfig = TurnConfig(),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val log: (String) -> Unit = {}
) {
    @Volatile var onData: (ByteArray) -> Unit = {}
    /** 续期连续失败或 socket 异常 → 回调中文原因（只回调一次）。 */
    @Volatile var onFatal: (String) -> Unit = {}

    private var socket: DatagramSocket? = null
    @Volatile private var realm = ""
    @Volatile private var nonce = ""
    @Volatile private var key: ByteArray? = null
    @Volatile private var relayed: Pair<String, Int>? = null
    @Volatile private var lifetimeSec = 600
    private var peer: Pair<String, Int>? = null
    private val channel = 0x4000
    private val closed = AtomicBoolean(false)
    private val fatalFired = AtomicBoolean(false)
    private val sentBytes = AtomicLong(0)
    private val recvBytes = AtomicLong(0)
    private var timers: ScheduledThreadPoolExecutor? = null
    private var rx: Thread? = null

    private class Pending { val q = LinkedBlockingQueue<Stun.Msg>(4) }
    private val pending = ConcurrentHashMap<String, Pending>()

    fun wireSent() = sentBytes.get()
    fun wireRecv() = recvBytes.get()
    fun relayedAddress(): String? = relayed?.let { "${it.first}:${it.second}" }

    // ------------------------------------------------------------ allocate
    /** Allocate；成功返回 "IP:端口"（中继地址）。失败抛 TurnException(中文原因)。 */
    fun allocate(): String {
        val s = socketFactory()
        socket = s
        startReceiver(s)
        // 1) 无凭证试探，拿 realm / nonce
        val t0 = System.currentTimeMillis()
        var resp = transact(cfg.allocateTotalMs) { tx ->
            Stun.build(Stun.ALLOCATE_REQUEST, tx, listOf(Stun.requestedTransportUdp()))
        } ?: throw TurnException("中继服务器没有响应", timeout = true)
        if (resp.type == Stun.ALLOCATE_ERROR && resp.errorCode() == 401) {
            realm = resp.str(Stun.ATTR_REALM) ?: ""
            nonce = resp.str(Stun.ATTR_NONCE) ?: ""
            key = Stun.longTermKey(user, realm, pass)
            // 一次尝试（含 401 带凭证重试那一轮）总共不超过 allocateTotalMs
            resp = authed(Stun.ALLOCATE_REQUEST, listOf(Stun.requestedTransportUdp()), maxOf(800L, cfg.allocateTotalMs - (System.currentTimeMillis() - t0)), true)
        } else if (resp.type == Stun.ALLOCATE_ERROR) {
            throw TurnException(errorReason(resp.errorCode() ?: 0, false), resp.errorCode() ?: 0)
        }
        if (resp.type != Stun.ALLOCATE_SUCCESS) throw TurnException("分配失败")
        val addr = Stun.parseXorAddr(resp.get(Stun.ATTR_XOR_RELAYED_ADDRESS))
            ?: throw TurnException("中继返回的地址不是 IPv4，无法使用")
        relayed = addr
        lifetimeSec = resp.lifetime() ?: 600
        startTimers()
        return "${addr.first}:${addr.second}"
    }

    /** 对对方中继地址做 CreatePermission + ChannelBind。 */
    fun permitAndBind(peerIp: String, peerPort: Int) {
        peer = peerIp to peerPort
        doPermission()
        doBind()
    }

    private fun doPermission() {
        val p = peer ?: return
        authed(Stun.CREATE_PERMISSION_REQUEST, listOf(Stun.xorAddr(Stun.ATTR_XOR_PEER_ADDRESS, p.first, p.second)), cfg.requestTotalMs, false)
    }

    private fun doBind() {
        val p = peer ?: return
        authed(
            Stun.CHANNEL_BIND_REQUEST,
            listOf(Stun.channelNumber(channel), Stun.xorAddr(Stun.ATTR_XOR_PEER_ADDRESS, p.first, p.second)),
            cfg.requestTotalMs, false
        )
    }

    private fun doRefresh() {
        val r = authed(Stun.REFRESH_REQUEST, emptyList(), cfg.requestTotalMs, false)
        r.lifetime()?.let { lifetimeSec = it }
    }

    // ------------------------------------------------------------ data
    fun send(payload: ByteArray) {
        val s = socket ?: return
        if (closed.get()) return
        val pkt = ByteArray(4 + payload.size)
        pkt[0] = (channel ushr 8).toByte(); pkt[1] = channel.toByte()
        pkt[2] = (payload.size ushr 8).toByte(); pkt[3] = payload.size.toByte()
        System.arraycopy(payload, 0, pkt, 4, payload.size)
        try {
            s.send(DatagramPacket(pkt, pkt.size, server))
            sentBytes.addAndGet(pkt.size.toLong())
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ 释放
    /** 同步尽力释放：先发 Refresh(lifetime=0)（最多 releaseWaitMs），再关 socket。后台线程调用。 */
    fun releaseAndClose() {
        if (closed.get()) return
        stopTimers()
        if (relayed != null && key != null) {
            try {
                for (attempt in 0..1) {
                    val r = transact(cfg.releaseWaitMs) { tx -> authedBytes(Stun.REFRESH_REQUEST, listOf(Stun.lifetime(0)), tx) } ?: break
                    if (r.isError && r.errorCode() == 438) { nonce = r.str(Stun.ATTR_NONCE) ?: nonce; continue }
                    break
                }
            } catch (_: Exception) {}
        }
        close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopTimers()
        try { socket?.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ 内部
    private fun startReceiver(s: DatagramSocket) {
        val t = Thread {
            val buf = ByteArray(2048)
            while (!closed.get()) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    s.soTimeout = 1000
                    s.receive(dp)
                    recvBytes.addAndGet(dp.length.toLong())
                    handleDatagram(buf, dp.length)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (!closed.get()) fatal("网络出错")
                    break
                }
            }
        }
        t.isDaemon = true
        t.name = "turn-rx"
        rx = t
        t.start()
    }

    private fun handleDatagram(buf: ByteArray, len: Int) {
        if (len < 4) return
        val b0 = buf[0].toInt() and 0xff
        if (b0 in 0x40..0x7f) {
            val ch = (b0 shl 8) or (buf[1].toInt() and 0xff)
            val l = ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
            if (ch != channel || len < 4 + l) return
            try { onData(buf.copyOfRange(4, 4 + l)) } catch (_: Exception) {}
            return
        }
        if (b0 and 0xC0 != 0) return
        val m = Stun.parse(buf.copyOf(len)) ?: return
        val p = pending[hex(m.txId)] ?: return
        // 成功响应必须带正确的 MESSAGE-INTEGRITY；错误响应若带了也必须正确
        val k = key
        if (k != null) {
            if (m.isSuccess && !Stun.verifyIntegrity(m, k)) { log("丢弃：成功响应校验失败"); return }
            if (m.isError && Stun.hasIntegrity(m) && !Stun.verifyIntegrity(m, k)) { log("丢弃：错误响应校验失败"); return }
        }
        p.q.offer(m)
    }

    private fun hex(b: ByteArray): String { val sb = StringBuilder(); for (x in b) sb.append(String.format("%02x", x)); return sb.toString() }

    /** 同一事务 ID 重传：RTO 从 rtoStartMs 起翻倍，总时长 totalMs；无响应返回 null。 */
    private fun transact(totalMs: Long, build: (ByteArray) -> ByteArray): Stun.Msg? {
        val s = socket ?: return null
        val tx = Stun.newTxId()
        val req = build(tx)
        val p = Pending()
        pending[hex(tx)] = p
        try {
            val deadline = System.currentTimeMillis() + totalMs
            var rto = cfg.rtoStartMs
            while (!closed.get()) {
                val now = System.currentTimeMillis()
                if (now >= deadline) return null
                try {
                    s.send(DatagramPacket(req, req.size, server))
                    sentBytes.addAndGet(req.size.toLong())
                } catch (_: Exception) {}
                val wait = minOf(rto, deadline - System.currentTimeMillis()).coerceAtLeast(1)
                val m = p.q.poll(wait, TimeUnit.MILLISECONDS)
                if (m != null) return m
                rto = minOf(rto * 2, cfg.rtoMaxMs)
            }
            return null
        } finally {
            pending.remove(hex(tx))
        }
    }

    private fun authedBytes(type: Int, extra: List<Stun.Attr>, tx: ByteArray): ByteArray {
        val attrs = ArrayList<Stun.Attr>(extra)
        attrs.add(Stun.str(Stun.ATTR_USERNAME, user))
        attrs.add(Stun.str(Stun.ATTR_REALM, realm))
        attrs.add(Stun.str(Stun.ATTR_NONCE, nonce))
        return Stun.build(type, tx, attrs, key)
    }

    /**
     * 带长期凭证的请求：438 用新 nonce 重试；401（已带过凭证）= 凭证被拒；其它错误码转中文。
     * @param allocate 是否是 Allocate（超时原因不同）
     */
    private fun authed(type: Int, extra: List<Stun.Attr>, totalMs: Long, allocate: Boolean): Stun.Msg {
        var tries = 0
        while (true) {
            val r = transact(totalMs) { tx -> authedBytes(type, extra, tx) }
                ?: throw TurnException(if (allocate) "中继服务器没有响应" else "中继服务器没有响应（${typeName(type)}）", timeout = true)
            if (r.isSuccess) return r
            val code = r.errorCode() ?: 0
            if (code == 438 && tries < 2) {
                nonce = r.str(Stun.ATTR_NONCE) ?: nonce
                tries++
                continue
            }
            throw TurnException(errorReason(code, true), code)
        }
    }

    private fun typeName(t: Int) = when (t) {
        Stun.REFRESH_REQUEST -> "续期"; Stun.CREATE_PERMISSION_REQUEST -> "授权"; Stun.CHANNEL_BIND_REQUEST -> "绑定通道"; else -> "请求"
    }

    // ------------------------------------------------------------ 续期
    private fun startTimers() {
        val ex = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "turn-timer").also { it.isDaemon = true } }
        ex.removeOnCancelPolicy = true
        timers = ex
        val allocMs = minOf(cfg.allocRefreshMs, lifetimeSec * 500L).coerceAtLeast(100)
        ex.scheduleWithFixedDelay({ refreshWithRetry("Allocation") { doRefresh() } }, allocMs, allocMs, TimeUnit.MILLISECONDS)
        ex.scheduleWithFixedDelay({ if (peer != null) refreshWithRetry("Permission") { doPermission() } }, cfg.permRefreshMs, cfg.permRefreshMs, TimeUnit.MILLISECONDS)
        ex.scheduleWithFixedDelay({ if (peer != null) refreshWithRetry("ChannelBind") { doBind() } }, cfg.bindRefreshMs, cfg.bindRefreshMs, TimeUnit.MILLISECONDS)
    }

    private fun stopTimers() {
        try { timers?.shutdownNow() } catch (_: Exception) {}
    }

    /** 失败重试 3 次（间隔 refreshRetryGapMs），仍失败 → fatal。 */
    private fun refreshWithRetry(what: String, op: () -> Unit) {
        if (closed.get()) return
        var lastErr = ""
        for (i in 1..3) {
            if (closed.get()) return
            try { op(); return } catch (e: TurnException) { lastErr = e.message ?: "" } catch (_: Exception) {}
            log("续期失败 $what 第 $i 次")
            try { Thread.sleep(cfg.refreshRetryGapMs) } catch (_: InterruptedException) { return }
        }
        fatal("中继续期失败，通道已断开")
    }

    private fun fatal(why: String) {
        if (closed.get()) return
        if (fatalFired.compareAndSet(false, true)) {
            try { onFatal(why) } catch (_: Exception) {}
        }
    }

    companion object {
        /** 错误码 → 中文原因。afterCredentials：已带凭证发过请求（此时 401 = 凭证被拒）。 */
        fun errorReason(code: Int, afterCredentials: Boolean): String = when (code) {
            401 -> if (afterCredentials) "凭证被拒" else "中继需要认证"
            403 -> "凭证被拒"
            437 -> "分配被拒（已存在）"
            438 -> "中继认证过期"
            441 -> "分配被拒（凭证不匹配）"
            442 -> "分配被拒（不支持 UDP）"
            486 -> "被中继限额"
            508 -> "中继容量不足"
            0 -> "分配失败"
            else -> "中继返回错误 $code"
        }

        class Picked(val host: String, val port: Int)

        /** 只取 turn: 且不带 transport=tcp；排除 turns:。 */
        fun pickUdpTurnUrl(urls: List<String>): Picked? = pickAllUdpTurnUrls(urls).firstOrNull()

        /** 同上，但按原顺序返回全部（去重），用于第一个不响应时换下一个。 */
        fun pickAllUdpTurnUrls(urls: List<String>): List<Picked> {
            val out = ArrayList<Picked>()
            for (u in urls) {
                val s = u.trim()
                if (s.startsWith("turns:", true) || !s.startsWith("turn:", true)) continue
                if (s.contains("transport=tcp", true)) continue
                var rest = s.substring(5)
                if (rest.startsWith("//")) rest = rest.substring(2)
                rest = rest.substringBefore('?').substringBefore('/')
                val host: String
                val port: Int
                if (rest.startsWith("[")) {
                    val end = rest.indexOf(']')
                    if (end < 0) continue
                    host = rest.substring(1, end)
                    port = if (end + 1 < rest.length && rest[end + 1] == ':') rest.substring(end + 2).toIntOrNull() ?: 3478 else 3478
                } else {
                    val idx = rest.lastIndexOf(':')
                    if (idx > 0) { host = rest.substring(0, idx); port = rest.substring(idx + 1).toIntOrNull() ?: 3478 }
                    else { host = rest; port = 3478 }
                }
                if (host.isNotEmpty() && out.none { it.host.equals(host, true) && it.port == port }) out.add(Picked(host, port))
            }
            return out
        }

        /** 域名解析出全部 IPv4（字符串）；失败抛 TurnException("域名解析失败")。 */
        fun resolveAllV4(host: String): List<String> {
            val l = try { InetAddress.getAllByName(host).filterIsInstance<java.net.Inet4Address>().map { it.hostAddress ?: "" }.filter { it.isNotEmpty() } } catch (_: Exception) { emptyList() }
            if (l.isEmpty()) throw TurnException("域名解析失败")
            return l
        }

        /** 域名解析成 IPv4；失败抛 TurnException("域名解析失败")。 */
        fun resolveV4(host: String): InetAddress {
            try {
                val all = InetAddress.getAllByName(host)
                return all.firstOrNull { it is java.net.Inet4Address } ?: throw TurnException("域名解析失败（没有 IPv4 地址）")
            } catch (e: TurnException) { throw e } catch (_: Exception) { throw TurnException("域名解析失败") }
        }
    }
}
