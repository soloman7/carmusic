package com.carmusic.source.providers

import com.carmusic.BuildConfig
import com.carmusic.source.MusicSource
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Jamendo - 官方免费 API（CC 版权欧美独立音乐，零版权风险）
 *
 * client_id 需在 dev.jamendo.com 免费注册（v2.7.1 起内置默认值），设置页可改；
 * tracks 返回直接带 mp3 直连（audio 字段），getMediaSource 无需再请求。
 *
 * v2.8.0：用户歌单广场弃用（实测全是 spam/空歌单/重复，creationdate 全为 0000），
 * 改为 9 个 tags 主题精选（每个已 curl 实测 ≥50 首含 audio）。
 *
 * 不进跨平台 fallback 候选（欧美曲库与中文流行无交集，匹配必是噪声）。
 */
class JamendoSource(
    private val client: OkHttpClient,
    private val clientIdProvider: suspend () -> String
) : MusicSource {

    override val platform = "jamendo"
    override val displayName = "免费电台"

    private val api = "https://api.jamendo.com/v3.0"

    /** 主题精选（tag 全部实测 ≥50 首有效，2026-08-05） */
    private val curatedPlaylists = listOf(
        "classical" to "古典精选",
        "piano" to "钢琴时光",
        "jazz" to "爵士酒吧",
        "ambient" to "环境氛围",
        "world" to "世界音乐",
        "soundtrack" to "影视原声",
        "chillout" to "弛放沙发",
        "hiphop" to "嘻哈节拍",
        "instrumental" to "纯音乐"
    ).map { (tag, name) ->
        Playlist(
            platform = platform,
            id = "tag:$tag",
            name = "Jamendo · $name",
            trackCount = 50,
            description = "Jamendo 主题精选"
        )
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", BuildConfig.UA).build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val cid = clientIdProvider().takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            val url = "$api/tracks/?client_id=$cid&format=json&limit=$limit" +
                "&offset=${(page - 1) * limit}&search=${URLEncoder.encode(keyword, "UTF-8")}" +
                "&include=musicinfo&audioformat=mp32"
            val body = get(url) ?: return@withContext emptyList()
            parseTracks(body)
        }

    /** 播放 URL 在搜索/歌单时已随 track 返回（extra["audio"]），直接取用 */
    override suspend fun getMediaSource(track: Track, quality: String): MediaSource? =
        track.extra["audio"]?.takeIf { it.startsWith("http") }
            ?.let { MediaSource(url = it, expireAt = 0, quality = "320k") }

    override suspend fun getLyric(track: Track): LyricResult? = null

    /** 推荐：两个官方热度榜 + 9 个主题精选（用户歌单已下线：spam/空/重复） */
    override suspend fun getRecommendedPlaylists(): List<Playlist> =
        listOf(
            Playlist(platform = platform, id = "chart:week", name = "Jamendo · 本周热门", trackCount = 50, description = "全球热度周榜", isTopList = true),
            Playlist(platform = platform, id = "chart:total", name = "Jamendo · 总热门榜", trackCount = 50, description = "全球热度总榜", isTopList = true)
        ) + curatedPlaylists

    /** 广场 = 9 个主题精选；offset>0 返回空终止翻页（spam 用户歌单 v2.8 下线） */
    override suspend fun getPlaylistSquare(offset: Int): List<Playlist> =
        if (offset == 0) curatedPlaylists else emptyList()

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> =
        withContext(Dispatchers.IO) {
            val cid = clientIdProvider().takeIf { it.isNotBlank() } ?: return@withContext emptyList()
            when {
                // 热度榜：limit=60 取回 → 过滤无 audio + 去重 → 取 50；周榜不足用总榜补
                playlist.id.startsWith("chart:") -> {
                    val isWeek = playlist.id == "chart:week"
                    val main = fetchChartTracks(cid, if (isWeek) "popularity_week" else "popularity_total")
                    if (isWeek && main.size < 50) {
                        val seen = main.map { it.id }.toMutableSet()
                        val topUp = fetchChartTracks(cid, "popularity_total")
                            .filter { it.id !in seen }
                            .take(50 - main.size)
                        main + topUp
                    } else main
                }
                // 主题精选：tags 精确匹配，<10 首时 fuzzytags 模糊重试（tags 对部分词会波动返 0）
                playlist.id.startsWith("tag:") -> {
                    val tag = playlist.id.removePrefix("tag:")
                    val strict = fetchTagTracks(cid, tag, fuzzy = false)
                    if (strict.size >= 10) strict else fetchTagTracks(cid, tag, fuzzy = true)
                }
                else -> emptyList()
            }
        }

    private fun fetchChartTracks(cid: String, order: String): List<Track> {
        val body = get("$api/tracks/?client_id=$cid&format=json&limit=60&order=$order&include=musicinfo&audioformat=mp32")
            ?: return emptyList()
        return parseTracks(body).filter { it.hasAudio() }.distinctBy { it.id }.take(50)
    }

    private fun fetchTagTracks(cid: String, tag: String, fuzzy: Boolean): List<Track> {
        val param = if (fuzzy) "fuzzytags" else "tags"
        val body = get("$api/tracks/?client_id=$cid&format=json&limit=50&order=popularity_total&$param=$tag&include=musicinfo&audioformat=mp32")
            ?: return emptyList()
        return parseTracks(body).filter { it.hasAudio() }.distinctBy { it.id }.take(50)
    }

    private fun Track.hasAudio(): Boolean = extra["audio"]?.startsWith("http") == true

    private fun parseTracks(body: String): List<Track> {
        val arr = runCatching { JsonParser.parseString(body).asJsonObject.getAsJsonArray("results") }
            .getOrNull() ?: return emptyList()
        return arr.mapNotNull { el -> runCatching { parseTrack(el.asJsonObject) }.getOrNull() }
    }

    private fun parseTrack(t: com.google.gson.JsonObject): Track {
        val audio = t.get("audio")?.asString ?: ""
        return Track(
            platform = platform,
            id = t.get("id").asString,
            title = t.get("name").asString,
            artist = t.get("artist_name")?.asString ?: "",
            album = t.get("album_name")?.asString ?: "",
            coverUrl = t.get("album_image")?.asString?.takeIf { it.isNotBlank() },
            duration = t.get("duration")?.asLong ?: 0,
            extra = mapOf("audio" to audio)
        )
    }
}
