package com.phoneshare.app

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Toast
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 悬浮标记层：只画对方标记，不挡触摸。
 * 消息格式与页面一致：mk:<type>:<json>
 * 坐标存 0～1，绘制时乘宽高。
 */
object MarkOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: MarkView? = null
    private var appCtx: Context? = null
    @Volatile private var permHinted = false

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
                if (!permHinted) {
                    permHinted = true
                    try {
                        Toast.makeText(
                            ctx,
                            "对方在标记你的屏幕，请在设置里开启“悬浮标记权限”",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Throwable) {}
                }
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
            permHinted = false
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            w.addView(v, lp)
            view = v
        } catch (e: Throwable) {
            Logx.add("标记层添加失败: " + e.message)
            view = null
        }
    }

    private class MarkView(ctx: Context) : View(ctx) {
        private val color = Color.parseColor("#ffd600")
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colorY()
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colorY()
            style = Paint.Style.FILL
        }

        private fun colorY(): Int = Color.parseColor("#ffd600")
        private fun dp(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

        private var laser: PointF? = null
        private var laserAt = 0L
        // pen id -> list of normalized points
        private val pens = LinkedHashMap<String, ArrayList<PointF>>()
        private val arrows = ArrayList<FloatArray>() // x1,y1,x2,y2 norm
        private val circles = ArrayList<FloatArray>() // x1,y1,x2,y2 norm (diagonal)

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
            try {
                val o = JSONObject(if (payload.isEmpty()) "{}" else payload)
                if (o.has("off")) {
                    laser = null
                    main.removeCallbacks(laserTimeout)
                    invalidate()
                    return
                }
                laser = PointF(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
                laserAt = System.currentTimeMillis()
                main.removeCallbacks(laserTimeout)
                main.postDelayed(laserTimeout, 500)
                invalidate()
            } catch (_: Throwable) {}
        }

        fun addPen(payload: String) {
            try {
                val o = JSONObject(payload)
                val id = o.optString("id", "p")
                val arr = o.optJSONArray("p") ?: return
                var list = pens[id]
                if (list == null) {
                    if (pens.size >= 300) {
                        val first = pens.keys.first()
                        pens.remove(first)
                    }
                    list = ArrayList()
                    pens[id] = list
                }
                var total = 0
                for (l in pens.values) total += l.size
                for (i in 0 until arr.length()) {
                    if (total >= 3000) break
                    val pt = arr.getJSONArray(i)
                    list.add(PointF(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat()))
                    total++
                }
                invalidate()
            } catch (_: Throwable) {}
        }

        fun addArrow(payload: String) {
            try {
                val o = JSONObject(payload)
                arrows.add(
                    floatArrayOf(
                        o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                        o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat()
                    )
                )
                invalidate()
            } catch (_: Throwable) {}
        }

        fun addCircle(payload: String) {
            try {
                val o = JSONObject(payload)
                circles.add(
                    floatArrayOf(
                        o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                        o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat()
                    )
                )
                invalidate()
            } catch (_: Throwable) {}
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            paint.color = colorY()
            laserPaint.color = colorY()
            for (list in pens.values) {
                if (list.size < 2) continue
                val path = Path()
                path.moveTo(list[0].x * w, list[0].y * h)
                for (i in 1 until list.size) {
                    path.lineTo(list[i].x * w, list[i].y * h)
                }
                canvas.drawPath(path, paint)
            }
            val head = dp(16f)
            for (a in arrows) {
                val x1 = a[0] * w
                val y1 = a[1] * h
                val x2 = a[2] * w
                val y2 = a[3] * h
                canvas.drawLine(x1, y1, x2, y2, paint)
                val ang = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                val a1 = ang - 0.45
                val a2 = ang + 0.45
                canvas.drawLine(
                    x2, y2,
                    (x2 - head * Math.cos(a1)).toFloat(),
                    (y2 - head * Math.sin(a1)).toFloat(),
                    paint
                )
                canvas.drawLine(
                    x2, y2,
                    (x2 - head * Math.cos(a2)).toFloat(),
                    (y2 - head * Math.sin(a2)).toFloat(),
                    paint
                )
            }
            for (c in circles) {
                val left = Math.min(c[0], c[2]) * w
                val top = Math.min(c[1], c[3]) * h
                val right = Math.max(c[0], c[2]) * w
                val bottom = Math.max(c[1], c[3]) * h
                if (right - left < 1f && bottom - top < 1f) continue
                canvas.drawOval(RectF(left, top, right, bottom), paint)
            }
            laser?.let {
                canvas.drawCircle(it.x * w, it.y * h, dp(9f), laserPaint)
            }
        }
    }
}
