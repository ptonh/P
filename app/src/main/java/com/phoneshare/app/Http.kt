package com.phoneshare.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 配置：网址、密钥、房间、清晰度（短边像素，0 = 原始）、码率上限（kbps） */
data class Cfg(
    val base: String,
    val auth: String,
    val room: String,
    val shortSide: Int,
    val maxKbps: Int
)

object Http {
    fun request(method: String, url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Cache-Control", "no-store")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP " + code + " " + text.take(200))
            return text
        } finally {
            c.disconnect()
        }
    }
}
