package com.phoneshare.app

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 远程控制的“门卫”：校验配对码、授权、限速，再把手势交给 ControlService 执行。
 * 契约 C4 / C5（v1.2）：消息和函数名逐字对应，不要改。
 * 配对码不写进日志。
 *
 * v1.2 相对 v1.1 新增（不改已有函数）：
 *  isGranted() / isLocked() / revokeByUser() / resetLock() / addStateListener() / removeStateListener()
 *  累计错误上限（跨会话）、授权空闲超时、消息长度上限。
 */
object ControlHandler {
    private const val MAX_FAILS_SESSION = 3       // 每个会话最多输错次数
    private const val MAX_FAILS_TOTAL = 9         // 跨会话累计输错次数上限，到了必须在手机上点“重置控制锁”
    private const val IDLE_MS = 30 * 60 * 1000L   // C4 v2：授权后空闲 30 分钟自动撤销
    private const val MAX_MSG_LEN = 200           // 超长消息直接丢弃

    private val rnd = SecureRandom()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 契约 C5：只传 "idle" 或 "granted"。 */
    @Volatile
    var onStateChange: ((String) -> Unit)? = null

    // 以下状态都在 lock 里读写
    private var code = ""
    private var granted = false
    private var attempts = 0
    private var totalFails = 0
    private var locked = false
    private var winStart = 0L
    private var winCount = 0
    private var lastNoAuthReply = 0L
    private var lastActivity = 0L

    // 当前会话的回复函数（会话结束时清掉），用于撤销/服务丢失时通知网页
    @Volatile
    private var replier: ((String) -> Unit)? = null

