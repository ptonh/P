package com.phoneshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/**
 * 缺权限时通知并跳转设置（control / overlay）
 */
object PermNotifier {
    private const val CHANNEL = "perm_hint"
    private const val ID_CONTROL = 3001
    private const val ID_OVERLAY = 3002
    private const val THROTTLE_MS = 60_000L

    @Volatile private var lastControl = 0L
    @Volatile private var lastOverlay = 0L

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "权限提醒", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
    }

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

        ensureChannel(app)
        try {
            if (kind == "control") {
                showControl(app)
            } else {
                showOverlay(app)
            }
        } catch (e: Exception) {
            Logx.add("PermNotifier: " + e.message)
            try {
                Toast.makeText(app, "请到设置开启相关权限", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
        }
    }

    private fun showControl(ctx: Context) {
        val acc = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val accPi = PendingIntent.getActivity(
            ctx, 301, acc,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:" + ctx.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val appPi = PendingIntent.getActivity(
            ctx, 302, appInfo,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val appAction = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_info_details),
            "应用信息",
            appPi
        ).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("对方想控制你的手机")
            .setContentText("需要开启无障碍权限，点此去开启")
            .setContentIntent(accPi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .addAction(appAction)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_CONTROL, n)
    }

    private fun showOverlay(ctx: Context) {
        val ov = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + ctx.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 303, ov,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("对方在标记你的屏幕")
            .setContentText("需要开启悬浮窗权限，点此去开启")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_OVERLAY, n)
    }

    fun clearIfGranted(ctx: Context) {
        val app = ctx.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
