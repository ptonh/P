package com.phoneshare.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LanFetcher(private val ctx: Context) {
    private val pool = Executors.newFixedThreadPool(2)
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val currentConn = AtomicReference<HttpURLConnection?>(null)
    @Volatile private var currentReq: String = ""

    var onEvent: ((JSONObject) -> Unit)? = null

    fun isActive(): Boolean = active.get()

    fun cancel() {
        cancelled.set(true)
        pool.execute {
            try { currentConn.get()?.disconnect() } catch (_: Exception) {}
        }
    }

    fun fetchBatch(
        req: String,
        base: String,
        token: String,
        files: JSONArray,
        onFinished: (() -> Unit)? = null
    ) {
        if (active.getAndSet(true)) {
            emit(JSONObject().put("e", "err").put("req", req).put("id", "")
                .put("code", "bad_arg").put("msg", "busy").put("fatal", true))
            return
        }
        currentReq = req
        cancelled.set(false)
        pool.execute {
            var okN = 0
            var failN = 0
            var canceledEmitted = false
            try {
                cleanupStale(ctx)
                val list = ArrayList<Triple<String, String, Long>>() // id,name,size
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    list.add(
                        Triple(
                            f.getString("id"),
                            sanitizeName(f.optString("name", "file")),
                            f.optLong("size", 0)
                        )
                    )
                }
                for ((id, name, size) in list) {
                    if (cancelled.get()) {
                        if (!canceledEmitted) {
                            emit(JSONObject().put("e", "err").put("req", req).put("id", id)
                                .put("code", "canceled").put("msg", "已取消").put("fatal", true))
                            canceledEmitted = true
                        }
                        failN += list.size - okN - failN
                        break
                    }
                    val r = downloadOne(req, base, token, id, name, size)
                    if (r) okN++ else {
                        failN++
                        if (cancelled.get()) canceledEmitted = true
                    }
                }
                if (cancelled.get() && !canceledEmitted) {
                    emit(JSONObject().put("e", "err").put("req", req).put("id", "")
                        .put("code", "canceled").put("msg", "已取消").put("fatal", true))
                    canceledEmitted = true
                }
                val remain = (list.size - okN - failN).coerceAtLeast(0)
                emit(JSONObject().put("e", "batch").put("req", req).put("ok", okN).put("fail", failN + remain))
            } catch (e: Exception) {
                emit(JSONObject().put("e", "err").put("req", req).put("id", "")
                    .put("code", "io").put("msg", e.message ?: "fetch").put("fatal", true))
                emit(JSONObject().put("e", "batch").put("req", req).put("ok", okN).put("fail", failN + 1))
            } finally {
                active.set(false)
                currentConn.set(null)
                onFinished?.invoke()
            }
        }
    }

    private fun downloadOne(req: String, base: String, token: String, id: String, name: String, size: Long): Boolean {
        var attempt = 0
        var got = 0L
        var out: OutputStream? = null
        var uri: Uri? = null
        var tmpFile: File? = null
        val mime = mimeFromName(name)
        val batchTag = Integer.toHexString(req.hashCode())
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues()
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(MediaStore.Downloads.MIME_TYPE, mime)
                cv.put(MediaStore.Downloads.IS_PENDING, 1)
                uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) {
                    emitErr(req, id, "io", "无法创建下载条目", false)
                    return false
                }
                out = ctx.contentResolver.openOutputStream(uri)
            } else {
                val dir = File(ctx.getExternalFilesDir(null), "lan")
                dir.mkdirs()
                tmpFile = File(dir, name + "." + batchTag + ".part")
                if (tmpFile.exists()) tmpFile.delete()
                out = FileOutputStream(tmpFile, false)
                got = 0
            }
            if (out == null) {
                uri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                emitErr(req, id, "io", "无法写入", false)
                return false
            }

            val url = base.trimEnd('/') + "/f/" + id + "?t=" + token
            while (attempt < 3 && !cancelled.get()) {
                attempt++
                try {
                    val conn = openConn(url)
                    currentConn.set(conn)
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.setRequestProperty("Connection", "close")
                    if (got > 0 && size > 0) {
                        conn.setRequestProperty("Range", "bytes=$got-")
                    }
                    val code = try { conn.responseCode } catch (e: Exception) {
                        if (cancelled.get()) {
                            emitErr(req, id, "canceled", "已取消", true)
                            failCleanup(uri, tmpFile)
                            return false
                        }
                        if (attempt >= 3) {
                            emitErr(req, id, "timeout", e.message ?: "timeout", got == 0L)
                            failCleanup(uri, tmpFile)
                            return false
                        }
                        continue
                    }
                    if (code == 403) {
                        conn.disconnect()
                        emitErr(req, id, "bad_token", "token 无效", true)
                        failCleanup(uri, tmpFile)
                        return false
                    }
                    if (code == 404) {
                        conn.disconnect()
                        emitErr(req, id, "not_found", "文件不存在", false)
                        failCleanup(uri, tmpFile)
                        return false
                    }
                    if (got > 0 && code == 200) {
                        conn.disconnect()
                        emitErr(req, id, "io", "服务端未支持续传", false)
                        failCleanup(uri, tmpFile)
                        return false
                    }
                    if (code != 200 && code != 206) {
                        conn.disconnect()
                        if (attempt >= 3) {
                            emitErr(req, id, "io", "HTTP $code", false)
                            failCleanup(uri, tmpFile)
                            return false
                        }
                        continue
                    }
                    if (got > 0 && code == 206) {
                        val cr = conn.getHeaderField("Content-Range") ?: ""
                        // bytes got-.../...
                        val m = Regex("bytes\\s+(\\d+)-").find(cr)
                        val start = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1
                        if (start != got) {
                            conn.disconnect()
                            emitErr(req, id, "io", "Range 不匹配", false)
                            failCleanup(uri, tmpFile)
                            return false
                        }
                    }
                    val ins = conn.inputStream
                    val buf = ByteArray(256 * 1024)
                    var lastCb = 0L
                    var windowStart = System.nanoTime()
                    var windowBytes = 0L
                    while (!cancelled.get()) {
                        val n = try { ins.read(buf) } catch (e: Exception) {
                            if (cancelled.get()) break
                            throw e
                        }
                        if (n < 0) break
                        try {
                            out.write(buf, 0, n)
                        } catch (e: Exception) {
                            val msg = e.message ?: ""
                            if (msg.contains("ENOSPC") || msg.contains("No space")) {
                                emitErr(req, id, "disk_full", "磁盘已满", true)
                                failCleanup(uri, tmpFile)
                                return false
                            }
                            throw e
                        }
                        got += n
                        windowBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastCb >= 300) {
                            val elapsed = (System.nanoTime() - windowStart) / 1e9
                            val bps = if (elapsed > 0.2) (windowBytes / elapsed).toLong() else 0L
                            emit(JSONObject().put("e", "progress").put("req", req).put("id", id)
                                .put("done", got).put("total", size).put("bps", bps))
                            lastCb = now
                            windowStart = System.nanoTime()
                            windowBytes = 0
                        }
                    }
                    ins.close()
                    conn.disconnect()
                    currentConn.set(null)
                    if (cancelled.get()) {
                        emitErr(req, id, "canceled", "已取消", true)
                        failCleanup(uri, tmpFile)
                        return false
                    }
                    if (size == 0L || got == size) {
                        out.flush()
                        out.close()
                        out = null
                        val resultUri: String
                        if (Build.VERSION.SDK_INT >= 29 && uri != null) {
                            val cv = ContentValues()
                            cv.put(MediaStore.Downloads.IS_PENDING, 0)
                            ctx.contentResolver.update(uri, cv, null, null)
                            resultUri = uri.toString()
                        } else {
                            resultUri = finishLegacy(tmpFile, name)
                            tmpFile = null
                        }
                        emit(JSONObject().put("e", "progress").put("req", req).put("id", id)
                            .put("done", got).put("total", size).put("bps", 0))
                        emit(JSONObject().put("e", "done").put("req", req).put("id", id).put("uri", resultUri))
                        return true
                    }
                    // incomplete, retry
                } catch (e: Exception) {
                    currentConn.set(null)
                    if (cancelled.get()) {
                        emitErr(req, id, "canceled", "已取消", true)
                        failCleanup(uri, tmpFile)
                        return false
                    }
                    if (attempt >= 3) {
                        emitErr(req, id, "io", e.message ?: "io", false)
                        failCleanup(uri, tmpFile)
                        return false
                    }
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                }
            }
            // 循环用尽仍未完成
            if (!cancelled.get()) {
                emitErr(req, id, "io", "数据不完整", false)
            }
            failCleanup(uri, tmpFile)
            return false
        } finally {
            try { out?.close() } catch (_: Exception) {}
        }
    }

    private fun finishLegacy(tmp: File?, name: String): String {
        if (tmp == null) return ""
        try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (pub != null && (Build.VERSION.SDK_INT < 23 ||
                        ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED)
            ) {
                pub.mkdirs()
                var dest = File(pub, name)
                if (dest.exists()) {
                    val dot = name.lastIndexOf('.')
                    val base = if (dot > 0) name.substring(0, dot) else name
                    val ext = if (dot > 0) name.substring(dot) else ""
                    for (i in 1..999) {
                        dest = File(pub, "$base ($i)$ext")
                        if (!dest.exists()) break
                    }
                }
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
                MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
                return dest.absolutePath
            }
        } catch (_: Exception) {}
        val dest = File(tmp.parentFile, name)
        tmp.renameTo(dest)
        return dest.absolutePath
    }

    private fun emitErr(req: String, id: String, code: String, msg: String, fatal: Boolean) {
        emit(JSONObject().put("e", "err").put("req", req).put("id", id)
            .put("code", code).put("msg", msg).put("fatal", fatal))
    }

    private fun failCleanup(uri: Uri?, tmp: File?) {
        try { uri?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
        try { tmp?.delete() } catch (_: Exception) {}
    }

    private fun openConn(urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        val host = url.host
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= 21) {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address.hostAddress ?: continue
                    if (a.contains(":")) continue
                    if (sameSubnet(a, host, la.prefixLength)) {
                        return n.openConnection(url) as HttpURLConnection
                    }
                }
            }
        }
        return url.openConnection() as HttpURLConnection
    }

    private fun sameSubnet(local: String, host: String, prefix: Int): Boolean {
        try {
            val l = ipv4(local) ?: return false
            val h = ipv4(host) ?: return false
            val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceIn(0, 32)))
            return (l and mask) == (h and mask)
        } catch (_: Exception) {
            return false
        }
    }

    private fun ipv4(s: String): Int? {
        val p = s.split(".")
        if (p.size != 4) return null
        var v = 0
        for (x in p) {
            val n = x.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }

    private fun emit(o: JSONObject) {
        try { onEvent?.invoke(o) } catch (_: Throwable) {}
    }

    companion object {
        fun sanitizeName(raw: String): String {
            var s = raw.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim()
            while (s.startsWith(".")) s = s.substring(1)
            if (s.length > 120) s = s.substring(s.length - 120)
            if (s.isEmpty()) s = "file"
            return s
        }

        fun isPrivateIp(host: String): Boolean {
            val p = host.split(".")
            if (p.size != 4) return false
            val a = p[0].toIntOrNull() ?: return false
            val b = p[1].toIntOrNull() ?: return false
            if (a == 10) return true
            if (a == 172 && b in 16..31) return true
            if (a == 192 && b == 168) return true
            return false
        }

        fun mimeFromName(name: String): String {
            val dot = name.lastIndexOf('.')
            if (dot < 0) return "application/octet-stream"
            val ext = name.substring(dot + 1).lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }

        fun cleanupStale(ctx: Context) {
            try {
                val dir = File(ctx.getExternalFilesDir(null), "lan")
                val now = System.currentTimeMillis()
                dir.listFiles()?.forEach { f ->
                    if (f.name.endsWith(".part") && now - f.lastModified() > 24 * 3600 * 1000L) {
                        f.delete()
                    }
                }
            } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val cr = ctx.contentResolver
                    val cutoff = (System.currentTimeMillis() / 1000) - 24 * 3600
                    if (Build.VERSION.SDK_INT >= 30) {
                        val args = android.os.Bundle()
                        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        args.putString(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                            MediaStore.Downloads.IS_PENDING + "=1 AND " + MediaStore.Downloads.DATE_ADDED + "<?"
                        )
                        args.putStringArray(
                            android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(cutoff.toString())
                        )
                        cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), args, null)
                            ?.use { c ->
                                val i = c.getColumnIndex(MediaStore.Downloads._ID)
                                while (c.moveToNext()) {
                                    val id = c.getLong(i)
                                    val u = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                                    try { cr.delete(u, null, null) } catch (_: Exception) {}
                                }
                            }
                    } else {
                        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        @Suppress("DEPRECATION")
                        val queryUri = MediaStore.setIncludePending(base)
                        cr.query(
                            queryUri,
                            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DATE_ADDED),
                            MediaStore.Downloads.IS_PENDING + "=1",
                            null,
                            null
                        )?.use { c ->
                            val i = c.getColumnIndex(MediaStore.Downloads._ID)
                            val d = c.getColumnIndex(MediaStore.Downloads.DATE_ADDED)
                            while (c.moveToNext()) {
                                val added = if (d >= 0) c.getLong(d) else 0
                                if (added > 0 && added < cutoff) {
                                    val id = c.getLong(i)
                                    val u = Uri.withAppendedPath(base, id.toString())
                                    try { cr.delete(u, null, null) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
