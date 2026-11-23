package com.phoneshare.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LanServer(private val ctx: Context) {
    data class Entry(
        val id: String,
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String
    )

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private val pool = Executors.newCachedThreadPool()
    private val slots = Semaphore(2)
    private val files = ConcurrentHashMap<String, Entry>()
    private val activeByKey = ConcurrentHashMap<String, Socket>()
    private val lastWriteMap = ConcurrentHashMap<Socket, AtomicLong>()
    @Volatile var token: String = ""
        private set
    @Volatile var bindIp: String = ""
        private set
    @Volatile var port: Int = 0
        private set
    @Volatile private var peerIp: String? = null
    private val lastActive = AtomicLong(System.currentTimeMillis())
    private val idleMs = 10 * 60 * 1000L

    var onEvent: ((JSONObject) -> Unit)? = null

    fun isRunning(): Boolean = running.get()

    fun start(bind: String, peer: String?, entries: List<Entry>): Boolean {
        stop()
        if (entries.isEmpty()) return false
        files.clear()
        entries.forEach { files[it.id] = it }
        token = randomToken()
        peerIp = peer?.takeIf { it.isNotEmpty() }
        bindIp = bind
        return try {
            val ss = ServerSocket(0, 50, InetAddress.getByName(bind))
            server = ss
            port = ss.localPort
            running.set(true)
            lastActive.set(System.currentTimeMillis())
            thread = Thread({ acceptLoop(ss) }, "LanServer").also { it.isDaemon = true; it.start() }
            pool.execute { idleWatch() }
            true
        } catch (e: Exception) {
            Logx.add("LanServer 启动失败: " + e.message)
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && server == null) return
        running.set(false)
        token = ""
        files.clear()
        try { server?.close() } catch (_: Throwable) {}
        server = null
        activeByKey.values.forEach { try { it.close() } catch (_: Throwable) {} }
        activeByKey.clear()
        lastWriteMap.clear()
        thread?.interrupt()
        thread = null
    }

    private fun idleWatch() {
        while (running.get()) {
            try { Thread.sleep(5_000) } catch (_: InterruptedException) { break }
            if (System.currentTimeMillis() - lastActive.get() > idleMs) {
                Logx.add("LanServer 空闲超时，关闭")
                stop()
                break
            }
            if (!ipStillUp(bindIp)) {
                Logx.add("LanServer 绑定 IP 已失效，关闭")
                stop()
                break
            }
            // 写出看门狗：60s 无字节则关闭连接
            val now = System.currentTimeMillis()
            for ((sock, ts) in lastWriteMap.entries.toList()) {
                if (now - ts.get() > 60_000) {
                    try { sock.close() } catch (_: Throwable) {}
                    lastWriteMap.remove(sock)
                }
            }
        }
    }

    private fun ipStillUp(ip: String): Boolean {
        if (ip.isEmpty()) return false
        return try {
            val faces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in faces) {
                if (!ni.isUp) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr.hostAddress == ip) return true
                }
            }
            false
        } catch (_: Exception) { false }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val s = ss.accept()
                lastActive.set(System.currentTimeMillis())
                pool.execute { handle(s) }
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                if (running.get()) Logx.add("LanServer accept: " + e.message)
            }
        }
    }

    private fun handle(socket: Socket) {
        var acquired = false
        try {
            socket.soTimeout = 10_000
            socket.tcpNoDelay = true
            var remote = socket.inetAddress?.hostAddress ?: ""
            if (remote.startsWith("::ffff:")) remote = remote.substring(7)
            val peer = peerIp
            if (peer != null && peer.isNotEmpty() && remote != peer) {
                reply(socket, 403, "Forbidden", 0)
                return
            }

            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val headerBytes = ByteArray(8192)
            var n = 0
            while (n < headerBytes.size) {
                val b = input.read()
                if (b < 0) break
                headerBytes[n++] = b.toByte()
                if (n >= 4 &&
                    headerBytes[n - 4] == '\r'.code.toByte() &&
                    headerBytes[n - 3] == '\n'.code.toByte() &&
                    headerBytes[n - 2] == '\r'.code.toByte() &&
                    headerBytes[n - 1] == '\n'.code.toByte()
                ) break
            }
            if (n >= 8192) {
                reply(socket, 400, "Header too large", 0)
                return
            }
            val header = String(headerBytes, 0, n, Charsets.ISO_8859_1)
            val lines = header.split("\r\n")
            if (lines.isEmpty()) return
            val req = lines[0].split(" ")
            if (req.size < 2 || req[0] != "GET") {
                reply(socket, 405, "Method Not Allowed", 0)
                return
            }
            val pathQ = req[1]
            val qIdx = pathQ.indexOf('?')
            val path = if (qIdx >= 0) pathQ.substring(0, qIdx) else pathQ
            val query = if (qIdx >= 0) pathQ.substring(qIdx + 1) else ""
            val params = parseQuery(query)
            val t = params["t"] ?: ""
            if (t != token || token.isEmpty()) {
                reply(socket, 403, "Forbidden", 0)
                return
            }
            if (!path.startsWith("/f/")) {
                reply(socket, 404, "Not Found", 0)
                return
            }
            val id = path.substring(3)
            val entry = files[id]
            if (entry == null) {
                reply(socket, 404, "Not Found", 0)
                return
            }

            // close old connection for same peer+file before taking slot
            val key = remote + "|" + id
            activeByKey.remove(key)?.let { try { it.close() } catch (_: Throwable) {} }
            activeByKey[key] = socket
            lastWriteMap[socket] = AtomicLong(System.currentTimeMillis())

            if (!slots.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                reply(socket, 503, "Busy", 0)
                return
            }
            acquired = true
            socket.soTimeout = 60_000

            var rangeHeader: String? = null
            for (line in lines) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substring(6).trim()
                    break
                }
            }
            serveFile(socket, entry, rangeHeader, id)
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        } finally {
            if (acquired) slots.release()
            lastWriteMap.remove(socket)
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun serveFile(socket: Socket, entry: Entry, rangeHeader: String?, id: String) {
        var size = entry.size
        if (size < 0) {
            try {
                ctx.contentResolver.openFileDescriptor(entry.uri, "r")?.use {
                    size = it.statSize
                }
            } catch (_: Exception) {}
        }
        if (size < 0) {
            reply(socket, 500, "Unknown size", 0)
            return
        }
        var start = 0L
        var end = size - 1
        var status = 200
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.substring(6).trim()
            try {
                if (spec.startsWith("-")) {
                    val suffix = spec.substring(1).toLong()
                    if (suffix <= 0 || size <= 0) {
                        reply(socket, 416, "Range Not Satisfiable", size)
                        return
                    }
                    start = (size - suffix).coerceAtLeast(0)
                    end = size - 1
                } else {
                    val parts = spec.split("-")
                    start = parts[0].toLong()
                    end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else size - 1
                }
                if (start < 0 || start >= size || end < start) {
                    reply(socket, 416, "Range Not Satisfiable", size)
                    return
                }
                end = end.coerceAtMost(size - 1)
                status = 206
            } catch (_: Exception) {
                reply(socket, 416, "Range Not Satisfiable", size)
                return
            }
        }
        val length = end - start + 1
        val out = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
        val sb = StringBuilder()
        sb.append(if (status == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ").append(entry.mime.ifEmpty { "application/octet-stream" }).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        if (status == 206) {
            sb.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(size).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()

        var absPos = start // absolute file position written
        var windowStart = System.nanoTime()
        var windowBytes = 0L
        var lastEmit = 0L
        try {
            writeRange(entry.uri, start, end, out, socket) { n ->
                absPos += n
                windowBytes += n
                lastWriteMap[socket]?.set(System.currentTimeMillis())
                lastActive.set(System.currentTimeMillis())
                val now = System.currentTimeMillis()
                if (now - lastEmit >= 300) {
                    val elapsed = (System.nanoTime() - windowStart) / 1e9
                    val bps = if (elapsed > 0.2) (windowBytes / elapsed).toLong() else 0L
                    emitSent(id, absPos, size, bps)
                    lastEmit = now
                    windowStart = System.nanoTime()
                    windowBytes = 0
                }
            }
            out.flush()
            // served 仅当整文件发完：请求覆盖到末字节且已写出到 size
            val wholeFile = (size == 0L) || (end == size - 1 && absPos >= size)
            if (wholeFile) {
                emitSent(id, size, size, 0)
                emitServed(id)
            }
        } catch (_: Exception) {
            // interrupted
        }
    }

    /** @return true if fully wrote the requested range */
    private fun writeRange(
        uri: Uri,
        start: Long,
        end: Long,
        out: BufferedOutputStream,
        socket: Socket,
        onChunk: (Int) -> Unit
    ): Boolean {
        val length = end - start + 1
        var left = length
        var already = 0L
        // try channel first
        try {
            openChannel(uri)?.use { ch ->
                val buf = ByteBuffer.allocate(256 * 1024)
                var pos = start
                while (pos <= end && running.get()) {
                    buf.clear()
                    val want = minOf(buf.capacity().toLong(), end - pos + 1).toInt()
                    buf.limit(want)
                    val r = ch.read(buf, pos)
                    if (r <= 0) break
                    out.write(buf.array(), 0, r)
                    pos += r
                    left -= r
                    already += r
                    onChunk(r)
                }
                return left <= 0
            }
        } catch (_: Exception) {
            // fall through: skip start + already written
        }
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            skipFully(ins, start + already)
            val buf = ByteArray(256 * 1024)
            while (left > 0 && running.get()) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                left -= n
                already += n
                onChunk(n)
            }
        }
        return left <= 0
    }

    private fun emitSent(id: String, done: Long, total: Long, bps: Long) {
        try {
            onEvent?.invoke(
                JSONObject().put("e", "sent").put("id", id)
                    .put("done", done).put("total", total).put("bps", bps)
            )
        } catch (_: Throwable) {}
    }

    private fun emitServed(id: String) {
        try {
            onEvent?.invoke(JSONObject().put("e", "served").put("id", id))
        } catch (_: Throwable) {}
    }

    private fun openChannel(uri: Uri): FileChannel? {
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            java.io.FileInputStream(pfd.fileDescriptor).channel
        } catch (_: Exception) {
            null
        }
    }

    private fun skipFully(ins: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val sk = ins.skip(left)
            if (sk > 0) {
                left -= sk
                continue
            }
            if (ins.read() < 0) break
            left--
        }
    }

    private fun reply(socket: Socket, code: Int, msg: String, totalSize: Long) {
        try {
            val out = socket.getOutputStream()
            val h = StringBuilder()
            h.append("HTTP/1.1 ").append(code).append(" ").append(msg).append("\r\n")
            h.append("Content-Length: 0\r\n")
            if (code == 416 && totalSize >= 0) {
                h.append("Content-Range: bytes */").append(totalSize).append("\r\n")
            }
            h.append("Connection: close\r\n\r\n")
            out.write(h.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
    }

    private fun parseQuery(q: String): Map<String, String> {
        val m = HashMap<String, String>()
        if (q.isEmpty()) return m
        for (part in q.split("&")) {
            val i = part.indexOf('=')
            if (i > 0) m[part.substring(0, i)] = java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
            else if (part.isNotEmpty()) m[part] = ""
        }
        return m
    }

    private fun randomToken(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        val sb = StringBuilder(32)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }
}
