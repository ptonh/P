package com.phoneshare.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 调试页：仅加载 assets/lan_test.html，独立放行 file 来源。
 */
class LanTestActivity : Activity() {
    private var webView: WebView? = null
    private var nativeLan: NativeLan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this)
        webView = wv
        setContentView(wv)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.allowFileAccess = true
        s.domStorageEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return true
                // 只允许 file:///android_asset/
                return !u.startsWith("file:///android_asset/")
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return true
                return !u.startsWith("file:///android_asset/")
            }
        }
        nativeLan = NativeLan(this, wv, object : NativeLan.HostsProvider {
            override fun allowedHosts(): Set<String> = setOf("file")
        }).also {
            it.setDebugAllowFile(true)
            wv.addJavascriptInterface(it, "NativeLan")
        }
        wv.loadUrl("file:///android_asset/lan_test.html")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        nativeLan?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        try { nativeLan?.release() } catch (_: Throwable) {}
        webView?.destroy()
        super.onDestroy()
    }
}
