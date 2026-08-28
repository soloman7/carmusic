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
                    // 付费剧集（pay_type=2）getsound 不给 soundurl 必播不了，直接过滤（2026-08-06 实测）
                    if (s.get("pay_type")?.takeIf { !it.isJsonNull }?.asInt == 2) return@mapNotNull null
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

    /**
     * 主题"歌单"：猫耳官方歌单/广播剧 API 已全部 404（2026-08-06 实测 dramaapi/malbum/album
     * 等 10+ 端点均死），唯一存活的是搜索+取流，故用主题关键词搜索快照充当歌单。
     * v3.2.0 新增 6 个主题（2026-08-24 实测：免费结果 23~30/30，取流抽查 2/2 可播）。
     */
    override suspend fun getRecommendedPlaylists(): List<Playlist> = listOf(
        "热门广播剧" to "广播剧精选",
        "助眠" to "睡前助眠",
        "白噪音" to "白噪音",
        "有声小说" to "有声小说",
        "情感电台" to "情感电台",
        "耳语" to "耳边轻语",
        "悬疑广播剧" to "悬疑剧场",
        "儿童故事" to "儿童故事",
        "相声" to "相声茶馆",
        "睡前故事" to "睡前故事",
        "历史" to "历史人文",
        "评书" to "评书连播"
    ).map { (kw, name) ->
        Playlist(
            platform = platform,
            id = "kw:$kw",
            name = "猫耳 · $name",
            trackCount = 30,
            description = "主题声音集"
        )
    }

    /** 主题歌单曲目 = 关键词搜索结果（已验证可 getsound 取流） */
    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> =
        if (playlist.id.startsWith("kw:")) {
            search(playlist.id.removePrefix("kw:"), page = 1, limit = 30)
        } else emptyList()
}
