package com.carmusic.playback

import com.carmusic.source.providers.CHROME_UA
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 播放流 HTTP 头注入。
 *
 * 背景：MediaItem 只带 URI，ExoPlayer/OkHttp 数据源拿不到 MusicSource 解析时
 * 才知道的 Referer/UA。猫耳 getsound 返回的 m3u8/分片在阿里云 WAF 下无站内
 * Referer 会静默失败——UI 显示在播却听不到声音（v3.9 车机实测）。
 *
 * 用法：取流成功后 [register]；播放数据源经 [interceptor] 按 URL/host 补头。
 */
object PlaybackHttpHeaders {

    /** 精确 URL → 需要带上的心头（Referer 等）。播放会话内有效，容量有限 */
    private val byUrl = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > 200
    }

    private val lock = Any()

    fun register(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        synchronized(lock) { byUrl[url] = headers }
    }

    fun headersFor(url: String): Map<String, String> = synchronized(lock) { byUrl[url] ?: emptyMap() }

    /** 按 host 的兜底头：CDN 不认 carmusic/x.y.z UA，统一改浏览器 UA；猫耳系补站内 Referer */
    internal fun defaultHeadersFor(url: String): Map<String, String> {
        val host = url.toHttpUrlOrNull()?.host ?: return emptyMap()
        val h = mutableMapOf("User-Agent" to CHROME_UA)
        if (host.endsWith("missevan.com") || host.endsWith("missevan.cn") ||
            host.contains("missevan")
        ) {
            h["Referer"] = "https://www.missevan.com/"
        }
        return h
    }

    /**
     * OkHttp 拦截器：URL 精确表优先，host 兜底次之；调用方已设置的头不覆盖
     * （API 路径自己带的 Referer/UA 保持原样）。
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val req = chain.request()
        val extras = headersFor(req.url.toString()) + defaultHeadersFor(req.url.toString())
        if (extras.isEmpty()) return@Interceptor chain.proceed(req)
        val b = req.newBuilder()
        extras.forEach { (k, v) -> if (req.header(k) == null) b.header(k, v) }
        chain.proceed(b.build())
    }

    /** 测试用：清空 */
    internal fun clearForTest() = synchronized(lock) { byUrl.clear() }
}
