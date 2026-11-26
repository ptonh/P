package relaytest

import com.phoneshare.app.relay.*
import java.util.PriorityQueue
import java.util.Random

/** 虚拟时钟调度器：不真等，事件按时间顺序执行。 */
class VSched : Sched {
    var t = 0L
    private var seq = 0L
    private class Ev(val time: Long, val seq: Long, val r: Runnable) : Cancellable {
        @Volatile var cancelled = false
        override fun cancel() { cancelled = true }
    }
    private val pq = PriorityQueue<Ev>(compareBy<Ev>({ it.time }, { it.seq }))
    override fun now() = t
    override fun post(r: Runnable) { after(0, r) }
    override fun after(delayMs: Long, r: Runnable): Cancellable {
        val e = Ev(t + maxOf(0L, delayMs), seq++, r)
        pq.add(e); return e
    }
    /** 跑到 done() 为真或虚拟时间超过 limitMs。返回 done()。 */
    fun runUntil(limitMs: Long, done: () -> Boolean): Boolean {
        while (true) {
            if (done()) return true
            val e = pq.peek() ?: return done()
            if (e.time > limitMs) { t = limitMs; return done() }
            pq.poll()
            if (e.cancelled) continue
            t = e.time
            e.r.run()
        }
    }
    fun runFor(ms: Long) { val end = t + ms; runUntil(end) { false }; t = end }
}

/** 单向线路：延迟、带宽 + 有限队列（尾丢）、随机丢包、乱序、重复、整线中断、篡改。 */
class SimLink(
    private val s: VSched, private val oneWayMs: Long, private val bwBytesPerSec: Long = 0, private val queueMs: Long = 300,
    var loss: Double = 0.0, var reorder: Double = 0.0, var reorderExtraMs: Long = 0, var dup: Double = 0.0,
    seed: Long = 1, private val deliver: (ByteArray) -> Unit
) {
    private val rng = Random(seed)
    private var busyUntil = 0.0
    val outages = ArrayList<LongRange>()
    var tamperProb = 0.0
    var tamperedCount = 0
    var onWire: ((ByteArray) -> Unit)? = null
    var blackholeFrom = Long.MAX_VALUE       // 从该虚拟时刻起整条线丢弃一切
    var bytesIn = 0L        // 进入线路的字节（含 4 字节 ChannelData 头 + 28 字节 IP/UDP）
    var bytesDelivered = 0L
    var droppedQueue = 0L
    var droppedRandom = 0L

    fun send(pkt: ByteArray) {
        val now = s.t
        val wire = pkt.size + 4 + 28
        bytesIn += wire
        onWire?.invoke(pkt)
        if (now >= blackholeFrom || outages.any { now in it }) return
        var txDone = now.toDouble()
        if (bwBytesPerSec > 0) {
            val backlog = maxOf(0.0, busyUntil - now)
            if (backlog > queueMs) { droppedQueue++; return }
            busyUntil = maxOf(now.toDouble(), busyUntil) + wire * 1000.0 / bwBytesPerSec
            txDone = busyUntil
        }
        if (rng.nextDouble() < loss) { droppedRandom++; return }
        var at = Math.ceil(txDone).toLong() + oneWayMs
        if (reorder > 0 && rng.nextDouble() < reorder) at += 1 + (rng.nextDouble() * reorderExtraMs).toLong()
        var p = pkt
        if (tamperProb > 0 && rng.nextDouble() < tamperProb) {
            p = pkt.copyOf(); val i = rng.nextInt(p.size); p[i] = (p[i].toInt() xor (1 shl rng.nextInt(8))).toByte(); tamperedCount++
        }
        val copies = if (dup > 0 && rng.nextDouble() < dup) 2 else 1
        for (c in 0 until copies) {
            val q = p
            s.after(maxOf(0L, at - now + c * 3)) { bytesDelivered += wire; deliver(q) }
        }
    }
}

/** 确定性数据源：内容只由位置决定，边读边算 SHA-256。 */
class GenSource(val size: Long, private val seed: Long = 7) : RudpSource {
    private var pos = 0L
    val digest = sha256()
    var closed = false
    var maxPos = 0L
    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val n = minOf(len.toLong(), size - pos).toInt()
        fill(pos, buf, off, n)
        digest.update(buf, off, n)
        pos += n
        return n
    }
    override fun close() { closed = true }
    companion object {
        private fun mix(x: Long): Long { var z = x * -7046029254386353131L; z = (z xor (z ushr 32)) * -4658895280553007687L; return z xor (z ushr 29) }
        fun fill(pos: Long, buf: ByteArray, off: Int, n: Int, seed: Long = 7) {
            var w = -1L; var v = 0L
            for (i in 0 until n) {
                val p = pos + i
                val wi = p ushr 3
                if (wi != w) { w = wi; v = mix(wi xor seed) }
                buf[off + i] = (v ushr (((p and 7).toInt()) * 8)).toByte()
            }
        }
    }
}

