package com.phoneshare.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.phoneshare.app.relay.IceServer
import com.phoneshare.app.relay.RelayEvents
import com.phoneshare.app.relay.RelaySession
import com.phoneshare.app.relay.RudpSink
import com.phoneshare.app.relay.RudpSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 薄封装 RelaySession → 事件经 NativeLan.emit 路径（window.__nativeLan）
 */
class NativeRelay(
    private val activity: Context,
    private val webView: WebView,
    private val hostsProvider: NativeLan.HostsProvider,
    private val nativeLan: NativeLan
) {
    private val appCtx = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var mainHost: String = ""
    @Volatile private var currentReq: String = ""
    @Volatile private var session: RelaySession? = null
    @Volatile private var boundNetwork: Network? = null
    private var networkCb: ConnectivityManager.NetworkCallback? = null
    private val held = AtomicBoolean(false)

    fun setMainUrl(url: String?) {
        mainHost = hostOf(url)
    }

    private fun hostOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun originOk(): Boolean {
        val host = mainHost
        val allowed = hostsProvider.allowedHosts().map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (host.isEmpty() || allowed.isEmpty()) return false
        return allowed.any { a -> host == a || host.endsWith(".$a") }
    }

    /** 统一走 window.__nativeLan */
    private fun emitLan(obj: JSONObject) {
        val lit = obj.toString()
            .replace("\\", "\\\\")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        main.post {
            try {
                val js = "(function(){if(window.__nativeLan){window.__nativeLan($lit);return 1}return 0})()"
                webView.evaluateJavascript(js, null)
            } catch (_: Throwable) {}
        }
    }

    private fun holdOnce() {
        if (held.compareAndSet(false, true)) {
            try { nativeLan.holdService() } catch (_: Exception) {}
        }
    }

    private fun releaseOnce() {
        if (held.compareAndSet(true, false)) {
            try { nativeLan.releaseService() } catch (_: Exception) {}
        }
    }

    private fun cleanupSession() {
        unregisterNet()
        releaseOnce()
        session = null
    }

    private fun registerNet(sess: RelaySession) {
        if (Build.VERSION.SDK_INT < 24) return
        try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            boundNetwork = cm.activeNetwork
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    if (boundNetwork == null || network == boundNetwork) {
                        try { sess.onNetworkChanged() } catch (_: Exception) {}
                    }
                }
                override fun onAvailable(network: Network) {
                    if (boundNetwork != null && network != boundNetwork) {
                        try { sess.onNetworkChanged() } catch (_: Exception) {}
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCb = cb
        } catch (_: Exception) {}
    }

    private fun unregisterNet() {
        try {
            if (networkCb != null) {
                val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(networkCb!!)
            }
        } catch (_: Exception) {}
        networkCb = null
        boundNetwork = null
    }

    private fun parseIce(iceJson: String): List<IceServer> {
        val arr = JSONArray(iceJson)
        val out = ArrayList<IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urls = ArrayList<String>()
            val ua = o.optJSONArray("urls")
            if (ua != null) {
                for (j in 0 until ua.length()) urls.add(ua.optString(j, ""))
            } else {
                val u = o.optString("urls", "")
                if (u.isNotEmpty()) urls.add(u)
            }
            out.add(
                IceServer(
                    urls.filter { it.isNotEmpty() },
                    o.optString("username", ""),
                    o.optString("credential", "")
                )
            )
        }
        return out
    }

    private fun eventsFor(req: String) = object : RelayEvents {
        override fun onReady(addr: String, keyB64: String) {
            emitLan(JSONObject().put("e", "relayReady").put("req", req).put("addr", addr).put("key", keyB64))
        }
        override fun onOpen(rttMs: Int) {
            emitLan(JSONObject().put("e", "relayOpen").put("req", req).put("rtt", rttMs))
        }
        override fun onFail(reason: String) {
            emitLan(JSONObject().put("e", "relayFail").put("req", req).put("reason", reason))
            cleanupSession()
        }
        override fun onClosed(why: String) {
            emitLan(JSONObject().put("e", "relayClosed").put("req", req).put("why", why))
            cleanupSession()
        }
        override fun onMsg(text: String) {
            emitLan(JSONObject().put("e", "relayMsg").put("req", req).put("text", text))
        }
        override fun onProg(fid: String, sending: Boolean, done: Long, total: Long, rateBps: Long, lossPct: Int, rttMs: Int) {
            emitLan(
                JSONObject().put("e", "relayProg").put("req", req).put("fid", fid)
                    .put("done", done).put("total", total).put("rate", rateBps)
                    .put("loss", lossPct).put("rtt", rttMs)
            )
        }
        override fun onDone(fid: String, sending: Boolean, ok: Boolean, msg: String) {
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", ok).put("msg", msg))
        }
        override fun onStat(sent: Long, recv: Long) {
            emitLan(JSONObject().put("e", "relayStat").put("req", req).put("sent", sent).put("recv", recv))
        }
    }

    @JavascriptInterface
    fun version(): Int {
        if (!originOk()) return 0
        return 1
    }

    @JavascriptInterface
    fun open(req: String, iceJson: String, role: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        if (session?.isBusy() == true) return """{"ok":false,"err":"busy"}"""
        currentReq = req
        val r = if (role == "r") "r" else "s"
        try {
            holdOnce()
            val ice = parseIce(iceJson)
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            boundNetwork = if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork else null
            val sess = RelaySession(
                role = r,
                ev = eventsFor(req),
                socketFactory = {
                    val s = DatagramSocket()
                    try {
                        if (Build.VERSION.SDK_INT >= 23) {
                            val net = boundNetwork ?: cm.activeNetwork
                            if (net != null) net.bindSocket(s)
                        }
                    } catch (_: Exception) {}
                    s
                },
                log = { /* no secrets */ }
            )
            session = sess
            registerNet(sess)
            sess.open(ice)
            return """{"ok":true}"""
        } catch (e: Exception) {
            cleanupSession()
            return """{"ok":false,"err":"io"}"""
        }
    }

    @JavascriptInterface
    fun connect(req: String, peerJson: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        val sess = session ?: return """{"ok":false,"err":"idle"}"""
        return try {
            val o = JSONObject(peerJson)
            val addr = o.optString("addr", "")
            val key = o.optString("key", "")
            sess.connect(addr, key)
            """{"ok":true}"""
        } catch (_: Exception) {
            """{"ok":false,"err":"bad_arg"}"""
        }
    }

    @JavascriptInterface
    fun sendMsg(req: String, text: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        val sess = session ?: return """{"ok":false,"err":"idle"}"""
        return if (sess.sendMsg(text)) """{"ok":true}""" else """{"ok":false,"err":"too_long"}"""
    }

    @JavascriptInterface
    fun sendFile(req: String, fid: String, fileId: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        val sess = session ?: return """{"ok":false,"err":"idle"}"""
        val entry = nativeLan.entryOf(fileId)
        if (entry == null) {
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "文件不存在"))
            return """{"ok":true}"""
        }
        var size = entry.size
        if (size <= 0L) {
            // 试读 1 字节：有数据 = 读不到大小；无数据 = 空文件
            try {
                appCtx.contentResolver.openInputStream(entry.uri)?.use { ins ->
                    val one = ByteArray(1)
                    val n = ins.read(one)
                    if (n > 0) {
                        emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "读不到文件大小"))
                        return """{"ok":true}"""
                    }
                    size = 0L
                } ?: run {
                    emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "文件不存在"))
                    return """{"ok":true}"""
                }
            } catch (_: Exception) {
                emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "读不到文件大小"))
                return """{"ok":true}"""
            }
        }
        val uri = entry.uri
        val src = object : RudpSource {
            private var ins: InputStream? = null
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                if (ins == null) {
                    ins = appCtx.contentResolver.openInputStream(uri)
                        ?: return -1
                }
                return try {
                    ins!!.read(buf, off, len)
                } catch (_: Exception) {
                    -1
                }
            }
            override fun close() {
                try { ins?.close() } catch (_: Exception) {}
                ins = null
            }
        }
        val ok = sess.sendFile(fid, size, src)
        if (!ok) {
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "上一个文件还没传完"))
        }
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun recvFile(req: String, fid: String, name: String, size: Long): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        val sess = session ?: return """{"ok":false,"err":"idle"}"""
        val open = nativeLan.saveOpenInternal(name, size)
        if (!open.startsWith("ok:")) {
            val msg = when {
                open.startsWith("err:toomany") -> "同时最多2个保存（含网页通道）"
                open.startsWith("err:nospace") -> "存储空间不足"
                open.startsWith("err:perm") -> "没有存储权限"
                else -> "保存失败"
            }
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", msg))
            return """{"ok":true}"""
        }
        val handle = open.removePrefix("ok:")
        val sink = object : RudpSink {
            override fun write(buf: ByteArray, off: Int, len: Int): String {
                val data = buf.copyOfRange(off, off + len)
                return nativeLan.saveWriteBytes(handle, data)
            }
            override fun finish(): String = nativeLan.saveCloseInternal(handle)
            override fun abort() {
                try { nativeLan.saveAbortInternal(handle) } catch (_: Exception) {}
            }
        }
        val ok = sess.recvFile(fid, size, sink)
        if (!ok) {
            try { nativeLan.saveAbortInternal(handle) } catch (_: Exception) {}
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "上一个文件还没传完"))
        }
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun cancel(req: String, fid: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        session?.cancel(fid)
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun close(req: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        try { session?.close() } catch (_: Exception) {}
        // close 本地主动不再回调 onClosed；仍做清理
        cleanupSession()
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun setRate(kbps: Int): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        session?.setRate(kbps)
        return """{"ok":true}"""
    }

    fun isBusy(): Boolean = session?.isBusy() == true

    /** 主线程调用：只调 session.close()，不碰 socket */
    fun release() {
        try { session?.close() } catch (_: Exception) {}
        cleanupSession()
    }
}
