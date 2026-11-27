package relaytest

import com.phoneshare.app.relay.Stun
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 假 TURN 服务器（本机 UDP）：长期凭证、Allocate / Refresh / CreatePermission / ChannelBind / ChannelData 转发。
 * 带若干故障开关，供单元测试使用。
 */
class FakeTurn(val user: String = "u", val pass: String = "p", val realm: String = "fake.realm") {
    val sock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val addr = InetSocketAddress("127.0.0.1", sock.localPort)

    // 故障开关
    @Volatile var respDelayMs = 0L             // 响应延迟这么久再发（异步，不阻塞别的请求）
    @Volatile var mute = false                 // 不回任何 STUN 响应
    @Volatile var dropResponses = 0            // 吞掉接下来 N 个响应（模拟丢包，验证重传）
    @Volatile var corruptSuccessMi = false     // 成功响应带错误的 MESSAGE-INTEGRITY
    @Volatile var allocateError = 0            // 对已认证的 Allocate 返回该错误码
    @Volatile var forceStaleOnce = false       // 下一个已认证请求返回 438 并换新 nonce

    // 记录
    val lifetimes = CopyOnWriteArrayList<Int>()
    val allocCount = AtomicInteger()
    val refreshCount = AtomicInteger()
    val permCount = AtomicInteger()
    val bindCount = AtomicInteger()
    val requestCount = AtomicInteger()
    val seenTx = ConcurrentHashMap<String, Int>()   // txId → 收到次数（验证重传沿用同一事务 ID）
    @Volatile var liveAllocs = 0

    private class Alloc(val client: InetSocketAddress, val relay: DatagramSocket) {
        val perms = ConcurrentHashMap.newKeySet<String>()
        val chToPeer = ConcurrentHashMap<Int, InetSocketAddress>()
        val peerToCh = ConcurrentHashMap<String, Int>()
        @Volatile var alive = true
    }

    private val allocs = ConcurrentHashMap<String, Alloc>()
    private val respCache = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var nonce = "nonce" + System.nanoTime()
    private val key = Stun.longTermKey(user, realm, pass)
    @Volatile private var running = true
    private val th = Thread { loop() }.also { it.isDaemon = true; it.name = "fake-turn"; it.start() }

