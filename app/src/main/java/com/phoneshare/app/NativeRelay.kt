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
 * 薄封装 RelaySession；事件经 NativeLan.emit → window.__nativeLan
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
    @Volatile private var pendingRate: Int = 2048
    @Volatile private var lastVer: Int = -1
    @Volatile private var lastOriginDenyHost: String? = null
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
        if (host.isEmpty() || allowed.isEmpty()) {
            if (lastOriginDenyHost != host) {
                lastOriginDenyHost = host
                Logx.add("NativeRelay origin 拒绝: host=$host allowed=$allowed")
            }
            return false
        }
        val ok = allowed.any { a -> host == a || host.endsWith(".$a") }
        if (!ok) {
            if (lastOriginDenyHost != host) {
                lastOriginDenyHost = host
                Logx.add("NativeRelay origin 拒绝: host=$host allowed=$allowed")
            }
        } else {
            lastOriginDenyHost = null
        }
        return ok
    }

    private fun emitLan(obj: JSONObject) {
        // 复用 NativeLan.emit（只转义 U+2028/2029，不翻倍反斜杠；带 pending 队列）
        try {
            nativeLan.emit(obj)
        } catch (_: Throwable) {}
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

    @JavascriptInterface
    fun version(): Int {
        val ok = originOk()
        val v = if (ok) 1 else 0
        if (v != lastVer) {
            lastVer = v
            Logx.add("NativeRelay.version origin=$ok")
        }
        return v
    }

    @JavascriptInterface
    fun open(req: String, iceJson: String, role: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        if (session?.isBusy() == true) return """{"ok":false,"err":"busy"}"""
        currentReq = req
        val r = if (role == "r") "r" else "s"
        return try {
            holdOnce()
            val ice = parseIce(iceJson)
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            boundNetwork = if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork else null
            val holder = arrayOf<RelaySession?>(null)
            val sess = RelaySession(
                role = r,
                ev = object : RelayEvents {
                    private fun stillCurrent(): Boolean =
                        req == currentReq && session === holder[0]

                    override fun onReady(addr: String, keyB64: String, via: String) {
                        emitLan(JSONObject().put("e", "relayReady").put("req", req).put("addr", addr).put("key", keyB64).put("via", via))
                    }
                    override fun onOpen(rttMs: Int) {
                        emitLan(JSONObject().put("e", "relayOpen").put("req", req).put("rtt", rttMs))
                    }
                    override fun onFail(reason: String) {
                        Logx.add("NativeRelay onFail t=" + System.currentTimeMillis() + " reason=" + reason)
                        emitLan(JSONObject().put("e", "relayFail").put("req", req).put("reason", reason))
                        if (stillCurrent()) cleanupSession()
                    }
                    override fun onClosed(why: String) {
                        Logx.add("NativeRelay onClosed t=" + System.currentTimeMillis() + " why=" + why)
                        emitLan(JSONObject().put("e", "relayClosed").put("req", req).put("why", why))
                        if (stillCurrent()) cleanupSession()
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
                },
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
            holder[0] = sess
            session = sess
            registerNet(sess)
            sess.setRate(pendingRate)
            sess.open(ice)
            """{"ok":true}"""
        } catch (e: Exception) {
            cleanupSession()
            """{"ok":false,"err":"io"}"""
        }
    }

    @JavascriptInterface
    fun connect(req: String, peerJson: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        if (req != currentReq) return """{"ok":false,"err":"stale"}"""
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
        if (req != currentReq) return """{"ok":false,"err":"stale"}"""
        val sess = session ?: return """{"ok":false,"err":"idle"}"""
        return if (sess.sendMsg(text)) """{"ok":true}""" else """{"ok":false,"err":"too_long"}"""
    }

    @JavascriptInterface
    fun sendFile(req: String, fid: String, fileId: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        if (req != currentReq) return """{"ok":false,"err":"stale"}"""
        val sess = session ?: return """{"ok":false,"err":"idle"}"""
        val entry = nativeLan.entryOf(fileId)
        if (entry == null) {
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "文件不存在"))
            return """{"ok":true}"""
        }
        var size = entry.size
        if (size <= 0L) {
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
                    ins = appCtx.contentResolver.openInputStream(uri) ?: return -1
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
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "通道还没连通"))
        }
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun recvFile(req: String, fid: String, name: String, size: Long): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        if (req != currentReq) return """{"ok":false,"err":"stale"}"""
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
            emitLan(JSONObject().put("e", "relayDone").put("req", req).put("fid", fid).put("ok", false).put("msg", "通道还没连通"))
        }
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun cancel(req: String, fid: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        if (req != currentReq) return """{"ok":false,"err":"stale"}"""
        session?.cancel(fid)
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun close(req: String): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        if (req != currentReq) return """{"ok":true}"""
        try { session?.close() } catch (_: Exception) {}
        cleanupSession()
        return """{"ok":true}"""
    }

    @JavascriptInterface
    fun setRate(kbps: Int): String {
        if (!originOk()) return """{"ok":false,"err":"origin"}"""
        pendingRate = kbps.coerceIn(64, 20480)
        session?.setRate(pendingRate)
        return """{"ok":true}"""
    }

    fun isBusy(): Boolean = session?.isBusy() == true

    fun release() {
        try { session?.close() } catch (_: Exception) {}
        cleanupSession()
    }
}
