package com.phoneshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 局域网传输前台服务：dataSync + WifiLock + PARTIAL_WAKE_LOCK
 */
class LanService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseLocks()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        startFg()
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "局域网传输", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("局域网传输中")
                .setContentText("正在通过局域网传文件")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("局域网传输中")
                .setContentText("正在通过局域网传文件")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NID, n)
            }
        } catch (e: Exception) {
            Logx.add("LanService startForeground: " + e.message)
            try { startForeground(NID, n) } catch (_: Exception) {}
        }
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phoneshare:lan").also {
                it.setReferenceCounted(false)
                it.acquire(60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Logx.add("LanService wakeLock: " + e.message)
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "phoneshare:lan").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (e: Exception) {
            Logx.add("LanService wifiLock: " + e.message)
        }
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "lan_transfer"
        private const val NID = 42
        const val ACTION_STOP = "com.phoneshare.app.LAN_STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, LanService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, LanService::class.java)) } catch (_: Exception) {}
        }
    }
}
