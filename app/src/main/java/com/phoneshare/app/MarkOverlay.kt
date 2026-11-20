package com.phoneshare.app

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject

/**
 * 悬浮标记层：只画对方的标记，不挡触摸。
 * mk:laser|pen|arrow|circle|clear
 * 坐标 0～1 相对整屏；laser 约 5 秒无更新消失；其余保留到 clear。
 */
object MarkOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: MarkView? = null
    private var appCtx: Context? = null

    fun ensure(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun canDraw(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true
    }

    fun openPermissionSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.packageName)
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }

    fun onMark(msg: String) {
        if (!msg.startsWith("mk:")) return
        val body = msg.substring(3)
        val idx = body.indexOf(':')
        val kind = if (idx < 0) body else body.substring(0, idx)
        val payload = if (idx < 0) "" else body.substring(idx + 1)
        main.post {
            val ctx = appCtx ?: return@post
            if (!canDraw(ctx)) {
                Logx.add("标记：无悬浮窗权限，忽略")
                return@post
            }
            show(ctx)
            val v = view ?: return@post
            when (kind) {
                "clear" -> v.clearAll()
                "laser" -> v.setLaser(payload)
                "pen" -> v.addPen(payload)
                "arrow" -> v.addArrow(payload)
                "circle" -> v.addCircle(payload)
            }
        }
    }

    fun hide() {
        main.post {
            val w = wm
            val v = view
            if (w != null && v != null) {
                try { w.removeView(v) } catch (_: Throwable) {}
            }
            view = null
        }
    }

    private fun show(ctx: Context) {
        if (view != null) return
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val v = MarkView(ctx)
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            w.addView(v, lp)
            view = v
        } catch (e: Throwable) {
            Logx.add("标记层添加失败: " + e.message)
            view = null
        }
    }

    private class MarkView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
            alpha = 180
        }
        private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
        }

        private var laser: PointF? = null
        private var laserAt = 0L
        private val pens = ArrayList<Path>()
        private val arrows = ArrayList<FloatArray>() // x1,y1,x2,y2
        private val circles = ArrayList<FloatArray>() // cx,cy,r

        private val laserTimeout = object : Runnable {
            override fun run() {
                if (System.currentTimeMillis() - laserAt >= 5000L) {
                    laser = null
                    invalidate()
                } else {
                    main.postDelayed(this, 500)
                }
            }
        }

        fun clearAll() {
            laser = null
            pens.clear()
            arrows.clear()
            circles.clear()
            main.removeCallbacks(laserTimeout)
            invalidate()
        }

        fun setLaser(payload: String) {
            val p = parsePoint(payload) ?: return
            laser = p
            laserAt = System.currentTimeMillis()
            main.removeCallbacks(laserTimeout)
            main.postDelayed(laserTimeout, 500)
            invalidate()
        }

        fun addPen(payload: String) {
            // 期望 JSON 数组 [[x,y],...] 或 "x1,y1;x2,y2"
            try {
                val path = Path()
                if (payload.trim().startsWith("[")) {
                    val arr = org.json.JSONArray(payload)
                    var first = true
                    for (i in 0 until arr.length()) {
                        val pt = arr.getJSONArray(i)
                        val x = pt.getDouble(0).toFloat() * width
                        val y = pt.getDouble(1).toFloat() * height
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    }
                } else {
                    val parts = payload.split(";")
                    var first = true
                    for (part in parts) {
                        val xy = part.split(",")
                        if (xy.size < 2) continue
                        val x = xy[0].toFloat() * width
                        val y = xy[1].toFloat() * height
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    }
                }
                pens.add(path)
                invalidate()
            } catch (_: Throwable) {}
        }

        fun addArrow(payload: String) {
            val v = parse4(payload) ?: return
            arrows.add(v)
            invalidate()
        }

        fun addCircle(payload: String) {
            // cx,cy,r 或 JSON
            try {
                if (payload.trim().startsWith("{")) {
                    val o = JSONObject(payload)
                    circles.add(floatArrayOf(
                        o.getDouble("x").toFloat(),
                        o.getDouble("y").toFloat(),
                        o.optDouble("r", 0.05).toFloat()
                    ))
                } else {
                    val p = payload.split(",")
                    if (p.size >= 3) {
                        circles.add(floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()))
                    }
                }
                invalidate()
            } catch (_: Throwable) {}
        }

        private fun parsePoint(s: String): PointF? {
            return try {
                if (s.trim().startsWith("{")) {
                    val o = JSONObject(s)
                    PointF(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
                } else {
                    val p = s.split(",")
                    if (p.size < 2) null else PointF(p[0].toFloat(), p[1].toFloat())
                }
            } catch (_: Throwable) { null }
        }

        private fun parse4(s: String): FloatArray? {
            return try {
                if (s.trim().startsWith("{")) {
                    val o = JSONObject(s)
                    floatArrayOf(
                        o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                        o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat()
                    )
                } else {
                    val p = s.split(",")
                    if (p.size < 4) null
                    else floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat())
                }
            } catch (_: Throwable) { null }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            for (path in pens) canvas.drawPath(path, paint)
            for (a in arrows) {
                val x1 = a[0] * w
                val y1 = a[1] * h
                val x2 = a[2] * w
                val y2 = a[3] * h
                canvas.drawLine(x1, y1, x2, y2, paint)
                // 简单箭头头部
                val ang = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                val len = 28.0
                val a1 = ang - Math.PI * 0.85
                val a2 = ang + Math.PI * 0.85
                canvas.drawLine(x2, y2, (x2 + len * Math.cos(a1)).toFloat(), (y2 + len * Math.sin(a1)).toFloat(), paint)
                canvas.drawLine(x2, y2, (x2 + len * Math.cos(a2)).toFloat(), (y2 + len * Math.sin(a2)).toFloat(), paint)
            }
            for (c in circles) {
                canvas.drawCircle(c[0] * w, c[1] * h, c[2] * Math.min(w, h), paint)
            }
            laser?.let {
                canvas.drawCircle(it.x * w, it.y * h, 18f, laserPaint)
            }
        }
    }
}