/** 接收端：顺序写入，算 SHA-256；可设置写到多少字节后报「存储空间不足」。 */
class HashSink(private val expect: Long, private val nospaceAfter: Long = Long.MAX_VALUE, private val name: String = "f.bin") : RudpSink {
    val digest = sha256()
    var count = 0L
    var aborted = false
    var finished = false
    override fun write(buf: ByteArray, off: Int, len: Int): String {
        if (count + len > nospaceAfter) return "err:nospace"
        digest.update(buf, off, len); count += len
        return "ok"
    }
    override fun finish(): String { finished = true; return if (count == expect) "ok:$name" else "err:size" }
    override fun abort() { aborted = true }
}

class Rec : RudpListener {
    var opened = false; var openRtt = -1
    var failReason: String? = null
    var closedWhy: String? = null
    val messages = ArrayList<Pair<Long, String>>()
    class Done(val xid: Int, val sending: Boolean, val ok: Boolean, val msg: String, val at: Long)
    val dones = ArrayList<Done>()
    var progressCalls = 0
    var lastLoss = 0
    var stats = 0
    var clock: () -> Long = { 0L }
    override fun onOpen(rttMs: Int) { opened = true; openRtt = rttMs }
    override fun onFail(reason: String) { failReason = reason }
    override fun onClosed(why: String) { closedWhy = why }
    override fun onMessage(text: String) { messages.add(clock() to text) }
    override fun onProgress(xid: Int, sending: Boolean, done: Long, total: Long, rateBps: Long, lossPct: Int, rttMs: Int) { progressCalls++; lastLoss = lossPct }
    override fun onFileDone(xid: Int, sending: Boolean, ok: Boolean, msg: String) { dones.add(Done(xid, sending, ok, msg, clock())) }
    override fun onStat(sentWire: Long, recvWire: Long) { stats++ }
    fun done(xid: Int, sending: Boolean) = dones.firstOrNull { it.xid == xid && it.sending == sending }
}

/** 两个通道 + 两条单向线路 + 虚拟时钟。 */
class Net(
    val rttMs: Long, val bwBytesPerSec: Long = 0, val loss: Double = 0.0, val reorder: Double = 0.0, val reorderExtraMs: Long = 0,
    val dup: Double = 0.0, val queueMs: Long = 300, val capKbps: Int = 2048, seed: Long = 1,
    keyB: ByteArray? = null, val cfgA: RudpConfig = RudpConfig(userCapKbps = capKbps), val cfgB: RudpConfig = RudpConfig(userCapKbps = capKbps)
) {
    val s = VSched()
    val key = randomKey()
    val recA = Rec(); val recB = Rec()
    lateinit var a: RudpChannel
    lateinit var b: RudpChannel
    val ab: SimLink
    val ba: SimLink
    init {
        recA.clock = { s.t }; recB.clock = { s.t }
        ab = SimLink(s, rttMs / 2, bwBytesPerSec, queueMs, loss, reorder, reorderExtraMs, dup, seed) { b.onPacket(it) }
        ba = SimLink(s, rttMs / 2, bwBytesPerSec, queueMs, loss, reorder, reorderExtraMs, dup, seed + 1) { a.onPacket(it) }
        a = RudpChannel(key, 0, s, { ab.send(it) }, recA, cfgA)
        b = RudpChannel(keyB ?: key, 1, s, { ba.send(it) }, recB, cfgB)
    }
    fun open(limitMs: Long = 60_000) {
        a.start(); b.start()
        check(s.runUntil(limitMs) { recA.opened && recB.opened }) { "握手未完成 A=${recA.opened} B=${recB.opened} fail=${recA.failReason}" }
    }
}

/** 传一个文件 A→B，返回 (用时 ms, 发送端摘要, 接收端 sink)。 */
class Xfer(val net: Net, val xid: Int, val size: Long, val from: RudpChannel, val to: RudpChannel, val fromRec: Rec, val toRec: Rec,
           nospaceAfter: Long = Long.MAX_VALUE) {
    val src = GenSource(size, 7L + xid)
    val sink = HashSink(size, nospaceAfter)
    var startAt = 0L
    fun start() {
        startAt = net.s.t
        to.recvFile(xid, size, sink)
        from.sendFile(xid, size, src)
    }
    fun finished() = fromRec.done(xid, true) != null && toRec.done(xid, false) != null
    private val okCache by lazy { fromRec.done(xid, true)?.ok == true && toRec.done(xid, false)?.ok == true && hexOf(src.digest.digest()) == hexOf(sink.digest.digest()) }
    fun ok() = okCache
    fun elapsed() = maxOf(fromRec.done(xid, true)?.at ?: net.s.t, toRec.done(xid, false)?.at ?: net.s.t) - startAt
}
