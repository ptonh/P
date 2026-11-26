package relaytest

import com.phoneshare.app.relay.*

const val MB = 1024L * 1024
private const val BW_10MBPS = 1_250_000L

private fun transfer(net: Net, size: Long, xid: Int = 1, limitMs: Long = 3_600_000, nospaceAfter: Long = Long.MAX_VALUE): Xfer {
    val x = Xfer(net, xid, size, net.a, net.b, net.recA, net.recB, nospaceAfter)
    x.start()
    net.s.runUntil(net.s.t + limitMs) { x.finished() }
    return x
}

fun rudpBasicTests() {
    case("RUDP 握手：双方 onOpen，RTT 与线路往返接近") {
        val n = Net(rttMs = 200)
        n.open()
        check(n.recA.openRtt in 150..260) { "A rtt=${n.recA.openRtt}" }
        check(n.recB.openRtt in 150..260) { "B rtt=${n.recB.openRtt}" }
    }
    case("RUDP 小文件 100KB 完整、SHA-256 一致") {
        val n = Net(rttMs = 100); n.open()
        val x = transfer(n, 100 * 1024)
        check(x.finished()) { "未完成 A=${n.recA.dones.map { it.msg }} B=${n.recB.dones.map { it.msg }}" }
        check(x.ok()) { "失败 A=${n.recA.dones.map { it.msg }} B=${n.recB.dones.map { it.msg }}" }
    }
    case("RUDP 空文件和 1 字节文件") {
        val n = Net(rttMs = 100); n.open()
        val e = transfer(n, 0, xid = 1)
        check(e.finished() && e.ok()) { "空文件失败 A=${n.recA.dones.map { it.msg }} B=${n.recB.dones.map { it.msg }}" }
        val o = transfer(n, 1, xid = 2)
        check(o.finished() && o.ok()) { "1 字节失败" }
    }
}

private fun speedLine(x: Xfer, size: Long) = "用时 ${x.elapsed() / 1000.0}s，平均 ${(size * 1000.0 / maxOf(1, x.elapsed()) / 1024).toInt()}KB/s"

fun rudpSpecTests() {
    case("【11】往返700ms/丢包2%/带宽10Mbps：传100MB，平均速度≥带宽80%，SHA-256一致") {
        val n = Net(rttMs = 700, bwBytesPerSec = BW_10MBPS, loss = 0.02, capKbps = 2048)
        n.open()
        val size = 100 * MB
        val x = transfer(n, size)
        check(x.finished()) { "未完成" }
        check(x.ok()) { "数据不一致或失败 A=${n.recA.dones.map { it.msg }} B=${n.recB.dones.map { it.msg }}" }
        val bps = size * 1000.0 / x.elapsed()
        println("    ${speedLine(x, size)}，线路上限 ${BW_10MBPS / 1024}KB/s，占 ${(100 * bps / BW_10MBPS).toInt()}%；发出 ${n.a.sentPackets} 包，重传 ${n.a.retxPackets}，峰值缓冲 ${n.a.peakBuffered / 1024}KB")
        check(bps >= 0.8 * BW_10MBPS) { "速度不足 80%：${(100 * bps / BW_10MBPS).toInt()}%" }
        check(n.a.peakBuffered <= 13 * MB) { "缓冲超限 ${n.a.peakBuffered}" }
    }
}

private fun diag(n: Net) = "A=${n.recA.dones.map { "${it.sending}:${it.ok}:${it.msg}" }} B=${n.recB.dones.map { "${it.sending}:${it.ok}:${it.msg}" }} closedA=${n.recA.closedWhy} closedB=${n.recB.closedWhy}"

private fun blackout(n: Net, from: Long, to: Long) { n.ab.outages.add(from..to); n.ba.outages.add(from..to) }

