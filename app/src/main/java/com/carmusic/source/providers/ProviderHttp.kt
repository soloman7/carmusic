package com.carmusic.source.providers

import com.carmusic.source.SourceUnavailableException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * provider 公共 HTTP 层（v3.9 严格化：吞错家族第三代治理）。
 *
 * 语义分水岭——此前"网络错误 / 非 2xx / 非 JSON"三种请求级失败与"平台应答但无数据"
 * 统一折叠成 null，下游 probe/fallback/清理全部在猜。现在：
 * - **请求失败 → 抛 [SourceUnavailableException]**（证据不可信；SourceManager 边界
 *   按既有契约转译：probe=不可信不动账本、search=该源缺席、fallback=不上抛污染）
 * - **应答成功 → 返回非空 JsonObject**；provider 返回 null/空列表只可能是
 *   "平台确认无此数据"（payload 层判断：vkey 空、无 url、code≠0、无曲目）
 * 各 provider 的差异化行为（JSONP 剥离、自定义 header、失败日志等）仍保留在各自文件里。
 */

/** 桌面 Chrome UA（各音乐站点按浏览器流量放行，统一维护一处） */
const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** GET → JsonObject。请求失败抛 [SourceUnavailableException]，成功返回非空对象 */
suspend fun OkHttpClient.getJson(
    url: String,
    headers: Map<String, String> = emptyMap()
): JsonObject = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    executeJson(request)
}

/** POST → JsonObject。语义同 [getJson]（body 由调用方构造：JSON 或 FormBody 均可） */
suspend fun OkHttpClient.postJson(
    url: String,
    body: RequestBody,
    headers: Map<String, String> = emptyMap()
): JsonObject = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).post(body).apply {
        headers.forEach { (k, v) -> header(k, v) }
    }.build()
    executeJson(request)
}

private fun OkHttpClient.executeJson(request: Request): JsonObject = try {
    newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) {
            throw SourceUnavailableException("HTTP ${resp.code} from ${request.url.host}")
        }
        val text = resp.body?.string()
            ?: throw SourceUnavailableException("empty body from ${request.url.host}")
        try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            throw SourceUnavailableException("non-JSON from ${request.url.host}", e)
        }
    }
} catch (e: SourceUnavailableException) {
    throw e
} catch (e: Exception) {
    throw SourceUnavailableException("request failed ${request.url.host}: ${e.message}", e)
}
