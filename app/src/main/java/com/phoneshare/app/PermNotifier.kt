package com.phoneshare.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 缺权限时用消息渠道发通知，点击回 ChatActivity 再跳设置
 */
object PermNotifier {
    private const val ID_CONTROL = 3001
    private const val ID_OVERLAY = 3002
    private const val THROTTLE_MS = 60_000L
    private const val ACTION_PERM = "com.phoneshare.app.PERM"

    @Volatile private var lastControl = 0L
    @Volatile private var lastOverlay = 0L

    fun show(ctx: Context, kind: String) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        if (kind == "control") {
            if (now - lastControl < THROTTLE_MS) return
            lastControl = now
        } else if (kind == "overlay") {
            if (now - lastOverlay < THROTTLE_MS) return
            lastOverlay = now
        } else return

        try { Notifier.init(app) } catch (_: Throwable) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.deleteNotificationChannel("perm_hint")
            }
        } catch (_: Throwable) {}

        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            try {
                val msg = if (kind == "control")
                    "对方想控制你的手机，请开启无障碍权限"
                else
                    "对方在标记你的屏幕，请开启悬浮窗权限"
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            return
        }

        try {
            if (kind == "control") showControl(app) else showOverlay(app)
            Logx.add("权限通知：已发出 kind=$kind")
        } catch (e: Exception) {
            Logx.add("PermNotifier: " + e.message)
            try {
                Toast.makeText(app, "请到设置开启相关权限", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    private fun chatIntent(ctx: Context, kind: String): Intent {
        return Intent(ctx, ChatActivity::class.java).apply {
            action = ACTION_PERM
            putExtra("perm", kind)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
    }

    private fun showControl(ctx: Context) {
        val mainPi = PendingIntent.getActivity(
            ctx, 31, chatIntent(ctx, "control"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val appPi = PendingIntent.getActivity(
            ctx, 33, chatIntent(ctx, "appinfo"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Notifier.CH_MSG)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("对方想控制你的手机")
            .setContentText("需要开启无障碍权限，点此去开启")
            .setContentIntent(mainPi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .addAction(0, "应用信息", appPi)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_CONTROL, n)
    }

    private fun showOverlay(ctx: Context) {
        val mainPi = PendingIntent.getActivity(
            ctx, 32, chatIntent(ctx, "overlay"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Notifier.CH_MSG)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("对方在标记你的屏幕")
            .setContentText("需要开启悬浮窗权限，点此去开启")
            .setContentIntent(mainPi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_OVERLAY, n)
    }

    fun clearIfGranted(ctx: Context) {
        val app = ctx.applicationContext
        val nm = NotificationManagerCompat.from(app)
        if (ControlHandler.serviceEnabled()) {
            nm.cancel(ID_CONTROL)
            lastControl = 0L
        }
        if (MarkOverlay.canDraw(app)) {
            nm.cancel(ID_OVERLAY)
            lastOverlay = 0L
        }
    }
}
