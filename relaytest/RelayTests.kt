package relaytest

import com.phoneshare.app.relay.*
import java.util.concurrent.CopyOnWriteArrayList

class EvRec : RelayEvents {
    @Volatile var addr = ""; @Volatile var key: String? = null
    @Volatile var openRtt = -1
    @Volatile var fail: String? = null
    @Volatile var closed: String? = null
    val msgs = CopyOnWriteArrayList<String>()
    class D(val fid: String, val sending: Boolean, val ok: Boolean, val msg: String)
    val dones = CopyOnWriteArrayList<D>()
    @Volatile var prog = 0
    @Volatile var stats = 0
    @Volatile var statSent = 0L
    override fun onReady(addr: String, keyB64: String) { this.addr = addr; this.key = keyB64 }
    override fun onOpen(rttMs: Int) { openRtt = rttMs }
    override fun onFail(reason: String) { fail = reason }
    override fun onClosed(why: String) { closed = why }
    override fun onMsg(text: String) { msgs.add(text) }
    override fun onProg(fid: String, sending: Boolean, done: Long, total: Long, rateBps: Long, lossPct: Int, rttMs: Int) { prog++ }
    override fun onDone(fid: String, sending: Boolean, ok: Boolean, msg: String) { dones.add(D(fid, sending, ok, msg)) }
    override fun onStat(sent: Long, recv: Long) { stats++; statSent = sent }
    fun done(fid: String, sending: Boolean) = dones.firstOrNull { it.fid == fid && it.sending == sending }
}

private fun cfg(hs: Long = 3000) = RelayCfg(
    RudpConfig(userCapKbps = 4096, handshakeTimeoutMs = hs, statMs = 300, progressMs = 100),
    TurnConfig(100, 400, 3000, 1500, 60_000, 60_000, 60_000, 100, 800)
)

private fun ice(s: FakeTurn, user: String = "u", pass: String = "p") =
    listOf(IceServer(listOf("turns:x:443", "turn:127.0.0.1:${s.addr.port}?transport=udp", "turn:127.0.0.1:${s.addr.port}?transport=tcp"), user, pass))

/** 建一对已连通的会话。 */
private class Pair2(val srv: FakeTurn, hs: Long = 3000, keyOverride: String? = null) {
    val ea = EvRec(); val eb = EvRec()
    val a = RelaySession("s", ea, cfg(hs)); val b = RelaySession("r", eb, cfg(hs))
    fun up() {
        a.open(ice(srv)); b.open(ice(srv))
        check(waitFor(3000) { ea.key != null && eb.key != null }) { "relayReady 超时 a.fail=${ea.fail} b.fail=${eb.fail}" }
        check(ea.key!!.isNotEmpty() && eb.key == "") { "key 规则：s 非空、r 为空串" }
    }
    fun connect(keyForB: String? = null) {
        a.connect(eb.addr, ""); b.connect(ea.addr, keyForB ?: ea.key!!)
    }
    fun waitOpen() = check(waitFor(5000) { ea.openRtt >= 0 && eb.openRtt >= 0 }) { "relayOpen 超时 a.fail=${ea.fail} b.fail=${eb.fail}" }
    fun closeAll() { a.close(); b.close() }
}

