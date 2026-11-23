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
        return 1
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
            if (size < 0 || size > 4L * 1024 * 1024 * 1024) return """{"ok":false,"err":"bad_files"}"""
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
                if (server?.isRunning() != true) LanService.stop(appCtx)
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
        LanService.stop(appCtx)
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

    fun release() {
        try { server?.stop() } catch (_: Exception) {}
        try { fetcher?.cancel() } catch (_: Exception) {}
        LanService.stop(appCtx)
    }

    companion object {
        const val REQ_LAN_FILES = 5
    }
}
