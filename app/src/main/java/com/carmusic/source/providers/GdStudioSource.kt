package com.carmusic.source.providers

import com.carmusic.BuildConfig
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Track
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GD Studio 公共解析 API - fallback 最后一棒（2026-08-03 实测活着）
 *
 * 第三方公共实例，无 SLA、间歇性限流（search 偶发返回 []），所以：
 * - 不进 sources 列表，不参与普通搜索/歌单
 * - 只在 SourceManager.getMediaSource 跨平台 fallback 全部失败后兜底
 * - 连续失败 3 次熔断 10 分钟，防止实例挂掉时每次 fallback 白等
 * - 8s 短超时
 *
 * 实测：types=url&id={neteaseId}&source=netease 非 VIP 歌直接吐 flac/mp3 直连。
 */
class GdStudioSource(client: OkHttpClient) {

    private val api = "https://music-api.gdstudio.xyz/api.php"

    /** 短超时派生 client（复用连接池/缓存） */
    private val shortClient: OkHttpClient = client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // ---- 熔断器 ----
    private val consecutiveFailures = AtomicInteger(0)
    @Volatile private var circuitOpenUntil = 0L

    private fun isCircuitOpen(): Boolean =
        consecutiveFailures.get() >= 3 && System.currentTimeMillis() < circuitOpenUntil

    private fun onSuccess() { consecutiveFailures.set(0) }

    private fun onFailure() {
        if (consecutiveFailures.incrementAndGet() >= 3) {
            circuitOpenUntil = System.currentTimeMillis() + 10 * 60_000
        }
    }

    /**
     * 为指定歌曲兜底解析播放 URL：GD 搜网易源 → 同名匹配 → types=url 取流。
     * 任何环节失败返回 null（静默，不抛）。
     *
     * 熔断只统计 IO/HTTP 错误（实例挂了/限流）；"请求成功但没匹配到歌"是正常业务结果，
     * 不计失败——否则搜几首冷门歌就会误熔断。
     */
    suspend fun resolveFor(track: Track): MediaSource? = withContext(Dispatchers.IO) {
        if (isCircuitOpen()) return@withContext null
        try {
            val gdId = searchNeteaseId("${track.title} ${track.artist}", track.title)
                ?: return@withContext null  // 无匹配：不动熔断计数
            val url = fetchUrl(gdId) ?: return@withContext null  // 同上
            onSuccess()
            MediaSource(
                url = url,
                expireAt = System.currentTimeMillis() + 60 * 60 * 1000,  // 1 小时
                quality = "320k"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 切歌取消是正常流程，绝不能计入熔断器（3 次切歌就误熔断 10 分钟）
        } catch (e: Exception) {
            onFailure()
            null
        }
    }

    /** GD 搜索（网易源），返回与目标标题归一化相等的第一条 id；artist 字段是 list */
    private fun searchNeteaseId(keyword: String, wantTitle: String): String? {
        val url = "$api?types=search&source=netease&count=5&pages=1&name=" +
            URLEncoder.encode(keyword, "UTF-8")
        val body = get(url)
        val arr = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull() ?: return null
        val want = norm(wantTitle)
        for (el in arr) {
            // 公共实例响应可能混入非对象元素，逐元素隔离，坏一条跳一条而不是计入熔断
            val o = runCatching { el.asJsonObject }.getOrNull() ?: continue
            val name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
            if (norm(name) != want) continue
            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
            return id
        }
        return null
    }

    private fun fetchUrl(id: String): String? {
        val body = get("$api?types=url&source=netease&id=$id")
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        return obj.get("url")?.takeIf { !it.isJsonNull }?.asString
            ?.takeIf { it.startsWith("http") }
    }

    /** 取响应体；IO/HTTP 错误抛 IOException 让熔断器计失败（区别于"无匹配"业务 null） */
    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", BuildConfig.UA).build()
        return shortClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("gdstudio HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("gdstudio empty body")
        }
    }

    /** 与 SourceManager 同款标题归一化（去括号内容/空白/小写） */
    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("【[^】]*】"), "")
        .replace(Regex("[(（][^)）]*[)）]"), "")
        .replace(Regex("\\s+"), "")
}