fun rudpMoreTests() {
    case("零丢包、往返50ms、10Mbps：传30MB，接近线路上限(≥85%)") {
        val n = Net(rttMs = 50, bwBytesPerSec = BW_10MBPS); n.open()
        val size = 30 * MB
        val x = transfer(n, size)
        check(x.ok()) { "失败 ${diag(n)}" }
        val bps = size * 1000.0 / x.elapsed()
        println("    ${speedLine(x, size)}，占线路 ${(100 * bps / BW_10MBPS).toInt()}%")
        check(bps >= 0.85 * BW_10MBPS) { "只到 ${(100 * bps / BW_10MBPS).toInt()}%" }
    }
    case("丢包10%：不崩溃，20MB 数据完整") {
        val n = Net(rttMs = 200, bwBytesPerSec = BW_10MBPS, loss = 0.10); n.open()
        val x = transfer(n, 20 * MB)
        check(x.ok()) { "失败 ${diag(n)}" }
        println("    ${speedLine(x, 20 * MB)}，重传 ${n.a.retxPackets}")
    }
    case("乱序10%(最多晚80ms) + 重复5%：10MB 数据完整") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS, reorder = 0.10, reorderExtraMs = 80, dup = 0.05); n.open()
        val x = transfer(n, 10 * MB)
        check(x.ok()) { "失败 ${diag(n)}" }
    }
    case("中途整条线断网20秒再恢复：15MB 数据完整，通道不断") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS); n.open()
        blackout(n, 8_000, 28_000)
        val x = transfer(n, 15 * MB)
        check(x.ok()) { "失败 ${diag(n)}" }
        check(n.recA.closedWhy == null && n.recB.closedWhy == null) { "不应断开 ${diag(n)}" }
        println("    ${speedLine(x, 15 * MB)}（含 20 秒断网）")
    }
    case("断网35秒：超过30秒判定断开，原因「30 秒没有数据」，文件失败") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS); n.open()
        blackout(n, 8_000, 60_000)
        val x = Xfer(n, 1, 15 * MB, n.a, n.b, n.recA, n.recB); x.start()
        n.s.runUntil(120_000) { n.recA.closedWhy != null && n.recB.closedWhy != null }
        check(n.recA.closedWhy == "30 秒没有数据") { "A=${n.recA.closedWhy}" }
        check(n.recB.closedWhy == "30 秒没有数据") { "B=${n.recB.closedWhy}" }
        check(n.recA.done(1, true)?.ok == false && n.recB.done(1, false)?.ok == false) { "文件应失败 ${diag(n)}" }
        check(x.sink.aborted) { "接收端 sink 应被 abort" }
        check(x.src.closed) { "发送端 source 应被关闭" }
    }
    case("加密：随机篡改 1% 的包（双向）→ 被丢弃并重传，10MB 数据完整") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS); n.open()
        n.ab.tamperProb = 0.01; n.ba.tamperProb = 0.01
        val x = transfer(n, 10 * MB)
        check(x.ok()) { "失败 ${diag(n)}" }
        check(n.ab.tamperedCount > 0) { "没有篡改发生" }
        check(n.b.badPackets > 0) { "B 应丢弃被篡改的包，badPackets=${n.b.badPackets}" }
        println("    篡改 ${n.ab.tamperedCount + n.ba.tamperedCount} 个包，B 丢弃 ${n.b.badPackets}，A 丢弃 ${n.a.badPackets}")
    }
    case("加密：单个包改一字节 → open 返回 null；错误密钥 → null") {
        val k = randomKey(); val c = PacketCrypto(k)
        val pkt = c.seal(RudpConst.T_DATA, 0, 5, "hello world".toByteArray())
        check(c.open(pkt)?.body?.let { String(it) } == "hello world") { "正常包应能解开" }
        for (i in pkt.indices) {
            val bad = pkt.copyOf(); bad[i] = (bad[i].toInt() xor 1).toByte()
            check(PacketCrypto(k).open(bad) == null) { "第 $i 字节被改后仍被接受" }
        }
        check(PacketCrypto(randomKey()).open(pkt) == null) { "错密钥应被拒" }
    }
    case("加密：重传包 nonce 不与任何已发包重复（全程抓包核对 方向+包序号）") {
        val n = Net(rttMs = 200, bwBytesPerSec = BW_10MBPS, loss = 0.05); 
        val seen = HashSet<Long>(); var dupes = 0
        val hook: (ByteArray) -> Unit = { p ->
            val dirv = p[1].toLong() and 0xff
            val pn = ((p[2].toLong() and 0xff) shl 24) or ((p[3].toLong() and 0xff) shl 16) or ((p[4].toLong() and 0xff) shl 8) or (p[5].toLong() and 0xff)
            if (!seen.add((dirv shl 32) or pn)) dupes++
        }
        n.ab.onWire = hook; n.ba.onWire = hook
        n.open()
        val x = transfer(n, 10 * MB)
        check(x.ok()) { "失败 ${diag(n)}" }
        check(n.a.retxPackets > 100) { "应有大量重传，实际 ${n.a.retxPackets}" }
        check(dupes == 0) { "出现 $dupes 个重复 nonce" }
        println("    抓到 ${seen.size} 个包，重传 ${n.a.retxPackets}，nonce 重复 0")
    }
    case("小消息：大文件传输中零丢包下 2 个往返内送达（双向）") {
        val n = Net(rttMs = 200, bwBytesPerSec = BW_10MBPS); n.open()
        val x = Xfer(n, 1, 20 * MB, n.a, n.b, n.recA, n.recB); x.start()
        n.s.runFor(8_000)
        val t0 = n.s.t
        n.a.sendMessage("ping-from-a"); n.b.sendMessage("ping-from-b")
        n.s.runFor(1000)
        val ma = n.recB.messages.firstOrNull { it.second == "ping-from-a" }
        val mb = n.recA.messages.firstOrNull { it.second == "ping-from-b" }
        check(ma != null && mb != null) { "没收到 B=${n.recB.messages} A=${n.recA.messages}" }
        println("    A→B ${ma!!.first - t0}ms，B→A ${mb!!.first - t0}ms（2 个往返 = 400ms）")
        check(ma.first - t0 <= 400 && mb!!.first - t0 <= 400) { "超过 2 个往返" }
        n.s.runUntil(n.s.t + 120_000) { x.finished() }
        check(x.ok()) { "文件应仍然完成 ${diag(n)}" }
    }
    case("小消息：2% 丢包 + 大文件，50 条按顺序、各一次送达；16KB 分片；超长被拒") {
        val n = Net(rttMs = 200, bwBytesPerSec = BW_10MBPS, loss = 0.02); n.open()
        val x = Xfer(n, 1, 10 * MB, n.a, n.b, n.recA, n.recB); x.start()
        for (i in 0 until 50) { n.a.sendMessage("m$i"); n.s.runFor(150) }
        val big = "汉".repeat(5461)               // 16383 字节
        check(n.a.sendMessage(big)) { "16KB 内应接受" }
        check(!n.a.sendMessage("x".repeat(16 * 1024 + 1))) { "超过 16KB 应被拒" }
        n.s.runUntil(n.s.t + 120_000) { x.finished() && n.recB.messages.size >= 51 }
        val got = n.recB.messages.map { it.second }
        check(got.size == 51) { "收到 ${got.size} 条" }
        for (i in 0 until 50) check(got[i] == "m$i") { "第 $i 条顺序不对：${got[i]}" }
        check(got[50] == big) { "16KB 内容不一致" }
        check(x.ok()) { "文件失败" }
    }
    case("取消：发送方取消 → 双方清理；随后同一通道还能传下一个文件") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS); n.open()
        val x = Xfer(n, 1, 20 * MB, n.a, n.b, n.recA, n.recB); x.start()
        n.s.runFor(4_000)
        n.a.cancel(1)
        n.s.runFor(2_000)
        check(n.recA.done(1, true)?.msg == "已取消") { "A=${n.recA.done(1, true)?.msg}" }
        check(n.recB.done(1, false)?.msg == "对方取消") { "B=${n.recB.done(1, false)?.msg}" }
        check(x.sink.aborted && x.src.closed) { "sink.aborted=${x.sink.aborted} src.closed=${x.src.closed}" }
        val y = transfer(n, 3 * MB, xid = 2)
        check(y.ok()) { "取消后下一个文件失败 ${diag(n)}" }
    }
    case("取消：接收方取消 → 发送方收到「对方取消」") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS); n.open()
        val x = Xfer(n, 1, 20 * MB, n.a, n.b, n.recA, n.recB); x.start()
        n.s.runFor(4_000)
        n.b.cancel(1)
        n.s.runFor(2_000)
        check(n.recB.done(1, false)?.msg == "已取消") { "B=${n.recB.done(1, false)?.msg}" }
        check(n.recA.done(1, true)?.msg == "对方取消") { "A=${n.recA.done(1, true)?.msg}" }
        check(x.sink.aborted && x.src.closed) { "未清理" }
    }
    case("接收方存储满：receiver「存储空间不足」，sender「对方存储空间不足」，sink 被放弃") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS); n.open()
        val x = Xfer(n, 1, 8 * MB, n.a, n.b, n.recA, n.recB, nospaceAfter = 1 * MB); x.start()
        n.s.runUntil(n.s.t + 60_000) { x.finished() }
        check(n.recB.done(1, false)?.msg == "存储空间不足") { "B=${n.recB.done(1, false)?.msg}" }
        check(n.recA.done(1, true)?.msg == "对方存储空间不足") { "A=${n.recA.done(1, true)?.msg}" }
        check(x.sink.aborted && x.src.closed) { "未清理" }
    }
    case("接收方收完校验失败（大小不符）→ 双方都报失败") {
        val n = Net(rttMs = 100); n.open()
        val src = GenSource(300_000)
        val sink = HashSink(expect = 299_999)       // 登记的大小与实际不符
        n.b.recvFile(7, 300_000, sink); n.a.sendFile(7, 300_000, src)
        n.s.runUntil(n.s.t + 30_000) { n.recA.done(7, true) != null && n.recB.done(7, false) != null }
        check(n.recB.done(7, false)?.ok == false && n.recA.done(7, true)?.ok == false) { diag(n) }
        check(n.recA.done(7, true)?.msg == "大小不符") { "A=${n.recA.done(7, true)?.msg}" }
    }
    case("对方消失：整线丢弃后 30 秒判定断开「30 秒没有数据」") {
        val n = Net(rttMs = 100, bwBytesPerSec = BW_10MBPS); n.open()
        val x = Xfer(n, 1, 20 * MB, n.a, n.b, n.recA, n.recB); x.start()
        n.s.runFor(3_000)
        val t0 = n.s.t
        n.ab.blackholeFrom = t0; n.ba.blackholeFrom = t0
        n.s.runUntil(t0 + 60_000) { n.recA.closedWhy != null }
        val dt = n.s.t - t0
        check(n.recA.closedWhy == "30 秒没有数据") { "A=${n.recA.closedWhy}" }
        check(dt in 29_000..33_000) { "应在约 30 秒后断开，实际 ${dt}ms" }
        check(n.recA.done(1, true)?.ok == false && n.recA.done(1, true)?.msg == "30 秒没有数据") { diag(n) }
    }
    case("握手：对方不回 → 30 秒后「对方没有回握手」；密钥不一致同样失败") {
        val n = Net(rttMs = 100)
        n.ab.blackholeFrom = 0; n.ba.blackholeFrom = 0
        n.a.start(); n.s.runUntil(60_000) { n.recA.failReason != null }
        check(n.recA.failReason == "对方没有回握手") { "reason=${n.recA.failReason}" }
        check(n.s.t in 29_500..31_500) { "应约 30 秒，实际 ${n.s.t}" }
        val m = Net(rttMs = 100, keyB = randomKey())
        m.a.start(); m.b.start(); m.s.runUntil(60_000) { m.recA.failReason != null && m.recB.failReason != null }
        check(m.recA.failReason == "对方没有回握手" && !m.recA.opened) { "错密钥不该连通" }
        check(m.b.badPackets > 0) { "应统计到校验失败的包" }
    }
    case("保活：空闲 100 秒通道不断，只发少量保活包") {
        val n = Net(rttMs = 100); n.open()
        val before = n.a.wireSent
        n.s.runFor(100_000)
        check(n.recA.closedWhy == null && n.recB.closedWhy == null) { "不该断 ${diag(n)}" }
        val used = n.a.wireSent - before
        check(used in 1..3000) { "空闲 100 秒发了 ${used} 字节" }
        check(n.recA.stats >= 9) { "relayStat 应约每 10 秒一次，实际 ${n.recA.stats}" }
    }
    case("速率上限：setRate(512) 从一开始生效，总发出字节(含重传/确认/头) ≤ 512KB/s 的 110%") {
        val n = Net(rttMs = 100, bwBytesPerSec = 12_500_000, capKbps = 512); n.open()
        val x = transfer(n, 8 * MB)
        check(x.ok()) { diag(n) }
        val bps = n.a.wireSent * 1000.0 / x.elapsed()
        println("    发出 ${n.a.wireSent / 1024}KB / ${x.elapsed() / 1000.0}s = ${(bps / 1024).toInt()}KB/s")
        check(bps <= 1.10 * 512 * 1024) { "超限 ${(bps / 1024).toInt()}KB/s" }
        check(bps >= 0.7 * 512 * 1024) { "远低于上限 ${(bps / 1024).toInt()}KB/s" }
    }
    case("速率上限：传输中途 setRate(512) 立即生效（之后 10 秒窗口 ≤ 110%）") {
        val n = Net(rttMs = 100, bwBytesPerSec = 12_500_000, capKbps = 20480); n.open()
        val x = Xfer(n, 1, 60 * MB, n.a, n.b, n.recA, n.recB); x.start()
        n.s.runFor(5_000)
        n.a.setRateKbps(512)
        n.s.runFor(500)
        val w0 = n.a.wireSent; val t0 = n.s.t
        n.s.runFor(10_000)
        val bps = (n.a.wireSent - w0) * 1000.0 / (n.s.t - t0)
        println("    调低后 10 秒内 ${(bps / 1024).toInt()}KB/s")
        check(bps <= 1.10 * 512 * 1024) { "超限 ${(bps / 1024).toInt()}KB/s" }
    }
    case("双向同时传输：A→B 8MB 与 B→A 6MB 并发，各自完整") {
        val n = Net(rttMs = 150, bwBytesPerSec = BW_10MBPS, loss = 0.01); n.open()
        val x = Xfer(n, 1, 8 * MB, n.a, n.b, n.recA, n.recB)
        val y = Xfer(n, 2, 6 * MB, n.b, n.a, n.recB, n.recA)
        x.start(); y.start()
        n.s.runUntil(n.s.t + 300_000) { x.finished() && y.finished() }
        check(x.ok() && y.ok()) { "x=${x.finished()} y=${y.finished()} ${diag(n)}" }
    }
    case("窗口：700ms 线路上发送缓冲峰值不超过 8MB，接收乱序缓冲不超过 4MB") {
        val n = Net(rttMs = 700, bwBytesPerSec = 12_500_000, loss = 0.02, capKbps = 20480); n.open()
        val x = transfer(n, 40 * MB)
        check(x.ok()) { diag(n) }
        check(n.a.peakBuffered <= 8 * MB + 64 * 1024) { "发送侧峰值 ${n.a.peakBuffered}" }
        check(n.b.peakBuffered <= 4 * MB + 64 * 1024) { "接收侧峰值 ${n.b.peakBuffered}" }
        println("    ${speedLine(x, 40 * MB)}；峰值 发送 ${n.a.peakBuffered / 1024}KB 接收 ${n.b.peakBuffered / 1024}KB")
    }
}

