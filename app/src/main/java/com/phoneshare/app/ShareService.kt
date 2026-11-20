package com.phoneshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** 前台服务：Android 要求屏幕采集必须放在 mediaProjection 类型的前台服务里。 */
class ShareService : Service() {
    companion object {
        const val ACTION_START = "com.phoneshare.app.START"
        const val ACTION_STOP = "com.phoneshare.app.STOP"
        const val ACTION_REVOKE = "com.phoneshare.app.REVOKE"
        private const val CHANNEL_ID = "share"
        private const val NOTI_ID = 1
        @Volatile
        var running = false
    }

    private var session: RtcSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    // 授权状态变化时更新通知（授权期间通知栏显示“有人正在控制你的手机”并带“撤销控制”按钮）
    private val stateListener: (String) -> Unit = { st ->
        handler.post {
            if (running) {
                try {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTI_ID, buildNotification(st == "granted"))
                } catch (e: Throwable) {
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REVOKE) {
            ControlHandler.revokeByUser()
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            val s = session
            if (s != null) {
                s.stop("已手动停止")
            } else {
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // 必须尽快进入前台
        startFg()

        if (session != null) {
            Logx.add("已经在共享中")
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || intent == null) {
            Logx.add("没有拿到屏幕共享授权，已停止")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val cfg = Cfg(
            base = intent.getStringExtra("base") ?: "",
            auth = intent.getStringExtra("auth") ?: "",
            room = intent.getStringExtra("room") ?: "",
            shortSide = intent.getIntExtra("short", 720),
            maxKbps = intent.getIntExtra("kbps", 2500)
        )
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phoneshare:share")
            wl.acquire(6 * 60 * 60 * 1000L)
            wakeLock = wl
        } catch (e: Throwable) {
        }
        running = true
        val s = RtcSession(applicationContext, cfg, data) { reason ->
            Logx.add("共享结束：" + reason)
            handler.post {
                running = false
                session = null
                try { wakeLock?.release() } catch (e: Throwable) {}
                wakeLock = null
                stopForegroundCompat()
                stopSelf()
            }
        }
        session = s
        ControlHandler.addStateListener(stateListener)
        s.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ControlHandler.removeStateListener(stateListener)
        session?.stop("服务被关闭")
        running = false
        try { wakeLock?.release() } catch (e: Throwable) {}
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun buildNotification(granted: Boolean): Notification {
        val stopIntent = Intent(this, ShareService::class.java).setAction(ACTION_STOP)
        val pi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val revokeIntent = Intent(this, ShareService::class.java).setAction(ACTION_REVOKE)
        val revokePi = PendingIntent.getService(this, 2, revokeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "停止共享", pi
        ).build()
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(if (granted) "有人正在控制你的手机" else "正在共享屏幕")
            .setContentText(if (granted) "点“撤销控制”可立即收回" else "点击打开应用，或点“停止共享”")
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(stopAction)
        if (granted) {
            val revokeAction = Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_revert), "撤销控制", revokePi
            ).build()
            b.addAction(revokeAction)
        }
        return b.build()
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "屏幕共享", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification(false)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, n)
        }
    }
}