fun relayTests() {
    case("RelaySession 端到端（真实 socket + 假 TURN）：消息双向、3MB+1MB 双向并发文件、统计、关闭释放") {
        val srv = FakeTurn()
        val p = Pair2(srv)
        try {
            p.up(); p.connect(); p.waitOpen()
            check(p.a.sendMsg("hi-b") && p.b.sendMsg("hi-a")) { "sendMsg 失败" }
            check(waitFor(3000) { p.eb.msgs.contains("hi-b") && p.ea.msgs.contains("hi-a") }) { "消息未到" }
            val s1 = GenSource(3 * MB, 11); val k1 = HashSink(3 * MB)
            val s2 = GenSource(1 * MB, 12); val k2 = HashSink(1 * MB)
            p.b.recvFile("fileA", 3 * MB, k1); p.a.sendFile("fileA", 3 * MB, s1)
            p.a.recvFile("fileB", 1 * MB, k2); p.b.sendFile("fileB", 1 * MB, s2)
            check(waitFor(30_000) { p.ea.done("fileA", true) != null && p.eb.done("fileA", false) != null && p.ea.done("fileB", false) != null && p.eb.done("fileB", true) != null }) {
                "文件未完成 A=${p.ea.dones.map { it.fid + it.sending + it.ok + it.msg }} B=${p.eb.dones.map { it.fid + it.sending + it.ok + it.msg }}"
            }
            check(p.ea.done("fileA", true)!!.ok && p.eb.done("fileA", false)!!.ok && p.ea.done("fileB", false)!!.ok && p.eb.done("fileB", true)!!.ok) { "有失败" }
            check(p.eb.done("fileA", false)!!.msg == "f.bin") { "relayDone.msg 应为最终文件名，实际 ${p.eb.done("fileA", false)!!.msg}" }
            check(hexOf(s1.digest.digest()) == hexOf(k1.digest.digest())) { "fileA SHA-256 不一致" }
            check(hexOf(s2.digest.digest()) == hexOf(k2.digest.digest())) { "fileB SHA-256 不一致" }
            check(p.ea.prog > 0 && p.eb.prog > 0) { "应有进度事件" }
            val st0 = p.ea.stats
            check(waitFor(3000) { p.ea.stats >= st0 + 2 }) { "应持续有 relayStat" }
            check(p.ea.statSent > 3 * MB) { "统计口径应含全部发出字节，实际 ${p.ea.statSent}" }
            check(p.a.isBusy() && p.b.isBusy()) { "传输后通道仍应占用" }
            p.closeAll()
            check(waitFor(3000) { srv.liveAllocs == 0 }) { "Allocation 未释放 live=${srv.liveAllocs}" }
            check(srv.lifetimes.count { it == 0 } == 2) { "应看到 2 次 lifetime=0：${srv.lifetimes}" }
            check(!p.a.isBusy() && !p.b.isBusy()) { "关闭后应不忙" }
        } finally { p.closeAll(); srv.stop() }
    }
    case("RelaySession：凭证错 → onFail「凭证被拒」，且不占用") {
        val srv = FakeTurn(); val e = EvRec(); val s = RelaySession("s", e, cfg())
        try {
            s.open(ice(srv, pass = "bad"))
            check(waitFor(3000) { e.fail != null }) { "应失败" }
            check(e.fail == "凭证被拒") { "reason=${e.fail}" }
            check(waitFor(500) { !s.isBusy() }) { "应不忙" }
        } finally { srv.stop() }
    }
    case("RelaySession：iceServers 里没有可用 turn: UDP 地址 → 「没有可用的中继地址」") {
        val e = EvRec(); val s = RelaySession("s", e, cfg())
        s.open(listOf(IceServer(listOf("turns:a:443", "turn:b:3478?transport=tcp"), "u", "p")))
        check(waitFor(2000) { e.fail != null }) { "应失败" }
        check(e.fail == "没有可用的中继地址") { "reason=${e.fail}" }
    }
    case("RelaySession：对方一直不 connect → 握手超时「对方没有回握手」，Allocation 释放") {
        val srv = FakeTurn(); val p = Pair2(srv, hs = 1500)
        try {
            p.up()
            p.a.connect(p.eb.addr, "")      // 只有 A 连，B 不连
            check(waitFor(6000) { p.ea.fail != null }) { "A 应握手超时" }
            check(p.ea.fail == "对方没有回握手") { "reason=${p.ea.fail}" }
            p.b.close()
            check(waitFor(3000) { srv.liveAllocs == 0 }) { "Allocation 应释放 live=${srv.liveAllocs}" }
        } finally { p.closeAll(); srv.stop() }
    }
    case("RelaySession：密钥不一致 → 双方握手失败") {
        val srv = FakeTurn(); val p = Pair2(srv, hs = 1500)
        try {
            p.up()
            val wrong = java.util.Base64.getEncoder().encodeToString(randomKey())
            p.connect(keyForB = wrong)
            check(waitFor(6000) { p.ea.fail != null && p.eb.fail != null }) { "应失败 a=${p.ea.fail} b=${p.eb.fail}" }
            check(p.ea.openRtt < 0 && p.eb.openRtt < 0) { "不该连通" }
        } finally { p.closeAll(); srv.stop() }
    }
    case("RelaySession：手机网络切换 → relayClosed「网络已切换」，Allocation 释放") {
        val srv = FakeTurn(); val p = Pair2(srv)
        try {
            p.up(); p.connect(); p.waitOpen()
            p.a.onNetworkChanged()
            check(waitFor(2000) { p.ea.closed == "网络已切换" }) { "closed=${p.ea.closed} fail=${p.ea.fail}" }
            check(waitFor(3000) { srv.lifetimes.contains(0) }) { "应释放 Allocation" }
            check(waitFor(500) { !p.a.isBusy() }) { "应不忙" }
        } finally { p.closeAll(); srv.stop() }
    }
    case("RelaySession：一端 close → 对端收到「对方中断」，传输中的文件失败并清理") {
        val srv = FakeTurn(); val p = Pair2(srv)
        try {
            p.up(); p.connect(); p.waitOpen()
            val src = GenSource(200 * MB, 5); val sink = HashSink(200 * MB)
            p.b.recvFile("big", 200 * MB, sink); p.a.sendFile("big", 200 * MB, src)
            Thread.sleep(600)
            p.a.close()
            check(waitFor(4000) { p.eb.closed == "对方中断" }) { "B.closed=${p.eb.closed} fail=${p.eb.fail}" }
            check(waitFor(2000) { p.eb.done("big", false)?.ok == false }) { "B 文件应失败" }
            check(sink.aborted) { "sink 应被放弃" }
            check(waitFor(3000) { srv.liveAllocs == 0 }) { "两端 Allocation 都应释放 live=${srv.liveAllocs}" }
        } finally { p.closeAll(); srv.stop() }
    }
    case("RelaySession：传输中取消，随后同一通道再传一个文件成功") {
        val srv = FakeTurn(); val p = Pair2(srv)
        try {
            p.up(); p.connect(); p.waitOpen()
            val src = GenSource(100 * MB, 5); val sink = HashSink(100 * MB)
            p.b.recvFile("c1", 100 * MB, sink); p.a.sendFile("c1", 100 * MB, src)
            Thread.sleep(500)
            p.a.cancel("c1")
            check(waitFor(3000) { p.ea.done("c1", true) != null && p.eb.done("c1", false) != null }) { "取消未生效" }
            check(p.ea.done("c1", true)!!.msg == "已取消" && p.eb.done("c1", false)!!.msg == "对方取消") { "A=${p.ea.done("c1", true)!!.msg} B=${p.eb.done("c1", false)!!.msg}" }
            val s2 = GenSource(1 * MB, 6); val k2 = HashSink(1 * MB)
            p.b.recvFile("c2", 1 * MB, k2); p.a.sendFile("c2", 1 * MB, s2)
            check(waitFor(15_000) { p.eb.done("c2", false) != null && p.ea.done("c2", true) != null }) { "第二个文件未完成" }
            check(p.eb.done("c2", false)!!.ok && hexOf(s2.digest.digest()) == hexOf(k2.digest.digest())) { "第二个文件数据不一致" }
        } finally { p.closeAll(); srv.stop() }
    }
}
