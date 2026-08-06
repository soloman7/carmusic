package com.carmusic.source.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * provider 公共 HTTP 层：统一 execute().use → isSuccessful → runCatching 解析 → null 兜底。
 * 各 provider 的差异化行为（JSONP 剥离、自定义 header、失败日志等）仍保留在各自文件里。
 */

/** 桌面 Chrome UA（各音乐站点按浏览器流量放行，统一维护一处） */
const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** GET → JsonObject；网络错误 / 非 2xx / 非 JSON 对象一律返回 null，不抛异常 */
suspend fun OkHttpClient.getJson(
    url: String,
    headers: Map<String, String> = emptyMap()
): JsonObject? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    executeJson(request)
}

/** POST → JsonObject；语义同 getJson（body 由调用方构造：JSON 或 FormBody 均可） */
suspend fun OkHttpClient.postJson(
    url: String,
    body: RequestBody,
    headers: Map<String, String> = emptyMap()
): JsonObject? = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).post(body).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    executeJson(request)
}

private fun OkHttpClient.executeJson(request: Request): JsonObject? = try {
    newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val text = resp.body?.string() ?: return null
        runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    }
} catch (e: Exception) {
    null
}
