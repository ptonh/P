package com.phoneshare.app.relay

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class IceServer(val urls: List<String>, val username: String, val credential: String)

/** 回调线程不固定（后台线程 / 调度线程），不要阻塞；NativeRelay 里转成 JSON 事件即可。 */
interface RelayEvents {
    /** 本端中继地址；key 只有 role="s" 才非空（base64，32 字节）。 */
    fun onReady(addr: String, keyB64: String)
    fun onOpen(rttMs: Int)
    fun onFail(reason: String)
    fun onClosed(why: String)
    fun onMsg(text: String)
    fun onProg(fid: String, sending: Boolean, done: Long, total: Long, rateBps: Long, lossPct: Int, rttMs: Int)
    fun onDone(fid: String, sending: Boolean, ok: Boolean, msg: String)
    /** 累计字节，口径 = DatagramSocket 实际收发（含 ChannelData 头、STUN、重传、ACK、保活）；每 10 秒一次。 */
    fun onStat(sent: Long, recv: Long)
}

class RelayCfg(
    val rudp: RudpConfig = RudpConfig(),
    val turn: TurnConfig = TurnConfig()
)

/**
 * 一条中继会话 = 一个 Allocation + 一条 RUDP 通道。纯 Kotlin。
 * 生命周期：open → (relayReady) → connect → (relayOpen) → sendMsg / sendFile / recvFile → close。
 * 任何一步失败只报 onFail / onClosed 一次，并自动释放 Allocation；不会改走别的通道。
 *
 * fid 是网页给的字符串；内部用 fid.hashCode()（0 改为 1）作传输编号，两端必须用同一个 fid。
 */