    fun stop() {
        running = false
        sock.close()
        for (a in allocs.values) { a.alive = false; a.relay.close() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }
    private fun k(a: InetSocketAddress) = "${a.address.hostAddress}:${a.port}"

    private fun reply(to: InetSocketAddress, pkt: ByteArray) {
        if (mute) return
        if (dropResponses > 0) { dropResponses--; return }
        val d = respDelayMs
        if (d > 0) { Thread { try { Thread.sleep(d); if (!mute) sock.send(DatagramPacket(pkt, pkt.size, to)) } catch (_: Exception) {} }.also { it.isDaemon = true }.start(); return }
        try { sock.send(DatagramPacket(pkt, pkt.size, to)) } catch (_: Exception) {}
    }

    private fun loop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                sock.receive(dp)
                val from = InetSocketAddress(dp.address, dp.port)
                val b0 = buf[0].toInt() and 0xff
                if (b0 in 0x40..0x7f) channelData(from, buf.copyOf(dp.length))
                else handleStun(from, buf.copyOf(dp.length))
            } catch (_: Exception) { if (!running) break }
        }
    }

    private fun channelData(from: InetSocketAddress, d: ByteArray) {
        val a = allocs[k(from)] ?: return
        val ch = ((d[0].toInt() and 0xff) shl 8) or (d[1].toInt() and 0xff)
        val len = ((d[2].toInt() and 0xff) shl 8) or (d[3].toInt() and 0xff)
        if (d.size < 4 + len) return
        val peer = a.chToPeer[ch] ?: return
        if (!a.perms.contains(peer.address.hostAddress)) return
        try { a.relay.send(DatagramPacket(d, 4, len, peer)) } catch (_: Exception) {}
    }

    private fun errResp(type: Int, tx: ByteArray, code: Int, withNonce: Boolean): ByteArray {
        val at = arrayListOf(Stun.errorAttr(code, "err"))
        if (withNonce) { at.add(Stun.str(Stun.ATTR_REALM, realm)); at.add(Stun.str(Stun.ATTR_NONCE, nonce)) }
        return Stun.build(type, tx, at)
    }

    private fun okResp(type: Int, tx: ByteArray, attrs: List<Stun.Attr>): ByteArray {
        val k = if (corruptSuccessMi) Stun.longTermKey(user, realm, "wrong") else key
        return Stun.build(type, tx, attrs, k)
    }

    private fun handleStun(from: InetSocketAddress, raw: ByteArray) {
        val m = Stun.parse(raw) ?: return
        requestCount.incrementAndGet()
        val txh = hex(m.txId)
        seenTx.merge(txh, 1) { a, b -> a + b }
        val errType = (m.type and 0x3EEF) or 0x0110
        // 认证
        val mi = Stun.hasIntegrity(m)
        if (!mi) { reply(from, errResp(errType, m.txId, 401, true)); return }
        val u = m.str(Stun.ATTR_USERNAME)
        val n = m.str(Stun.ATTR_NONCE)
        if (forceStaleOnce) { forceStaleOnce = false; nonce = "nonce" + System.nanoTime(); reply(from, errResp(errType, m.txId, 438, true)); return }
        if (n != nonce) { reply(from, errResp(errType, m.txId, 438, true)); return }
        if (u != user || !Stun.verifyIntegrity(m, key)) { reply(from, errResp(errType, m.txId, 401, true)); return }
        // 重传幂等：同一事务 ID 回放缓存响应
        respCache[txh]?.let { reply(from, it); return }
        val resp: ByteArray = when (m.type) {
            Stun.ALLOCATE_REQUEST -> {
                if (allocateError != 0) errResp(Stun.ALLOCATE_ERROR, m.txId, allocateError, false)
                else {
                    val a = allocs.getOrPut(k(from)) {
                        allocCount.incrementAndGet()
                        val rs = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                        val al = Alloc(from, rs); liveAllocs++
                        Thread { relayLoop(al) }.also { it.isDaemon = true; it.start() }
                        al
                    }
                    okResp(Stun.ALLOCATE_SUCCESS, m.txId, listOf(
                        Stun.xorAddr(Stun.ATTR_XOR_RELAYED_ADDRESS, "127.0.0.1", a.relay.localPort),
                        Stun.lifetime(600),
                        Stun.xorAddr(Stun.ATTR_XOR_MAPPED_ADDRESS, from.address.hostAddress, from.port)))
                }
            }
            Stun.REFRESH_REQUEST -> {
                refreshCount.incrementAndGet()
                val lt = m.lifetime() ?: 600
                lifetimes.add(lt)
                if (lt == 0) { allocs.remove(k(from))?.let { it.alive = false; it.relay.close(); liveAllocs-- } }
                okResp(Stun.REFRESH_SUCCESS, m.txId, listOf(Stun.lifetime(lt)))
            }
            Stun.CREATE_PERMISSION_REQUEST -> {
                permCount.incrementAndGet()
                val a = allocs[k(from)]
                val p = Stun.parseXorAddr(m.get(Stun.ATTR_XOR_PEER_ADDRESS))
                if (a == null || p == null) errResp(Stun.CREATE_PERMISSION_ERROR, m.txId, 437, false)
                else { a.perms.add(p.first); okResp(Stun.CREATE_PERMISSION_SUCCESS, m.txId, emptyList()) }
            }
            Stun.CHANNEL_BIND_REQUEST -> {
                bindCount.incrementAndGet()
                val a = allocs[k(from)]
                val p = Stun.parseXorAddr(m.get(Stun.ATTR_XOR_PEER_ADDRESS))
                val cn = m.get(Stun.ATTR_CHANNEL_NUMBER)
                if (a == null || p == null || cn == null) errResp(Stun.CHANNEL_BIND_ERROR, m.txId, 437, false)
                else {
                    val ch = ((cn[0].toInt() and 0xff) shl 8) or (cn[1].toInt() and 0xff)
                    val pa = InetSocketAddress(p.first, p.second)
                    a.chToPeer[ch] = pa; a.peerToCh["${p.first}:${p.second}"] = ch; a.perms.add(p.first)
                    okResp(Stun.CHANNEL_BIND_SUCCESS, m.txId, emptyList())
                }
            }
            else -> errResp(errType, m.txId, 400, false)
        }
        respCache[txh] = resp
        reply(from, resp)
    }

    private fun relayLoop(a: Alloc) {
        val buf = ByteArray(2048)
        while (a.alive && running) {
            try {
                val dp = DatagramPacket(buf, buf.size)
                a.relay.receive(dp)
                val srcIp = dp.address.hostAddress
                if (!a.perms.contains(srcIp)) continue
                val ch = a.peerToCh["$srcIp:${dp.port}"] ?: continue
                val out = ByteArray(4 + dp.length)
                out[0] = (ch ushr 8).toByte(); out[1] = ch.toByte()
                out[2] = (dp.length ushr 8).toByte(); out[3] = dp.length.toByte()
                System.arraycopy(buf, 0, out, 4, dp.length)
                sock.send(DatagramPacket(out, out.size, a.client))
            } catch (_: Exception) { if (!a.alive || !running) break }
        }
    }
}