    private val idleCheck = object : Runnable {
        override fun run() {
            val st = synchronized(lock) {
                if (!granted) 0
                else if (SystemClock.elapsedRealtime() - lastActivity >= IDLE_MS) 2
                else 1
            }
            when (st) {
                0 -> {
                }
                1 -> main.postDelayed(this, 30000L)
                else -> {
                    if (revoke()) {
                        Logx.add("控制：长时间没有操作，已自动撤销")
                        replier?.invoke("rc:rev")
                        notifyState("idle")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 契约 C5

    /** 新会话开始：生成 6 位配对码（只给界面显示），重置授权；若累计输错已到上限，本会话直接锁定。 */
    fun onSessionStart(): String {
        val c = String.format(Locale.ROOT, "%06d", rnd.nextInt(1000000))
        synchronized(lock) {
            code = c
            granted = false
            attempts = 0
            locked = totalFails >= MAX_FAILS_TOTAL
            winStart = 0L
            winCount = 0
            lastNoAuthReply = 0L
        }
        return c
    }

    /** 会话结束：撤销授权，清掉配对码和回复函数。 */
    fun onSessionEnd() {
        val was = revoke()
        synchronized(lock) { code = "" }
        replier = null
        if (was) notifyState("idle")
    }

    fun serviceEnabled(): Boolean = ControlService.instance != null

    fun openSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** 包1只会转交以 "rc:" 开头的消息；会在 WebRTC 线程被调用。 */
    fun onMessage(msg: String, reply: (String) -> Unit) {
        if (!msg.startsWith("rc:")) return
        if (msg.length > MAX_MSG_LEN) return
        replier = reply
        val body = msg.substring(3)
        when {
            body.startsWith("auth:") -> handleAuth(body.substring(5).trim(), reply)
            body == "begin" -> {
                // C4 v2：打开控制入口；已授权直接 grant，未授权 noauth（页面保持输码框）
                val g = synchronized(lock) { granted }
                if (g) reply("rc:grant") else reply("rc:deny:noauth")
            }
            body == "end" -> {
                // C4 v2：只表示对方暂时退出控制界面，不撤销授权
            }
            else -> handleCommand(body, reply)
        }
    }

    // ------------------------------------------------------------------ v1.2 新增

    fun isGranted(): Boolean = synchronized(lock) { granted }

    fun isLocked(): Boolean = synchronized(lock) { locked || totalFails >= MAX_FAILS_TOTAL }

    /** 手机端撤销授权（界面按钮、通知栏按钮调用），并通知网页 rc:rev。 */
    fun revokeByUser() {
        if (revoke()) {
            Logx.add("控制：手机端撤销了授权")
            replier?.invoke("rc:rev")
            notifyState("idle")
        }
    }

    /** 手机端重置“累计输错”锁（界面按钮调用）。本会话也一并解锁、恢复 3 次机会。 */
    fun resetLock() {
        val g = synchronized(lock) {
            totalFails = 0
            attempts = 0
            locked = false
            granted
        }
        Logx.add("控制：已重置控制锁")
        notifyState(if (g) "granted" else "idle")
    }

    fun addStateListener(l: (String) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeStateListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** 无障碍服务被系统或用户关掉了（ControlService 调用，不属于契约）。 */
    fun onServiceLost() {
        if (revoke()) {
            Logx.add("控制：无障碍服务已关闭，授权被撤销")
            replier?.invoke("rc:rev")
            notifyState("idle")
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun handleAuth(input: String, reply: (String) -> Unit) {
        val r = synchronized(lock) {
            when {
                locked -> Pair("rc:deny:locked", false)
                !serviceEnabled() -> Pair("rc:deny:noperm", false)
                granted -> Pair("rc:grant", false)
                code.isNotEmpty() && constEq(code, input) -> {
                    granted = true
                    totalFails = 0
                    lastActivity = SystemClock.elapsedRealtime()
                    Pair("rc:grant", true)
                }
                else -> {
                    attempts++
                    totalFails++
                    if (attempts >= MAX_FAILS_SESSION || totalFails >= MAX_FAILS_TOTAL) {
                        locked = true
                        Pair("rc:deny:locked", false)
                    } else {
                        Pair("rc:deny:wrongcode", false)
                    }
                }
            }
        }
        reply(r.first)
        if (r.second) {
            Logx.add("控制：配对码正确，已授权")
            main.removeCallbacks(idleCheck)
            main.postDelayed(idleCheck, 30000L)
            notifyState("granted")
        } else if (r.first != "rc:grant") {
            Logx.add("控制：授权被拒绝（" + r.first.substring(8) + "）")
        }
    }

    private fun handleCommand(body: String, reply: (String) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        var allowed = false
        var needDeny = false
        synchronized(lock) {
            if (!granted) {
                if (now - lastNoAuthReply >= 1000L) {
                    lastNoAuthReply = now
                    needDeny = true
                }
            } else {
                if (now - winStart >= 1000L) {
                    winStart = now
                    winCount = 0
                }
                winCount++
                allowed = winCount <= 30
                if (allowed) lastActivity = now
            }
        }
        if (needDeny) {
            reply("rc:deny:noauth")
            return
        }
        if (!allowed) return

        val idx = body.indexOf(':')
        val cmd = if (idx < 0) body else body.substring(0, idx)
        val arg = if (idx < 0) "" else body.substring(idx + 1)

        val svc = ControlService.instance
        if (svc == null) {
            if (revoke()) notifyState("idle")
            reply("rc:rev")
            return
        }

        when (cmd) {
            "tap" -> {
                val v = nums(arg, 2) ?: return
                main.post { svc.tap(v[0], v[1]) }
            }
            "long" -> {
                val v = nums(arg, 2) ?: return
                main.post { svc.longPress(v[0], v[1]) }
            }
            "swipe" -> {
                val v = nums(arg, 5) ?: return
                val ms = v[4].toLong()
                main.post { svc.swipe(v[0], v[1], v[2], v[3], ms) }
            }
            "key" -> {
                val k = arg.trim()
                if (k == "back" || k == "home" || k == "recents" || k == "notifications") {
                    main.post { svc.globalKey(k) }
                }
            }
            else -> {
            }
        }
    }

    /** 撤销授权；返回之前是否处于授权状态。 */
    private fun revoke(): Boolean {
        val was = synchronized(lock) {
            val w = granted
            granted = false
            w
        }
        main.removeCallbacks(idleCheck)
        return was
    }

    private fun notifyState(s: String) {
        try {
            onStateChange?.invoke(s)
        } catch (e: Throwable) {
        }
        for (l in listeners) {
            try {
                l(s)
            } catch (e: Throwable) {
            }
        }
    }

    private fun constEq(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** 解析 "a,b,c" 为 n 个有限小数，不合法返回 null。 */
    private fun nums(s: String, n: Int): DoubleArray? {
        val parts = s.split(",")
        if (parts.size != n) return null
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val d = parts[i].trim().toDoubleOrNull() ?: return null
            if (d.isNaN() || d.isInfinite()) return null
            out[i] = d
        }
        return out
    }
}