/** 1GB 流式传输（内存里按需生成/校验，不整块放内存）。在 -Xmx64m 的子进程里跑。 */
fun bigTransferInChild() {
    val n = Net(rttMs = 50, bwBytesPerSec = 25_000_000, capKbps = 20480); n.open()
    val size = 1024 * MB
    val x = transfer(n, size, limitMs = 3_600_000)
    check(x.finished()) { "未完成 ${diag(n)}" }
    check(x.ok()) { "SHA-256 不一致或失败 ${diag(n)}" }
    val bps = size * 1000.0 / x.elapsed()
    println("    1GB：虚拟用时 ${x.elapsed() / 1000.0}s，${(bps / MB).toInt()}MB/s；通道内缓冲峰值 发送 ${n.a.peakBuffered / 1024}KB 接收 ${n.b.peakBuffered / 1024}KB")
    val rt = Runtime.getRuntime()
    println("    JVM 最大堆 ${rt.maxMemory() / MB}MB，结束时已用 ${(rt.totalMemory() - rt.freeMemory()) / MB}MB")
}

fun bigTransferTest() {
    case("1GB 流式传输：子进程 -Xmx64m 内完成且 SHA-256 一致（内存峰值 ≤64MB）") {
        val java = System.getProperty("java.home") + "/bin/java"
        val pb = ProcessBuilder(java, "-Xmx64m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), "relaytest.RelayMainKt", "--big")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val code = p.waitFor()
        for (l in out.lines()) if (l.startsWith("    ")) println(l)
        check(code == 0 && out.contains("BIG-OK")) { "子进程失败 exit=$code：${out.lines().filter { !it.startsWith("Picked") }.take(8)}" }
    }
}