class RelaySession(
    private val role: String,                     // "s" 发起方（生成密钥） / "r" 接收方
    private val ev: RelayEvents,
    private val cfg: RelayCfg = RelayCfg(),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    private val log: (String) -> Unit = {}
) {
    private val lock = Any()
    private var turn: TurnClient? = null
    private var sched: RealSched? = null
    private var chan: RudpChannel? = null
    private var ownKey: ByteArray? = null
    @Volatile private var ended = false
    private val busy = AtomicBoolean(true)
    private val fids = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var rateKbps = cfg.rudp.userCapKbps
    @Volatile private var connected = false

    init { require(role == "s" || role == "r") { "role 必须是 s 或 r" } }

    fun isBusy(): Boolean = busy.get()

    companion object {
        fun xidOf(fid: String): Int { val h = fid.hashCode(); return if (h == 0) 1 else h }
    }

    // ------------------------------------------------------------ open：Allocate
    /** 异步。成功 → onReady；失败 → onFail（并释放）。 */
    fun open(ice: List<IceServer>) {
        Thread {
            try {
                val urls = ArrayList<String>(); var user = ""; var pass = ""
                for (s in ice) {
                    urls.addAll(s.urls)
                    if (user.isEmpty() && s.username.isNotEmpty()) { user = s.username; pass = s.credential }
                }
                val picked = TurnClient.pickUdpTurnUrl(urls) ?: throw TurnException("没有可用的中继地址")
                val ip = TurnClient.resolveV4(picked.host)
                val tc = TurnClient(InetSocketAddress(ip, picked.port), user, pass, cfg.turn, socketFactory, log)
                synchronized(lock) { if (ended) { tc.close(); return@Thread }; turn = tc }
                tc.onFatal = { why -> terminate(why, true) }
                val addr = tc.allocate()
                var keyB64 = ""
                if (role == "s") {
                    val k = randomKey(); ownKey = k
                    keyB64 = java.util.Base64.getEncoder().encodeToString(k)
                }
                if (!ended) ev.onReady(addr, keyB64)
            } catch (e: TurnException) {
                terminate(e.message ?: "分配失败", false)
            } catch (e: Exception) {
                terminate("分配失败", false)
            }
        }.also { it.isDaemon = true; it.name = "relay-open" }.start()
    }

    // ------------------------------------------------------------ connect：授权 + 绑定 + 握手
    /** peerAddr = 对方 relayReady 的 addr（IP:端口）；keyB64：role="r" 必须给发起方的 key，role="s" 忽略。 */
    fun connect(peerAddr: String, keyB64: String) {
        Thread {
            try {
                val tc = turn ?: throw TurnException("还没有分配中继地址")
                if (connected) throw TurnException("已经连接")
                val ix = peerAddr.lastIndexOf(':')
                if (ix <= 0) throw TurnException("对方地址无效")
                val pip = peerAddr.substring(0, ix)
                val pport = peerAddr.substring(ix + 1).toIntOrNull() ?: throw TurnException("对方地址无效")
                val key: ByteArray = if (role == "s") ownKey ?: throw TurnException("还没有会话密钥")
                else try { java.util.Base64.getDecoder().decode(keyB64) } catch (_: Exception) { throw TurnException("会话密钥无效") }
                if (key.size != 32) throw TurnException("会话密钥无效")
                tc.permitAndBind(pip, pport)
                val sc = RealSched("rudp-relay")
                val ch = RudpChannel(key, if (role == "s") 0 else 1, sc, { pkt -> tc.send(pkt) }, listener, cfg.rudp)
                synchronized(lock) { if (ended) { sc.shutdown(); return@Thread }; sched = sc; chan = ch; connected = true }
                ch.setRateKbps(rateKbps)
                tc.onData = { d -> ch.onPacket(d) }
                ch.start()
            } catch (e: TurnException) {
                terminate(e.message ?: "连接失败", false)
            } catch (e: Exception) {
                terminate("连接失败", false)
            }
        }.also { it.isDaemon = true; it.name = "relay-connect" }.start()
    }

    // ------------------------------------------------------------ 数据
    fun sendMsg(text: String): Boolean = chan?.sendMessage(text) ?: false
    fun sendFile(fid: String, size: Long, src: RudpSource): Boolean {
        val ch = chan ?: run { try { src.close() } catch (_: Exception) {}; return false }
        val x = xidOf(fid); fids[x] = fid
        ch.sendFile(x, size, src); return true
    }
    fun recvFile(fid: String, size: Long, sink: RudpSink): Boolean {
        val ch = chan ?: run { try { sink.abort() } catch (_: Exception) {}; return false }
        val x = xidOf(fid); fids[x] = fid
        ch.recvFile(x, size, sink); return true
    }
    fun cancel(fid: String) { chan?.cancel(xidOf(fid)) }
    fun setRate(kbps: Int) { rateKbps = kbps.coerceIn(64, 20480); chan?.setRateKbps(rateKbps) }

    /** 手机网络切换：Allocation 的 5 元组已变，判定断开。 */
    fun onNetworkChanged() { terminate("网络已切换", true) }

    /** 本地关闭：通知对方、释放 Allocation（后台线程，不阻塞调用者）。 */
    fun close() { terminate("本地关闭", true, silent = true) }

    // ------------------------------------------------------------ 内部
    private fun fidOf(x: Int) = fids[x] ?: x.toString()

    private val listener = object : RudpListener {
        override fun onOpen(rttMs: Int) { if (!ended) ev.onOpen(rttMs) }
        override fun onFail(reason: String) { terminate(reason, false) }
        override fun onClosed(why: String) { terminate(why, true) }
        override fun onMessage(text: String) { if (!ended) ev.onMsg(text) }
        override fun onProgress(xid: Int, sending: Boolean, done: Long, total: Long, rateBps: Long, lossPct: Int, rttMs: Int) {
            if (!ended) ev.onProg(fidOf(xid), sending, done, total, rateBps, lossPct, rttMs)
        }
        override fun onFileDone(xid: Int, sending: Boolean, ok: Boolean, msg: String) { ev.onDone(fidOf(xid), sending, ok, msg) }
        override fun onStat(sentWire: Long, recvWire: Long) {
            val t = turn ?: return
            if (!ended) ev.onStat(t.wireSent(), t.wireRecv())
        }
    }

    /**
     * 终止：只触发一次。closedEvent=true 报 onClosed（通道曾经建立或已进入传输），false 报 onFail。
     * silent：本地主动 close 不再回调。
     */
    private fun terminate(why: String, closedEvent: Boolean, silent: Boolean = false) {
        val tc: TurnClient?; val sc: RealSched?; val ch: RudpChannel?
        synchronized(lock) {
            if (ended) return
            ended = true
            tc = turn; sc = sched; ch = chan
        }
        busy.set(false)
        // 先通知（文件失败事件由通道关闭时发出），再在后台释放 Allocation
        if (!silent) { if (closedEvent && connected) ev.onClosed(why) else if (closedEvent) ev.onFail(why) else ev.onFail(why) }
        Thread {
            try { ch?.close(why) } catch (_: Exception) {}
            try { Thread.sleep(50) } catch (_: Exception) {}
            try { tc?.releaseAndClose() } catch (_: Exception) {}
            try { sc?.shutdown() } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.name = "relay-release" }.start()
    }
}
