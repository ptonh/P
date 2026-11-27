package relaytest

import com.phoneshare.app.relay.Stun
import com.phoneshare.app.relay.TurnClient
import com.phoneshare.app.relay.TurnConfig
import com.phoneshare.app.relay.TurnException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

private fun fast(extra: TurnConfig.() -> Unit = {}) = TurnConfig(
    rtoStartMs = 100, rtoMaxMs = 400, allocateTotalMs = 3000, requestTotalMs = 1500,
    allocRefreshMs = 60_000, permRefreshMs = 60_000, bindRefreshMs = 60_000, refreshRetryGapMs = 100, releaseWaitMs = 800
).also(extra)

private fun client(s: FakeTurn, user: String = "u", pass: String = "p", cfg: TurnConfig = fast()) =
    TurnClient(s.addr, user, pass, cfg)

private fun expectErr(want: String, f: () -> Unit) {
    try { f() } catch (e: TurnException) {
        check(e.message!!.contains(want)) { "原因应含「$want」，实际「${e.message}」" }
        return
    }
    throw CheckFailed("应抛 TurnException(含「$want」)，但成功了")
}

fun stunTests() {
    case("STUN RFC5769 向量：MESSAGE-INTEGRITY 通过 / 错密钥拒绝") {
        val raw = ("000100582112a442b7e7a701bc34d686fa87dfae802200105354554e20746573742063" +
            "6c69656e74002400046e0001ff80290008932ff9b151263b36000600096576" +
            "746a3a68367659202020000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a280280004e57a3bcf")
        val b = ByteArray(raw.length / 2) { raw.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val m = Stun.parse(b) ?: throw CheckFailed("解析失败")
        check(Stun.verifyIntegrity(m, "VOkJxbRl1RmTxUk/WvJxBt".toByteArray())) { "官方向量校验应通过" }
        check(!Stun.verifyIntegrity(m, "VOkJxbRl1RmTxUk/WvJxBu".toByteArray())) { "错密钥应被拒" }
        val t = b.copyOf(); t[30] = (t[30].toInt() xor 1).toByte()
        val m2 = Stun.parse(t)!!
        check(!Stun.verifyIntegrity(m2, "VOkJxbRl1RmTxUk/WvJxBt".toByteArray())) { "改一个字节应被拒" }
    }
    case("STUN 构造→解析→校验 往返（长期凭证）；改一字节被拒") {
        val key = Stun.longTermKey("user", "realm", "pass")
        val tx = Stun.newTxId()
        val pkt = Stun.build(Stun.ALLOCATE_REQUEST, tx, listOf(Stun.requestedTransportUdp(), Stun.str(Stun.ATTR_USERNAME, "user")), key)
        val m = Stun.parse(pkt)!!
        check(Stun.verifyIntegrity(m, key)) { "往返校验应通过" }
        val bad = pkt.copyOf(); bad[24] = (bad[24].toInt() xor 0x10).toByte()
        check(!Stun.verifyIntegrity(Stun.parse(bad)!!, key)) { "篡改应被拒" }
        check(Stun.parse(pkt.copyOf(pkt.size - 1)) == null) { "截断报文应解析失败" }
    }
    case("STUN XOR 地址往返") {
        val a = Stun.xorAddr(Stun.ATTR_XOR_PEER_ADDRESS, "203.0.113.7", 54321)
        val p = Stun.parseXorAddr(a.value)
        check(p == ("203.0.113.7" to 54321)) { "got $p" }
    }
    case("pickAllUdpTurnUrls：保持顺序、去重、排除 turns: 和 tcp") {
        val l = TurnClient.pickAllUdpTurnUrls(listOf("turns:a:443", "turn:1.2.3.4:3478?transport=udp", "turn:5.6.7.8:3478?transport=udp", "turn:1.2.3.4:3478", "turn:9.9.9.9:3478?transport=tcp"))
        check(l.size == 2 && l[0].host == "1.2.3.4" && l[1].host == "5.6.7.8") { "实际 ${l.map { it.host }}" }
    }
    case("pickUdpTurnUrl：排除 turns: 和 transport=tcp") {
        val p = TurnClient.pickUdpTurnUrl(listOf("turns:a:443?transport=tcp", "turn:b:3478?transport=tcp", "turn:c.example.com:3479?transport=udp"))
        check(p != null && p.host == "c.example.com" && p.port == 3479) { "got ${p?.host}:${p?.port}" }
        check(TurnClient.pickUdpTurnUrl(listOf("turns:x:443", "turn:y:3478?transport=tcp")) == null) { "应为空" }
        val q = TurnClient.pickUdpTurnUrl(listOf("turn:z.example.com"))
        check(q != null && q.port == 3478) { "默认端口 3478" }
    }
}

fun turnTests() {
    case("TURN Allocate：401 重试后成功，返回中继地址") {
        val s = FakeTurn(); val c = client(s)
        try {
            val a = c.allocate()
            check(a.startsWith("127.0.0.1:")) { "addr=$a" }
            check(s.allocCount.get() == 1) { "alloc=${s.allocCount.get()}" }
            check(s.requestCount.get() >= 2) { "应至少 2 个请求（无凭证 + 带凭证）" }
        } finally { c.close(); s.stop() }
    }
    case("TURN 凭证错 → 「凭证被拒」") {
        val s = FakeTurn(); val c = client(s, pass = "bad")
        try { expectErr("凭证被拒") { c.allocate() } } finally { c.close(); s.stop() }
    }
    case("TURN 486 → 「被中继限额」") {
        val s = FakeTurn(); s.allocateError = 486; val c = client(s)
        try { expectErr("被中继限额") { c.allocate() } } finally { c.close(); s.stop() }
    }
    case("TURN 403 → 「凭证被拒」；508 → 「中继容量不足」；441 → 「分配被拒」") {
        for ((code, want) in listOf(403 to "凭证被拒", 508 to "中继容量不足", 441 to "分配被拒")) {
            val s = FakeTurn(); s.allocateError = code; val c = client(s)
            try { expectErr(want) { c.allocate() } } finally { c.close(); s.stop() }
        }
    }
    case("TURN 服务器不响应 → 「中继服务器没有响应」，且 RTO 重传沿用同一事务 ID") {
        val s = FakeTurn(); s.mute = true; val c = client(s, cfg = fast { })
        try {
            expectErr("中继服务器没有响应") { c.allocate() }
            val maxSeen = s.seenTx.values.maxOrNull() ?: 0
            check(maxSeen >= 3) { "同一事务 ID 应被重传多次，最多只见 $maxSeen 次" }
        } finally { c.close(); s.stop() }
    }
    case("TURN 响应丢 2 个：靠重传仍成功，且只分配一次") {
        val s = FakeTurn(); s.dropResponses = 2; val c = client(s)
        try { c.allocate(); check(s.allocCount.get() == 1) { "alloc=${s.allocCount.get()}" } } finally { c.close(); s.stop() }
    }
    case("TURN 成功响应的 MESSAGE-INTEGRITY 错误 → 被丢弃（按超时处理）") {
        val s = FakeTurn(); val c = client(s)
        try {
            // 先正常拿到 401 流程：corrupt 开关对成功响应生效，401 错误响应不带 MI 仍被接受
            s.corruptSuccessMi = true
            expectErr("中继服务器没有响应") { c.allocate() }
        } finally { c.close(); s.stop() }
    }
    case("TURN 438 Stale Nonce：用新 nonce 重试成功") {
        val s = FakeTurn(); val c = client(s)
        try {
            c.allocate()
            s.forceStaleOnce = true
            c.permitAndBind("127.0.0.1", 40000)
            check(s.permCount.get() == 1 && s.bindCount.get() == 1) { "perm=${s.permCount.get()} bind=${s.bindCount.get()}" }
        } finally { c.close(); s.stop() }
    }
    case("TURN 续期：Allocation / Permission / ChannelBind 按各自周期刷新") {
        val s = FakeTurn()
        val c = client(s, cfg = fast { }.let { TurnConfig(100, 400, 3000, 1500, 300, 250, 500, 100, 800) })
        try {
            c.allocate(); c.permitAndBind("127.0.0.1", 40000)
            Thread.sleep(1700)
            check(s.refreshCount.get() >= 4) { "Refresh 次数 ${s.refreshCount.get()}" }
            check(s.permCount.get() >= 4) { "Permission 次数 ${s.permCount.get()}" }
            check(s.bindCount.get() >= 2) { "ChannelBind 次数 ${s.bindCount.get()}" }
            check(s.lifetimes.none { it == 0 }) { "不应出现 lifetime=0" }
        } finally { c.close(); s.stop() }
    }
    case("TURN 续期中途换 nonce（438）：自动重试，不判失败") {
        val s = FakeTurn(); val fatal = AtomicReference<String?>(null)
        val c = client(s, cfg = TurnConfig(100, 400, 3000, 1500, 300, 60_000, 60_000, 100, 800))
        c.onFatal = { fatal.set(it) }
        try {
            c.allocate(); s.forceStaleOnce = true
            Thread.sleep(900)
            check(fatal.get() == null) { "不应 fatal：${fatal.get()}" }
            check(s.refreshCount.get() >= 2) { "refresh=${s.refreshCount.get()}" }
        } finally { c.close(); s.stop() }
    }
    case("TURN 续期连续 3 次失败 → onFatal") {
        val s = FakeTurn(); val fatal = AtomicReference<String?>(null)
        val c = client(s, cfg = TurnConfig(50, 100, 3000, 250, 300, 60_000, 60_000, 50, 300))
        c.onFatal = { fatal.set(it) }
        try {
            c.allocate()
            s.mute = true
            check(waitFor(4000) { fatal.get() != null }) { "应在 4 秒内 fatal" }
            check(fatal.get()!!.contains("续期失败")) { "原因=${fatal.get()}" }
        } finally { c.close(); s.stop() }
    }
    case("TURN releaseAndClose：同步发出 Refresh(lifetime=0)，Allocation 被释放") {
        val s = FakeTurn(); val c = client(s)
        try {
            c.allocate()
            check(s.liveAllocs == 1) { "live=${s.liveAllocs}" }
            c.releaseAndClose()
            check(s.lifetimes.contains(0)) { "服务器没看到 lifetime=0：${s.lifetimes}" }
            check(s.liveAllocs == 0) { "live=${s.liveAllocs}" }
        } finally { c.close(); s.stop() }
    }
    case("TURN 两端经假服务器互传 ChannelData（双向），计数含 4 字节头") {
        val s = FakeTurn(); val a = client(s); val b = client(s)
        try {
            val ra = a.allocate(); val rb = b.allocate()
            val gotA = CopyOnWriteArrayList<String>(); val gotB = CopyOnWriteArrayList<String>()
            a.onData = { gotA.add(String(it)) }; b.onData = { gotB.add(String(it)) }
            val (ia, pa) = ra.split(":").let { it[0] to it[1].toInt() }
            val (ib, pb) = rb.split(":").let { it[0] to it[1].toInt() }
            a.permitAndBind(ib, pb); b.permitAndBind(ia, pa)
            val before = a.wireSent()
            a.send("hello-from-a".toByteArray()); b.send("hello-from-b".toByteArray())
            check(waitFor(2000) { gotB.contains("hello-from-a") && gotA.contains("hello-from-b") }) { "A收=$gotA B收=$gotB" }
            check(a.wireSent() - before == 4L + "hello-from-a".length) { "发送字节 ${a.wireSent() - before}" }
        } finally { a.close(); b.close(); s.stop() }
    }
    case("TURN 未授权的来源不会被转发（权限检查）") {
        val s = FakeTurn(); val a = client(s); val b = client(s)
        try {
            val ra = a.allocate(); val rb = b.allocate()
            val got = CopyOnWriteArrayList<String>()
            a.onData = { got.add(String(it)) }
            val (ib, pb) = rb.split(":").let { it[0] to it[1].toInt() }
            // 只有 B 授权 A，A 没有为 B 授权/绑定通道：B→A 的包 A 侧不应收到
            b.permitAndBind(ra.split(":")[0], ra.split(":")[1].toInt())
            b.send("x".toByteArray())
            Thread.sleep(400)
            check(got.isEmpty()) { "A 不该收到：$got" }
        } finally { a.close(); b.close(); s.stop() }
    }
}
