package com.phoneshare.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * window.NativeLan — 契约 v1.1
 * 事件：主线程调用 window.__nativeLan({e:..., ...}) 对象字面量
 */
class NativeLan(
    activity: Activity,
    webView: WebView,
    private val hostsProvider: HostsProvider
) {
    interface HostsProvider {
        fun allowedHosts(): Set<String>
    }

    data class Entry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String
    )

    private val actRef = WeakReference(activity)
    private val wvRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val appCtx = activity.applicationContext

    @Volatile private var mainHost: String = ""
    @Volatile private var debugAllowFile: Boolean = false
    @Volatile private var pendingReq: String = ""
    @Volatile private var fetchReq: String = ""

    private val table = ConcurrentHashMap<String, Entry>()
    private val saveHandles = ConcurrentHashMap<String, SaveHandle>()
    private val saveLock = Any()
    private val serviceHold = java.util.concurrent.atomic.AtomicInteger(0)
    private val prefs by lazy { appCtx.getSharedPreferences("lan_save_ledger", android.content.Context.MODE_PRIVATE) }
    private val pendingEvents = ArrayDeque<String>()
    private val maxPending = 200

    private var server: LanServer? = null
    private var fetcher: LanFetcher? = null

    fun setMainUrl(url: String?) {
        mainHost = hostOf(url)
        flushPending()
    }

    fun setDebugAllowFile(allow: Boolean) {
        debugAllowFile = allow
        if (allow) mainHost = "file"
    }

    private fun originOk(): Boolean {
        if (debugAllowFile && mainHost == "file") return true
        val host = mainHost
        val allowed = hostsProvider.allowedHosts().map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (host.isEmpty() || allowed.isEmpty()) {
            Logx.add("NativeLan origin 拒绝: host=$host allowed=$allowed")
            return false
        }
        val ok = allowed.any { a -> host == a || host.endsWith(".$a") }
        if (!ok) Logx.add("NativeLan origin 拒绝: host=$host allowed=$allowed")
        return ok
    }

    private fun badOrigin(): String = """{"ok":false,"err":"origin"}"""

    private fun hostOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        if (url.startsWith("file:")) return "file"
        return try {
            Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Throwable) { "" }
    }

    @JavascriptInterface
    fun version(): Int {
        if (!originOk()) return 0
        return 2
    }

    @JavascriptInterface
    fun netInfo(): String {
        if (!originOk()) return badOrigin()
        return try {
            val ips = ArrayList<Pair<String, String>>()
            val faces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in faces) {
                if (!ni.isUp) continue
                val name = ni.name ?: continue
                val low = name.lowercase()
                if (low == "lo" || low.startsWith("rmnet") || low.startsWith("ccmni") || low.startsWith("tun")) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    val h = addr.hostAddress ?: continue
                    if (h.contains(":")) continue
                    if (!LanFetcher.isPrivateIp(h)) continue
                    ips.add(h to name)
                }
            }
            var type = "none"
            var ip = ""
            if (ips.isNotEmpty()) {
                val hot = ips.firstOrNull { (_, n) ->
                    val l = n.lowercase()
                    l.startsWith("ap") || l.startsWith("swlan") || l.contains("softap") || l == "wlan1"
                }
                if (hot != null) {
                    ip = hot.first
                    type = "hotspot"
                } else {
                    ip = ips[0].first
                    type = "wifi"
                }
            }
            JSONObject().put("ok", true).put("ip", ip).put("type", type).put("addrs", JSONArray().also { arr ->
                ips.forEach { arr.put(JSONObject().put("ip", it.first).put("iface", it.second)) }
            }).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("err", e.message ?: "net").toString()
        }
    }

    @JavascriptInterface
    fun pickFiles(req: String): String {
        if (!originOk()) return badOrigin()
        pendingReq = req ?: ""
        val act = actRef.get() ?: return """{"ok":false,"err":"noactivity"}"""
        main.post {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                act.startActivityForResult(i, REQ_LAN_FILES)
            } catch (e: Exception) {
                emit(JSONObject().put("e", "picked").put("req", pendingReq).put("files", JSONArray()))
            }
        }
        return """{"ok":true}"""
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_LAN_FILES) return
        val req = pendingReq
        if (resultCode != Activity.RESULT_OK || data == null) {
            emit(JSONObject().put("e", "picked").put("req", req).put("files", JSONArray()))
            return
        }
        io.execute {
            val arr = JSONArray()
            try {
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { uris.add(it) }
                    }
                } else {
                    data.data?.let { uris.add(it) }
                }
                var n = 0
                for (uri in uris) {
                    if (n >= 20) break
                    try {
                        appCtx.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    val name = LanFetcher.sanitizeName(queryName(uri) ?: "file")
                    val size = querySize(uri)
                    val mime = queryMime(uri, name)
                    val id = randomId()
                    table[id] = Entry(uri, name, size, mime)
                    arr.put(JSONObject().put("id", id).put("name", name).put("size", size))
                    n++
                }
            } catch (e: Exception) {
                Logx.add("NativeLan pick: " + e.message)
            }
            emit(JSONObject().put("e", "picked").put("req", req).put("files", arr))
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            appCtx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        } catch (_: Exception) { null }
    }

    private fun querySize(uri: Uri): Long {
        try {
            appCtx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (c.moveToFirst() && i >= 0) {
                    val s = c.getLong(i)
                    if (s >= 0) return s
                }
            }
        } catch (_: Exception) {}
        try {
            appCtx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val s = pfd.statSize
                if (s >= 0) return s
            }
        } catch (_: Exception) {}
        return 0L
    }

    private fun queryMime(uri: Uri, name: String): String {
        try {
            val t = appCtx.contentResolver.getType(uri)
            if (!t.isNullOrEmpty()) return t
        } catch (_: Exception) {}
        return LanFetcher.mimeFromName(name)
    }

    private fun randomId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        val sb = StringBuilder(12)
        for (i in 0 until 12) sb.append(chars[rnd.nextInt(chars.length)])
        return sb.toString()
    }

    @JavascriptInterface
    fun serve(idsJson: String, optsJson: String?): String {
        if (!originOk()) return badOrigin()
        return try {
            if (server?.isRunning() == true) {
                return """{"ok":false,"err":"busy"}"""
            }
            val ids = JSONArray(idsJson)
            val opts = if (optsJson.isNullOrEmpty()) JSONObject() else JSONObject(optsJson)
            val peerRaw = opts.optString("peerIp", "")
            val peer = if (peerRaw.isEmpty()) null else peerRaw
            if (peer != null && !LanFetcher.isPrivateIp(peer)) {
                return """{"ok":false,"err":"bad_arg"}"""
            }
            val entries = ArrayList<LanServer.Entry>()
            for (i in 0 until ids.length()) {
                val id = ids.getString(i)
                val ent = table[id] ?: return """{"ok":false,"err":"bad_ids"}"""
                var size = ent.size
                if (size < 0) size = querySize(ent.uri)
                if (size < 0) size = 0
                entries.add(LanServer.Entry(id, ent.uri, ent.name, size, ent.mime))
            }
            if (entries.isEmpty()) return """{"ok":false,"err":"bad_ids"}"""
            val info = JSONObject(netInfo())
            val ip = info.optString("ip", "")
            if (ip.isEmpty()) return """{"ok":false,"err":"no_net"}"""
            val srv = LanServer(appCtx)
            server = srv
            srv.onEvent = { jo -> emit(jo) }
            if (!srv.start(ip, peer, entries)) {
                server = null
                return """{"ok":false,"err":"no_net"}"""
            }
            LanService.start(appCtx)
            JSONObject()
                .put("ok", true)
                .put("ip", srv.bindIp)
                .put("port", srv.port)
                .put("token", srv.token)
                .put("base", "http://${srv.bindIp}:${srv.port}")
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("err", e.message ?: "serve").toString()
        }
    }

    @JavascriptInterface
    fun fetch(req: String, baseUrl: String, token: String, filesJson: String): String {
        if (!originOk()) return badOrigin()
        if (fetcher?.isActive() == true) return """{"ok":false,"err":"busy"}"""
        // sync validate baseUrl
        if (!validBase(baseUrl)) return """{"ok":false,"err":"bad_url"}"""
        if (token.isEmpty() || token.length > 200) return """{"ok":false,"err":"bad_url"}"""
        val files = try { JSONArray(filesJson) } catch (_: Exception) {
            return """{"ok":false,"err":"bad_files"}"""
        }
        if (files.length() == 0 || files.length() > 20) return """{"ok":false,"err":"bad_files"}"""
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: return """{"ok":false,"err":"bad_files"}"""
            val id = f.optString("id", "")
            if (!id.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) return """{"ok":false,"err":"bad_files"}"""
            val size = f.optLong("size", -1)
            if (size < 0) return """{"ok":false,"err":"bad_files"}"""
            val name = LanFetcher.sanitizeName(f.optString("name", ""))
            if (name.isEmpty()) return """{"ok":false,"err":"bad_files"}"""
        }
        fetchReq = req
        val f = LanFetcher(appCtx)
        fetcher = f
        f.onEvent = { jo -> emit(jo) }
        LanService.start(appCtx)
        f.fetchBatch(req, baseUrl, token, files) {
            if (!f.isActive()) {
                // stop service if server also idle
                if (server?.isRunning() != true && saveHandles.isEmpty() && serviceHold.get() <= 0) {
                    try { LanService.stop(appCtx) } catch (_: Exception) {}
                }
            }
        }
        return """{"ok":true}"""
    }

    private fun validBase(baseUrl: String): Boolean {
        return try {
            if (!baseUrl.startsWith("http://")) return false
            val u = java.net.URL(baseUrl)
            if (!u.userInfo.isNullOrEmpty()) return false
            val host = u.host ?: return false
            if (!LanFetcher.isPrivateIp(host)) return false
            // 契约：必须显式带端口
            u.port in 1..65535
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun cancel(req: String): String {
        if (!originOk()) return badOrigin()
        if (req == fetchReq) {
            fetcher?.cancel()
        }
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun stop(): String {
        if (!originOk()) return badOrigin()
        try { server?.stop() } catch (_: Exception) {}
        server = null
        try { fetcher?.cancel() } catch (_: Exception) {}
        if (saveHandles.isEmpty() && serviceHold.get() <= 0) {
            try { LanService.stop(appCtx) } catch (_: Exception) {}
        }
        return """{"ok":true}"""
    }

    /** 主线程：window.__nativeLan({...}) */
    fun emit(payload: JSONObject) {
        try {
            if (!payload.has("e")) return
            var lit = payload.toString()
            lit = lit.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            val js = "(function(){if(window.__nativeLan){window.__nativeLan($lit);return 1}return 0})()"
            main.post {
                try {
                    val wv = wvRef.get()
                    if (wv == null) {
                        enqueue(lit)
                        return@post
                    }
                    wv.evaluateJavascript(js) { result ->
                        if (result == null || result == "null" || result == "0") {
                            enqueue(lit)
                        }
                    }
                } catch (_: Throwable) {
                    enqueue(lit)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun enqueue(lit: String) {
        synchronized(pendingEvents) {
            if (pendingEvents.size >= maxPending) pendingEvents.removeFirst()
            pendingEvents.addLast(lit)
        }
    }

    private fun flushPending() {
        main.post {
            val batch = ArrayList<String>()
            synchronized(pendingEvents) {
                while (pendingEvents.isNotEmpty()) batch.add(pendingEvents.removeFirst())
            }
            val wv = wvRef.get() ?: return@post
            for (lit in batch) {
                val js = "(function(){if(window.__nativeLan){window.__nativeLan($lit);return 1}return 0})()"
                try {
                    wv.evaluateJavascript(js) { result ->
                        if (result == null || result == "null" || result == "0") {
                            enqueue(lit)
                        }
                    }
                } catch (_: Throwable) {
                    enqueue(lit)
                }
            }
        }
    }


    // ---------- 分块保存 v1.2 ----------
    private class SaveHandle(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String,
        var uri: android.net.Uri?,
        var file: java.io.File?,
        var out: java.io.OutputStream?,
        var written: Long = 0,
        var lastWriteAt: Long = System.currentTimeMillis(),
        val lock: Any = Any()
    )

    internal fun holdService() {
        if (serviceHold.getAndIncrement() == 0) {
            try { LanService.start(appCtx) } catch (_: Exception) {}
        }
    }

    internal fun releaseService() {
        val v = serviceHold.decrementAndGet()
        if (v <= 0) {
            serviceHold.set(0)
            if (server?.isRunning() != true && fetcher?.isActive() != true) {
                try { LanService.stop(appCtx) } catch (_: Exception) {}
            }
        }
    }

    private fun ledgerAdd(id: String, token: String) {
        try {
            val set = prefs.getStringSet("open", HashSet())?.toMutableSet() ?: HashSet()
            set.add(id + "|" + token)
            prefs.edit().putStringSet("open", set).apply()
        } catch (_: Exception) {}
    }

    private fun ledgerRemove(id: String) {
        try {
            val set = prefs.getStringSet("open", HashSet())?.toMutableSet() ?: return
            set.removeAll { it.startsWith(id + "|") }
            prefs.edit().putStringSet("open", set).apply()
        } catch (_: Exception) {}
    }

    init {
        if (ledgerCleaned.compareAndSet(false, true)) {
            try {
                val set = prefs.getStringSet("open", emptySet())?.toList() ?: emptyList()
                for (item in set) {
                    val parts = item.split("|", limit = 2)
                    if (parts.size < 2) continue
                    val token = parts[1]
                    try {
                        if (token.startsWith("content:")) {
                            appCtx.contentResolver.delete(android.net.Uri.parse(token), null, null)
                        } else {
                            java.io.File(token).delete()
                        }
                    } catch (_: Exception) {}
                }
                prefs.edit().remove("open").apply()
                LanFetcher.cleanupStale(appCtx)
            } catch (_: Exception) {}
        }
        // 监视线程：进程级单次启动。release() 不得 shutdown io，否则 watcherStarted=true 而线程已死。
        synchronized(watcherLock) {
            if (!watcherStarted) {
                watcherStarted = true
                watcherStop = false
                io.execute {
                    while (!watcherStop) {
                        try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                        if (watcherStop) break
                        val now = System.currentTimeMillis()
                        for ((id, h) in saveHandles.toMap()) {
                            if (now - h.lastWriteAt > 10 * 60 * 1000L) {
                                try { saveAbortInternal(id) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun saveOpen(name: String, size: Long): String {
        if (!originOk()) return "err:origin"
        return saveOpenInternal(name, size)
    }

    internal fun saveOpenInternal(name: String, size: Long): String {
        if (saveHandles.size >= 2) return "err:toomany"
        return try {
            val safe = LanFetcher.sanitizeName(name)
            val m = LanFetcher.mimeFromName(safe)
            if (size >= 0) {
                try {
                    val path = android.os.Environment.getExternalStorageDirectory()
                    val st = android.os.StatFs(path.path)
                    val avail = st.availableBytes
                    val need = (size * 1.05).toLong()
                    if (avail < need) return "err:nospace"
                } catch (_: Exception) {}
            }
            val id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            var uri: android.net.Uri? = null
            var file: java.io.File? = null
            var out: java.io.OutputStream? = null
            var ledgerToken = ""
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, m)
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                uri = appCtx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) return "err:io"
                out = appCtx.contentResolver.openOutputStream(uri)
                ledgerToken = uri.toString()
            } else {
                val has = android.os.Build.VERSION.SDK_INT < 23 ||
                    appCtx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!has) return "err:perm"
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                file = java.io.File(dir, safe + ".part")
                if (file.exists()) file.delete()
                out = java.io.FileOutputStream(file)
                ledgerToken = file.absolutePath
            }
            if (out == null) {
                uri?.let { try { appCtx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                return "err:io"
            }
            val h = SaveHandle(id, safe, size, m, uri, file, out)
            saveHandles[id] = h
            ledgerAdd(id, ledgerToken)
            holdService()
            "ok:$id"
        } catch (e: Exception) {
            "err:io"
        }
    }

    @android.webkit.JavascriptInterface
    fun saveWrite(handle: String, offset: Long, b64: String): String {
        if (!originOk()) return "err:origin"
        val h = saveHandles[handle] ?: return "err:badhandle"
        return synchronized(h.lock) {
            try {
                if (offset != h.written) return@synchronized "err:offset"
                val data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                if (data.size > 1024 * 1024) return@synchronized "err:big"
                try {
                    h.out?.write(data)
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("ENOSPC") || msg.contains("No space")) return@synchronized "err:nospace"
                    return@synchronized "err:io"
                }
                h.written += data.size
                h.lastWriteAt = System.currentTimeMillis()
                "ok"
            } catch (e: Exception) {
                "err:io"
            }
        }
    }

    internal fun saveWriteBytes(handle: String, data: ByteArray): String {
        val h = saveHandles[handle] ?: return "err:badhandle"
        return synchronized(h.lock) {
            try {
                if (data.size > 1024 * 1024) return@synchronized "err:big"
                try {
                    h.out?.write(data)
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    if (msg.contains("ENOSPC") || msg.contains("No space")) return@synchronized "err:nospace"
                    return@synchronized "err:io"
                }
                h.written += data.size
                h.lastWriteAt = System.currentTimeMillis()
                "ok"
            } catch (e: Exception) {
                "err:io"
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun saveClose(handle: String): String {
        if (!originOk()) return "err:origin"
        return saveCloseInternal(handle)
    }

    internal fun saveCloseInternal(handle: String): String {
        val h = saveHandles.remove(handle) ?: return "err:badhandle"
        return synchronized(h.lock) {
            try {
                if (h.size >= 0 && h.written != h.size) {
                    // cleanup incomplete
                    try { h.out?.close() } catch (_: Exception) {}
                    h.uri?.let { try { appCtx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                    h.file?.delete()
                    ledgerRemove(handle)
                    releaseService()
                    return@synchronized "err:size"
                }
                try {
                    h.out?.flush()
                    h.out?.close()
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    try { h.uri?.let { appCtx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
                    try { h.file?.delete() } catch (_: Exception) {}
                    ledgerRemove(handle)
                    releaseService()
                    return@synchronized if (msg.contains("ENOSPC") || msg.contains("No space")) "err:nospace" else "err:io"
                }
                h.out = null
                var finalName = h.name
                if (android.os.Build.VERSION.SDK_INT >= 29 && h.uri != null) {
                    val cv = android.content.ContentValues()
                    cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    appCtx.contentResolver.update(h.uri!!, cv, null, null)
                    try {
                        appCtx.contentResolver.query(h.uri!!, arrayOf(android.provider.MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                            val i = c.getColumnIndex(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                            if (c.moveToFirst() && i >= 0) finalName = c.getString(i) ?: finalName
                        }
                    } catch (_: Exception) {}
                } else if (h.file != null) {
                    val dest = java.io.File(h.file!!.parentFile, h.name)
                    if (dest.exists()) {
                        // simple unique
                        var n = 1
                        val dot = h.name.lastIndexOf('.')
                        val base = if (dot > 0) h.name.substring(0, dot) else h.name
                        val ext = if (dot > 0) h.name.substring(dot) else ""
                        var d = dest
                        while (d.exists() && n < 999) {
                            d = java.io.File(h.file!!.parentFile, "$base ($n)$ext")
                            n++
                        }
                        h.file!!.renameTo(d)
                        finalName = d.name
                    } else {
                        h.file!!.renameTo(dest)
                        finalName = dest.name
                    }
                    try {
                        android.media.MediaScannerConnection.scanFile(appCtx, arrayOf(java.io.File(h.file!!.parentFile, finalName).absolutePath), null, null)
                    } catch (_: Exception) {}
                }
                ledgerRemove(handle)
                releaseService()
                "ok:$finalName"
            } catch (e: Exception) {
                val msg = e.message ?: ""
                try { h.out?.close() } catch (_: Exception) {}
                try { h.uri?.let { appCtx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
                try { h.file?.delete() } catch (_: Exception) {}
                ledgerRemove(handle)
                releaseService()
                if (msg.contains("ENOSPC") || msg.contains("No space")) "err:nospace" else "err:io"
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun saveAbort(handle: String): String {
        if (!originOk()) return "err:origin"
        return saveAbortInternal(handle)
    }

    internal fun saveAbortInternal(handle: String): String {
        val h = saveHandles.remove(handle) ?: return "ok"
        synchronized(h.lock) {
            try { h.out?.close() } catch (_: Exception) {}
            try { h.uri?.let { appCtx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
            try { h.file?.delete() } catch (_: Exception) {}
        }
        ledgerRemove(handle)
        releaseService()
        return "ok"
    }

    internal fun entryOf(id: String): Entry? = table[id]

    fun isBusy(): Boolean {
        return server?.isRunning() == true || fetcher?.isActive() == true || saveHandles.isNotEmpty()
    }

    fun release() {
        for (id in saveHandles.keys.toList()) {
            try { saveAbortInternal(id) } catch (_: Exception) {}
        }
        try { server?.stop() } catch (_: Exception) {}
        try { fetcher?.cancel() } catch (_: Exception) {}
        serviceHold.set(0)
        try { LanService.stop(appCtx) } catch (_: Exception) {}
    }

    companion object {
        const val REQ_LAN_FILES = 5
        private val ledgerCleaned = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var watcherStop = false
        @Volatile private var watcherStarted = false
        private val watcherLock = Any()
    }
}
