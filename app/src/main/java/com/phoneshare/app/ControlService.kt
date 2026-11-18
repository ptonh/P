package com.phoneshare.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：执行点击、长按、滑动和系统按键。
 * 需要用户在系统“无障碍”设置里手动开启（安卓 13+ 通过浏览器安装的应用还要先“允许受限制的设置”）。
 * 只由 ControlHandler 在授权通过后调用。
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logx.add("无障碍服务已开启")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ControlHandler.onServiceLost()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        ControlHandler.onServiceLost()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 手势（x、y 为 0～1，相对整屏）

    fun tap(x: Double, y: Double) {
        stroke(x, y, x, y, 50L, false)
    }

    fun longPress(x: Double, y: Double) {
        stroke(x, y, x, y, 600L, false)
    }

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Long) {
        stroke(x1, y1, x2, y2, ms.coerceIn(50L, 2000L), true)
    }

    fun globalKey(name: String) {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return
        }
        performGlobalAction(action)
    }

    private fun stroke(x1: Double, y1: Double, x2: Double, y2: Double, durationMs: Long, isSwipe: Boolean) {
        try {
            val size = screenSize()
            val w = size.first
            val h = size.second
            val px1 = (x1.coerceIn(0.0, 1.0) * (w - 1)).toFloat()
            val py1 = (y1.coerceIn(0.0, 1.0) * (h - 1)).toFloat()
            val px2 = (x2.coerceIn(0.0, 1.0) * (w - 1)).toFloat()
            val py2 = (y2.coerceIn(0.0, 1.0) * (h - 1)).toFloat()
            val path = Path()
            path.moveTo(px1, py1)
            if (isSwipe) {
                // 起点终点相同的“滑动”挪 1 像素，避免系统把它当成无效路径
                if (px1 == px2 && py1 == py2) path.lineTo(px2 + 1f, py2) else path.lineTo(px2, py2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Throwable) {
            Logx.add("手势执行失败: " + e.message)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }
}
