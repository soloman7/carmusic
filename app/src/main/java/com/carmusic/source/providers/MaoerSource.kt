package com.carmusic.source.providers

import com.carmusic.source.MusicSource
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * 猫耳FM（missevan）- 广播剧/有声书/声音（2026-08-03 实测：搜索+取流均无签名）
 *
 * - 搜索：/sound/getsearch（type=3 声音）
 * - 取流：/sound/getsound → soundurl（HLS m3u8，ExoPlayer 自动识别）
 * - 时长单位毫秒；pay_type 付费剧集可能只给试听，播不了时按正常失败处理
 *
 * 不进跨平台 fallback 候选（时长与歌曲匹配会误伤），只在搜索结果出现。
 */
class MaoerSource(private val client: OkHttpClient) : MusicSource {

    override val platform = "maoer"
    override val displayName = "猫耳FM"

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val url = "https://www.missevan.com/sound/getsearch" +
                "?s=${URLEncoder.encode(keyword, "UTF-8")}&p=$page&type=3&page_size=$limit"

            val json = client.getJson(url, mapOf("User-Agent" to CHROME_UA))
                ?: return@withContext emptyList()
            val datas = json.getAsJsonObject("info")?.getAsJsonArray("Datas")
                ?: return@withContext emptyList()

            datas.mapNotNull { el ->
                try {
                    val s = el.asJsonObject
                    Track(
                        platform = platform,
                        id = s.get("id").asString,
                        title = s.get("soundstr").asString,
                        artist = s.get("username")?.asString ?: "",
                        album = "猫耳FM",
                        coverUrl = s.get("front_cover")?.asString,
                        duration = (s.get("duration")?.asLong ?: 0) / 1000  // 毫秒→秒
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    /** getsound 返回 HLS m3u8 地址（media3-exoplayer-hls 已在依赖里，自动识别） */
    override suspend fun getMediaSource(track: Track, quality: String): MediaSource? =
        withContext(Dispatchers.IO) {
            val url = "https://www.missevan.com/sound/getsound?soundid=${track.id}"
            val json = client.getJson(url, mapOf("User-Agent" to CHROME_UA))
                ?: return@withContext null
            val soundUrl = json.getAsJsonObject("info")?.getAsJsonObject("sound")
                ?.get("soundurl")?.asString
                ?.takeIf { it.startsWith("http") } ?: return@withContext null
            MediaSource(url = soundUrl, expireAt = 0, quality = quality)
        }

    override suspend fun getLyric(track: Track): LyricResult? = null
}
