package com.phoneshare.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 简单的日志：界面上显示，也可以一键复制，方便把问题发回来。 */
object Logx {
    private val lines = java.util.ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: ((String) -> Unit)? = null

    @Synchronized
    fun add(msg: String) {
        val line = fmt.format(Date()) + " " + msg
        lines.addLast(line)
        while (lines.size > 800) lines.removeFirst()
        android.util.Log.i("PhoneShare", msg)
        listener?.invoke(line)
    }

    @Synchronized
    fun all(): String = lines.joinToString("\n")
}
