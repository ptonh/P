package com.phoneshare.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/**
 * 页面桥：window.AndroidShare
 * 每个方法先检查主页面域名是否在放行名单，否则返回 "err:origin"。
 */
class ShareBridge(
    activity: Activity,
    webView: WebView,
    private val hostsProvider: HostsProvider
) {
    interface HostsProvider {
        fun allowedHosts(): Set<String>
    }

    private val actRef = WeakReference(activity)
    private val wvRef = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())
    private val pendingEvents = ArrayDeque<String>()
    private val maxPending = 200
    @Volatile private var pageReady = false
    /** 主框架域名，仅由主线程 setMainUrl 更新（桥线程禁止读 WebView.url） */
    @Volatile private var mainHost: String = ""

    private val stateListener: (String) -> Unit = { st ->
        dispatchEvent(JSONObject().put("t", "rcstate").put("state", st).toString())
    }

    companion object {
        const val REQ_PROJECTION = 4401
    }

    init {
        NativeShare.attach(activity.applicationContext) { jo ->
            dispatchEvent(jo.toString())
        }
        MarkOverlay.ensure(activity.applicationContext)
        ControlHandler.addStateListener(stateListener)
    }

    fun setMainUrl(url: String?) {
        mainHost = hostOf(url)
    }

    private fun originOk(): Boolean {
        val host = mainHost
        val allowed = hostsProvider.allowedHosts().map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (host.isEmpty() || allowed.isEmpty()) {
            Logx.add("ShareBridge origin 拒绝: host=$host allowed=$allowed")
            return false
        }
        val ok = allowed.any { a -> host == a || host.endsWith(".$a") }
        if (!ok) Logx.add("ShareBridge origin 拒绝: host=$host allowed=$allowed")
        return ok
    }

    private fun hostOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun badOrigin(): String = "err:origin"

    @JavascriptInterface
    fun ready(): String {
        if (!originOk()) return badOrigin()
        pageReady = true
        main.post {
            val batch = ArrayList<String>()
            synchronized(pendingEvents) {
                while (pendingEvents.isNotEmpty()) batch.add(pendingEvents.removeFirst())
            }
            for (s in batch) evalEvent(s)
        }
        return "ok"
    }

    @JavascriptInterface
    fun info(): String {
        if (!originOk()) return badOrigin()
        val o = JSONObject()
        o.put("v", 1)
        o.put("sdk", android.os.Build.VERSION.SDK_INT)
        var wvVer = 1
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val pkg = android.webkit.WebView.getCurrentWebViewPackage()
                val vn = pkg?.versionName
                if (vn != null) {
                    val maj = vn.split(".")[0].toIntOrNull()
                    if (maj != null) wvVer = maj
                }
            }
        } catch (_: Throwable) {}
        o.put("webview", wvVer)
        o.put("rc", ControlHandler.serviceEnabled())
        return o.toString()
    }

    @JavascriptInterface
    fun start(json: String): String {
        if (!originOk()) return badOrigin()
        val act = actRef.get() ?: return "err:noactivity"
        return try {
            val jo = JSONObject(json)
            NativeShare.prepareIce(jo)
            // JavascriptInterface 不在主线程，必须 post 后再弹系统录屏授权
            main.post {
                try {
                    val a = actRef.get() ?: return@post
                    val mpm = a.getSystemService(Activity.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    @Suppress("DEPRECATION")
                    val intent = mpm.createScreenCaptureIntent()
                    a.startActivityForResult(intent, REQ_PROJECTION)
                    Logx.add("ShareBridge: 已弹出录屏授权")
                } catch (e: Throwable) {
                    Logx.add("ShareBridge: 弹出录屏授权失败: " + e.message)
                    NativeShare.onProjectionResult(Activity.RESULT_CANCELED, null)
                }
            }
            "ok"
        } catch (e: Throwable) {
            "err:" + (e.message ?: "start")
        }
    }

    @JavascriptInterface
    fun offer(sid: String): String {
        if (!originOk()) return badOrigin()
        main.post { NativeShare.createOffer(sid) }
        return "ok"
    }

    @JavascriptInterface
    fun signal(json: String): String {
        if (!originOk()) return badOrigin()
        return try {
            val jo = JSONObject(json)
            main.post { NativeShare.onSignal(jo) }
            "ok"
        } catch (e: Throwable) {
            "err:" + (e.message ?: "signal")
        }
    }

    @JavascriptInterface
    fun resetPeer(): String {
        if (!originOk()) return badOrigin()
        main.post { NativeShare.resetPeer() }
        return "ok"
    }

    @JavascriptInterface
    fun stop(): String {
        if (!originOk()) return badOrigin()
        main.post { NativeShare.stop("user") }
        return "ok"
    }

    @JavascriptInterface
    fun rc(text: String): String {
        if (!originOk()) return badOrigin()
        main.post {
            ControlHandler.onMessage(text) { reply ->
                dispatchEvent(JSONObject().put("t", "rc").put("msg", reply).toString())
            }
        }
        return "ok"
    }

    @JavascriptInterface
    fun mark(text: String): String {
        if (!originOk()) return badOrigin()
        MarkOverlay.onMark(text)
        return "ok"
    }

    @JavascriptInterface
    fun rcState(): String {
        if (!originOk()) return badOrigin()
        val o = JSONObject()
        o.put("enabled", ControlHandler.serviceEnabled())
        o.put("granted", ControlHandler.isGranted())
        o.put("locked", ControlHandler.isLocked())
        return o.toString()
    }

    @JavascriptInterface
    fun openSettings(): String {
        if (!originOk()) return badOrigin()
        val act = actRef.get() ?: return "err:noactivity"
        main.post { ControlHandler.openSettings(act) }
        return "ok"
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PROJECTION) return
        Logx.add("ShareBridge: 录屏授权结果 code=" + resultCode + " data=" + (data != null))
        NativeShare.onProjectionResult(resultCode, data)
    }

    private fun dispatchEvent(jsonLiteral: String) {
        if (!pageReady) {
            synchronized(pendingEvents) {
                if (pendingEvents.size >= maxPending) pendingEvents.removeFirst()
                pendingEvents.addLast(jsonLiteral)
            }
            return
        }
        evalEvent(jsonLiteral)
    }

    private val loggedTypes = HashSet<String>()

    private fun evalEvent(json: String) {
        val wv = wvRef.get() ?: return
        val quoted = JSONObject.quote(json)
        // 视频页在聊天页 iframe(vcF) 内：主框架没有 __nativeShare，需转发 postMessage
        val js = "(function(){" +
            "if(!window.__nativeShare){" +
            "window.__nativeShare={event:function(s){" +
            "var F=document.getElementById('vcF');" +
            "if(F&&F.contentWindow){F.contentWindow.postMessage({source:'vcall',type:'native',s:String(s)},'*');return 1;}" +
            "return 0;}};" +
            "}" +
            "try{return window.__nativeShare.event(" + quoted + ");}catch(e){return -1;}" +
            "})()"
        main.post {
            try {
                wv.evaluateJavascript(js) { result ->
                    // 顶层视频页自有 __nativeShare 时可能返回 null，属正常
                    if (result == "0" || result == "-1") {
                        try {
                            val typ = JSONObject(json).optString("t", "?")
                            Logx.add("ShareBridge: 事件未送达 type=$typ result=$result")
                        } catch (_: Throwable) {
                            Logx.add("ShareBridge: 事件未送达 result=$result")
                        }
                    } else if (result != null && result != "null") {
                        try {
                            val typ = JSONObject(json).optString("t", "?")
                            if (loggedTypes.add(typ)) Logx.add("ShareBridge: 事件已送达 type=$typ")
                        } catch (_: Throwable) {}
                    }
                }
            } catch (e: Throwable) {
                Logx.add("ShareBridge eval: " + e.message)
            }
        }
    }

    fun release() {
        try { ControlHandler.removeStateListener(stateListener) } catch (_: Throwable) {}
        pageReady = false
        synchronized(pendingEvents) { pendingEvents.clear() }
    }
}

