package com.phoneshare.app.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * STUN / TURN 报文编解码（RFC 5389 / 5766），纯 Kotlin，不依赖 android.*。
 * 只支持 IPv4 地址属性。
 */
object Stun {
    const val MAGIC_COOKIE = 0x2112A442

    const val ALLOCATE_REQUEST = 0x0003
    const val ALLOCATE_SUCCESS = 0x0103
    const val ALLOCATE_ERROR = 0x0113
    const val REFRESH_REQUEST = 0x0004
    const val REFRESH_SUCCESS = 0x0104
    const val REFRESH_ERROR = 0x0114
    const val CREATE_PERMISSION_REQUEST = 0x0008
    const val CREATE_PERMISSION_SUCCESS = 0x0108
    const val CREATE_PERMISSION_ERROR = 0x0118
    const val CHANNEL_BIND_REQUEST = 0x0009
    const val CHANNEL_BIND_SUCCESS = 0x0109
    const val CHANNEL_BIND_ERROR = 0x0119

    const val ATTR_USERNAME = 0x0006
    const val ATTR_MESSAGE_INTEGRITY = 0x0008
    const val ATTR_ERROR_CODE = 0x0009
    const val ATTR_CHANNEL_NUMBER = 0x000C
    const val ATTR_LIFETIME = 0x000D
    const val ATTR_XOR_PEER_ADDRESS = 0x0012
    const val ATTR_REALM = 0x0014
    const val ATTR_NONCE = 0x0015
    const val ATTR_XOR_RELAYED_ADDRESS = 0x0016
    const val ATTR_REQUESTED_TRANSPORT = 0x0019
    const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    const val ATTR_FINGERPRINT = 0x8028

    private val rnd = SecureRandom()

    class Attr(val type: Int, val value: ByteArray)

    class Msg(val type: Int, val txId: ByteArray, val attrs: List<Attr>, val raw: ByteArray) {
        fun get(t: Int): ByteArray? = attrs.firstOrNull { it.type == t }?.value
        fun str(t: Int): String? = get(t)?.toString(Charsets.UTF_8)
        fun errorCode(): Int? {
            val a = get(ATTR_ERROR_CODE) ?: return null
            if (a.size < 4) return null
            return (a[2].toInt() and 0x07) * 100 + (a[3].toInt() and 0xff)
        }
        fun lifetime(): Int? {
            val a = get(ATTR_LIFETIME) ?: return null
            if (a.size < 4) return null
            return ByteBuffer.wrap(a).order(ByteOrder.BIG_ENDIAN).int
        }
        /** 成功响应：类位 (type & 0x0110) == 0x0100；错误响应：0x0110 */
        val isSuccess: Boolean get() = (type and 0x0110) == 0x0100
        val isError: Boolean get() = (type and 0x0110) == 0x0110
    }

    fun newTxId(): ByteArray = ByteArray(12).also { rnd.nextBytes(it) }

    fun longTermKey(user: String, realm: String, pass: String): ByteArray =
        MessageDigest.getInstance("MD5").digest("$user:$realm:$pass".toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        mac.update(data, off, len)
        return mac.doFinal()
    }

    private fun pad4(n: Int) = (4 - (n and 3)) and 3

    /** 构造报文。integrityKey 不为空则追加 MESSAGE-INTEGRITY（HMAC-SHA1）；fingerprint 仅测试向量使用。 */
    fun build(type: Int, txId: ByteArray, attrs: List<Attr>, integrityKey: ByteArray? = null, fingerprint: Boolean = false): ByteArray {
        var bodyLen = 0
        for (a in attrs) bodyLen += 4 + a.value.size + pad4(a.value.size)
        val extra = (if (integrityKey != null) 24 else 0) + (if (fingerprint) 8 else 0)
        val total = 20 + bodyLen + extra
        val out = ByteArray(total)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(type.toShort())
        bb.putShort((bodyLen + extra).toShort())
        bb.putInt(MAGIC_COOKIE)
        bb.put(txId)
        for (a in attrs) {
            bb.putShort(a.type.toShort())
            bb.putShort(a.value.size.toShort())
            bb.put(a.value)
            repeat(pad4(a.value.size)) { bb.put(0) }
        }
        if (integrityKey != null) {
            // 长度字段此时已包含 MI（及其后的 FINGERPRINT）；RFC 要求 HMAC 计算时长度只算到 MI 末尾
            val miOff = 20 + bodyLen
            val tmp = out.copyOf(miOff)
            ByteBuffer.wrap(tmp).order(ByteOrder.BIG_ENDIAN).putShort(2, (bodyLen + 24).toShort())
            val d = hmac(integrityKey, tmp, 0, miOff)
            bb.putShort(ATTR_MESSAGE_INTEGRITY.toShort())
            bb.putShort(20)
            bb.put(d)
        }
        if (fingerprint) {
            val crc = java.util.zip.CRC32()
            crc.update(out, 0, total - 8)
            val fp = (crc.value xor 0x5354554EL).toInt()
            bb.putShort(ATTR_FINGERPRINT.toShort())
            bb.putShort(4)
            bb.putInt(fp)
        }
        return out
    }

