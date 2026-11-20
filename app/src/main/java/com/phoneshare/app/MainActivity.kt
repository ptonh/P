package com.phoneshare.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val REQ_CAPTURE = 1001
    private val REQ_NOTI = 1002

    private lateinit var etUrl: EditText
    private lateinit var etAuth: EditText
    private lateinit var etRoom: EditText
    private lateinit var spQuality: Spinner
    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tvStatus: TextView
    private val stateListener: (String) -> Unit = { _ -> runOnUiThread { updateStatus() } }

    // 清晰度：短边像素（0 = 原始）、码率上限 kbps
    private val qualityNames = listOf("720p（省流，推荐）", "1080p", "原始分辨率")
    private val qualityShort = listOf(720, 1080, 0)
    private val qualityKbps = listOf(2500, 5000, 8000)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun field(hint: String, pwd: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setSingleLine(true)
        e.inputType = if (pwd) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        e.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return e
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        b.layoutParams = lp
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本界面显示配对码：加 FLAG_SECURE，屏幕共享里这个界面会变黑，观看者读不到配对码
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(12))

        val title = TextView(this)
        title.text = "手机共享屏幕"
        title.textSize = 20f
        root.addView(title)

        val hint = TextView(this)
        hint.text = "用法：手机先点“开始共享”，再用电脑或另一台手机打开网页，进入同一个房间。"
        hint.textSize = 13f
        root.addView(hint)

        etUrl = field("网页地址（可直接粘贴带 ?auth=…&room=… 的整个链接）", false)
        etAuth = field("密钥 auth（链接里已有就不用填）", true)
        etRoom = field("房间号（链接里已有就不用填）", false)
        etUrl.setText(prefs.getString("url", ""))
        etAuth.setText(prefs.getString("auth", ""))
        etRoom.setText(prefs.getString("room", ""))
        root.addView(etUrl)
        root.addView(etAuth)
        root.addView(etRoom)

        spQuality = Spinner(this)
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualityNames)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spQuality.adapter = ad
        spQuality.setSelection(prefs.getInt("quality", 0).coerceIn(0, qualityNames.size - 1))
        root.addView(spQuality)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(button("开始共享") { onStartClicked() })
        row.addView(button("停止") { startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP)) })
        row.addView(button("复制日志") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Logx.all()))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        })
        root.addView(row)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(button("开启控制权限") { ControlHandler.openSettings(this) })
        row2.addView(button("撤销控制") { ControlHandler.revokeByUser(); updateStatus() })
        row2.addView(button("重置控制锁") { ControlHandler.resetLock(); updateStatus() })
        root.addView(row2)

        val tip = TextView(this)
        tip.text = "安卓13以上通过浏览器/Release安装的应用，开启无障碍前请先到「应用信息 → 右上角 ⋮ → 允许受限制的设置」。"
        tip.textSize = 12f
        root.addView(tip)

        tvStatus = TextView(this)
        tvStatus.textSize = 13f
        root.addView(tvStatus)


        tvLog = TextView(this)
        tvLog.textSize = 12f
        tvLog.typeface = android.graphics.Typeface.MONOSPACE
        tvLog.setTextIsSelectable(true)
        scroll = ScrollView(this)
        scroll.addView(tvLog)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(scroll)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ControlHandler.addStateListener(stateListener)
        tvLog.text = Logx.all() + "\n"
        scrollToEnd()
        updateStatus()
        Logx.listener = { line ->
            runOnUiThread {
                tvLog.append(line + "\n")
                scrollToEnd()
                updateStatus()
            }
        }
    }

    override fun onPause() {
        Logx.listener = null
        ControlHandler.removeStateListener(stateListener)
        super.onPause()
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        sb.append(if (ShareService.running) "状态：共享中" else "状态：未共享")
        val code = RtcSession.pairCode
        if (code.isNotEmpty()) {
            sb.append("\n配对码：").append(code)
        }
        if (ControlHandler.isGranted()) {
            sb.append("\n控制：已授权（可随时点“撤销控制”）")
        } else {
            sb.append("\n控制：未授权")
        }
        if (ControlHandler.isLocked()) {
            sb.append("\n控制已锁定（输错次数过多），点“重置控制锁”后才能再次授权")
        }
        if (!ControlHandler.serviceEnabled()) {
            sb.append("\n无障碍服务未开启")
        }
        tvStatus.text = sb.toString()
        tvStatus.textSize = if (code.isNotEmpty()) 18f else 13f
    }

    private fun scrollToEnd() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ----- 开始 -----

    private var pendingBase = ""
    private var pendingAuth = ""
    private var pendingRoom = ""

    private fun onStartClicked() {
        if (ShareService.running) {
            Toast.makeText(this, "已经在共享中", Toast.LENGTH_SHORT).show()
            return
        }
        val raw = etUrl.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "请填网页地址", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(if (raw.startsWith("http")) raw else "https://" + raw)
        val host = uri.authority
        if (host.isNullOrEmpty()) {
            Toast.makeText(this, "网页地址不对", Toast.LENGTH_SHORT).show()
            return
        }
        pendingBase = (uri.scheme ?: "https") + "://" + host
        pendingAuth = uri.getQueryParameter("auth") ?: etAuth.text.toString().trim()
        pendingRoom = uri.getQueryParameter("room") ?: etRoom.text.toString().trim()
        if (pendingRoom.isEmpty()) {
            Toast.makeText(this, "请填房间号", Toast.LENGTH_SHORT).show()
            return
        }
        etAuth.setText(pendingAuth)
        etRoom.setText(pendingRoom)
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("url", raw)
            .putString("auth", pendingAuth)
            .putString("room", pendingRoom)
            .putInt("quality", spQuality.selectedItemPosition)
            .apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
        } else {
            launchCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTI) launchCapture() // 不管有没有允许通知，都继续（没有通知也能共享，只是看不到通知栏提示）
    }

    private fun launchCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 安卓 14 以上系统弹窗默认可以选“共享单个应用”，只选一个应用时共享的是那个应用的窗口，应用一关就停；
        // 这里要求只能共享整个屏幕（低版本系统本来就只有整个屏幕）
        val captureIntent: Intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Logx.add("已取消屏幕共享授权")
            return
        }
        val q = spQuality.selectedItemPosition.coerceIn(0, qualityNames.size - 1)
        val svc = Intent(this, ShareService::class.java)
            .setAction(ShareService.ACTION_START)
            .putExtra("data", data)
            .putExtra("base", pendingBase)
            .putExtra("auth", pendingAuth)
            .putExtra("room", pendingRoom)
            .putExtra("short", qualityShort[q])
            .putExtra("kbps", qualityKbps[q])
        Logx.add("开始共享：房间「" + pendingRoom + "」，清晰度 " + qualityNames[q])
        startForegroundService(svc)
    }
}