    /** 严格解析；格式不对返回 null。 */
    fun parse(data: ByteArray): Msg? {
        if (data.size < 20) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val type = bb.short.toInt() and 0xffff
        if (type and 0xC000 != 0) return null
        val length = bb.short.toInt() and 0xffff
        if (length and 3 != 0 || 20 + length > data.size) return null
        if (bb.int != MAGIC_COOKIE) return null
        val tx = ByteArray(12)
        bb.get(tx)
        val attrs = ArrayList<Attr>()
        var pos = 20
        val end = 20 + length
        while (pos + 4 <= end) {
            val at = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
            val al = ((data[pos + 2].toInt() and 0xff) shl 8) or (data[pos + 3].toInt() and 0xff)
            if (pos + 4 + al > end) return null
            attrs.add(Attr(at, data.copyOfRange(pos + 4, pos + 4 + al)))
            pos += 4 + al + pad4(al)
        }
        return Msg(type, tx, attrs, data.copyOf(end))
    }

    /** 报文里有 MESSAGE-INTEGRITY 且校验通过才返回 true。 */
    fun verifyIntegrity(m: Msg, key: ByteArray): Boolean {
        val raw = m.raw
        var pos = 20
        while (pos + 4 <= raw.size) {
            val at = ((raw[pos].toInt() and 0xff) shl 8) or (raw[pos + 1].toInt() and 0xff)
            val al = ((raw[pos + 2].toInt() and 0xff) shl 8) or (raw[pos + 3].toInt() and 0xff)
            if (at == ATTR_MESSAGE_INTEGRITY) {
                if (al != 20 || pos + 24 > raw.size) return false
                val tmp = raw.copyOf(pos)
                ByteBuffer.wrap(tmp).order(ByteOrder.BIG_ENDIAN).putShort(2, (pos + 24 - 20).toShort())
                val d = hmac(key, tmp, 0, pos)
                return MessageDigest.isEqual(d, raw.copyOfRange(pos + 4, pos + 24))
            }
            pos += 4 + al + pad4(al)
        }
        return false
    }

    fun hasIntegrity(m: Msg) = m.get(ATTR_MESSAGE_INTEGRITY) != null

    // ---- 属性辅助 ----
    fun str(t: Int, s: String) = Attr(t, s.toByteArray(Charsets.UTF_8))
    fun requestedTransportUdp() = Attr(ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0))
    fun lifetime(sec: Int) = Attr(ATTR_LIFETIME, ByteBuffer.allocate(4).putInt(sec).array())
    fun channelNumber(ch: Int) = Attr(ATTR_CHANNEL_NUMBER, ByteBuffer.allocate(4).putShort(ch.toShort()).putShort(0).array())
    fun xorAddr(type: Int, ip: String, port: Int): Attr {
        val parts = ip.split(".")
        require(parts.size == 4) { "只支持 IPv4" }
        val out = ByteArray(8)
        out[1] = 1
        val xp = port xor (MAGIC_COOKIE ushr 16)
        out[2] = (xp ushr 8).toByte(); out[3] = xp.toByte()
        val ck = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
        for (i in 0..3) out[4 + i] = (parts[i].toInt() xor ck[i].toInt()).toByte()
        return Attr(type, out)
    }
    /** 解 XOR-*-ADDRESS（IPv4）。 */
    fun parseXorAddr(v: ByteArray?): Pair<String, Int>? {
        if (v == null || v.size < 8 || (v[1].toInt() and 0xff) != 1) return null
        val port = (((v[2].toInt() and 0xff) shl 8) or (v[3].toInt() and 0xff)) xor (MAGIC_COOKIE ushr 16)
        val ck = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
        val ip = IntArray(4) { (v[4 + it].toInt() xor ck[it].toInt()) and 0xff }
        return "${ip[0]}.${ip[1]}.${ip[2]}.${ip[3]}" to port
    }
    fun errorAttr(code: Int, reason: String): Attr {
        val r = reason.toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + r.size)
        out[2] = (code / 100).toByte(); out[3] = (code % 100).toByte()
        System.arraycopy(r, 0, out, 4, r.size)
        return Attr(ATTR_ERROR_CODE, out)
    }
}
