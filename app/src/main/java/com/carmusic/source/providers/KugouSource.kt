package com.carmusic.source.providers

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
 * 酷狗音乐 - 使用 hash 签名机制
 */
class KugouSource(private val client: OkHttpClient) : MusicSource {

    override val platform = "kugou"
    override val displayName = "酷狗"

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val encodedKw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://complexsearch.kugou.com/v2/search/song" +
                "?callback=callback123&keyword=$encodedKw&page=$page&pagesize=$limit" +
                "&bitrate=0&isfuzzy=0&inputtype=0&platform=WebFilter" +
                "&userid=0&clientver=2000&iscorrection=1&privilege_filter=0" +
                "&filter=10&token=&appid=1014&dfid=-"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", CHROME_UA)
                .header("Referer", "https://www.kugou.com/")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val text = resp.body?.string() ?: return@withContext emptyList()
                // 酷狗返回 JSONP 格式 callback123({...})；先校验包装再剥离，防止异常响应被 substring 出垃圾
                if (!text.startsWith("callback123(")) return@withContext emptyList()
                val json = runCatching {
                    JsonParser.parseString(
                        text.substringAfter("callback123(").substringBeforeLast(")")
                    ).asJsonObject
                }.getOrNull() ?: return@withContext emptyList()
                val lists = json.getAsJsonObject("data")?.getAsJsonArray("lists")
                    ?: return@withContext emptyList()

                lists.mapNotNull { el ->
                    try {
                        val song = el.asJsonObject
                        Track(
                            platform = platform,
                            id = song.get("FileHash").asString,
                            title = song.get("SongName").asString,
                            artist = song.get("SingerName").asString,
                            album = song.get("AlbumName")?.asString ?: "",
                            duration = song.get("Duration")?.asLong ?: 0,
                            extra = mapOf(
                                "albumId" to (song.get("AlbumID")?.asString ?: ""),
                                "hash" to song.get("FileHash").asString,
                                "hqhash" to (song.get("HQFileHash")?.asString ?: ""),
                                "sqhash" to (song.get("SQFileHash")?.asString ?: "")
                            )
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

    override suspend fun getMediaSource(track: Track, quality: String): MediaSource? =
        withContext(Dispatchers.IO) {
            val hash = when (quality) {
                "flac" -> track.extra["sqhash"]?.takeIf { it.isNotBlank() }
                    ?: track.extra["hqhash"]?.takeIf { it.isNotBlank() }
                    ?: track.id
                "320k" -> track.extra["hqhash"]?.takeIf { it.isNotBlank() } ?: track.id
                else -> track.id
            }

            // wwwapi getdata 已失效（err 30020），改用 m 站接口（实测可用）
            val url = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash"

            val json = client.getJson(url, mapOf("User-Agent" to CHROME_UA))
                ?: return@withContext null
            // url 在 JSON 顶层；VIP 歌返回 "url":"" + "error":"需要付费"，判空走 fallback
            val playUrl = json.get("url")?.takeIf { !it.isJsonNull }?.asString
                ?.takeIf { it.isNotBlank() } ?: return@withContext null

            MediaSource(
                url = playUrl,
                expireAt = System.currentTimeMillis() + 2 * 60 * 60 * 1000,  // 2 小时
                quality = quality
            )
        }

    override suspend fun getLyric(track: Track): LyricResult? =
        withContext(Dispatchers.IO) {
            runCatching {
                // 先搜歌词 ID
                val searchUrl = "https://krcs.kugou.com/search" +
                    "?ver=1&man=yes&client=mobi&hash=${track.id}"
                val searchJson = client.getJson(searchUrl, mapOf("User-Agent" to CHROME_UA))
                    ?: return@withContext null
                val candidates = searchJson.getAsJsonArray("candidates")
                if (candidates == null || candidates.size() == 0) return@withContext null
                val item = candidates[0].asJsonObject
                val id = item.get("id").asString
                val accessKey = item.get("accesskey").asString

                // 下载歌词
                val dlUrl = "https://krcs.kugou.com/download" +
                    "?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
                val dlJson = client.getJson(dlUrl, mapOf("User-Agent" to CHROME_UA))
                    ?: return@withContext null
                val encoded = dlJson.get("content")?.asString ?: return@withContext null
                val lrc = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                LyricResult(lrc = lrc)
            }.getOrNull()
        }

    // rankid 均实测有效（前 3 个 2026-07-28 curl 验证返回 rankname；
    // v3.2.0 新增 6 个 2026-08-24 从 m.kugou.com/rank/list 实拉并逐个验证曲目可取）
    override suspend fun getRecommendedPlaylists(): List<Playlist> = listOf(
        Playlist(platform = platform, id = "rank:8888", name = "酷狗 · TOP500", trackCount = 50, description = "每日更新", isTopList = true),
        Playlist(platform = platform, id = "rank:6666", name = "酷狗 · 飙升榜", trackCount = 50, description = "每日更新", isTopList = true),
        Playlist(platform = platform, id = "rank:31308", name = "酷狗 · 内地榜", trackCount = 50, description = "每周更新", isTopList = true),
        Playlist(platform = platform, id = "rank:74534", name = "酷狗 · 新歌榜", trackCount = 50, description = "每日更新", isTopList = true),
        Playlist(platform = platform, id = "rank:82831", name = "酷狗 · 网络热歌榜", trackCount = 50, description = "每日更新", isTopList = true),
        Playlist(platform = platform, id = "rank:85432", name = "酷狗 · 百万收藏榜", trackCount = 50, description = "每日更新", isTopList = true),
        Playlist(platform = platform, id = "rank:24971", name = "酷狗 · DJ热歌榜", trackCount = 50, description = "每周更新", isTopList = true),
        Playlist(platform = platform, id = "rank:33165", name = "酷狗 · 粤语金曲榜", trackCount = 50, description = "每周更新", isTopList = true),
        Playlist(platform = platform, id = "rank:33163", name = "酷狗 · 影视金曲榜", trackCount = 50, description = "每周更新", isTopList = true)
    )

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> =
        withContext(Dispatchers.IO) {
            when {
                playlist.id.startsWith("rank:") -> fetchRankTracks(playlist.id.removePrefix("rank:"))
                playlist.id.startsWith("sp:") -> fetchSpecialTracks(playlist.id.removePrefix("sp:"))
                else -> emptyList()
            }
        }

    /** 歌单广场：m.kugou.com/plist/index，30 条/页，实测无需签名（2026-08-06） */
    override suspend fun getPlaylistSquare(offset: Int): List<Playlist> =
        withContext(Dispatchers.IO) {
            val page = offset / 30 + 1
            val url = "https://m.kugou.com/plist/index?json=true&page=$page"

            val json = client.getJson(url, mapOf("User-Agent" to CHROME_UA))
                ?: return@withContext emptyList()
            val list = json.optObj("plist")?.optObj("list")?.optArr("info")
                ?: return@withContext emptyList()

            list.mapNotNull { el ->
                try {
                    val item = el.asJsonObject
                    Playlist(
                        platform = platform,
                        id = "sp:${item.optLong("specialid") ?: return@mapNotNull null}",
                        name = item.optStr("specialname") ?: return@mapNotNull null,
                        coverUrl = item.optStr("imgurl")?.replace("{size}", "400"),
                        trackCount = (item.optLong("songcount") ?: 0).toInt(),
                        description = "酷狗歌单"
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    private fun fetchRankTracks(rankId: String): List<Track> {
        // 实测无需签名；m.kugou.com 已支持 https（明文白名单仅为流 CDN 保留）
        val url = "https://m.kugou.com/rank/info/?rankid=$rankId&page=1&json=true"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_UA)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val text = resp.body?.string() ?: return emptyList()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            // songs 在顶层，不在 info 里（已实测）
            val list = json?.optObj("songs")?.optArr("list")
            if (list == null) {
                android.util.Log.w("KugouSource", "rank parse failed: ${text.take(500)}")
                return emptyList()
            }

            return list.mapNotNull { el ->
                try {
                    val song = el.asJsonObject
                    val hash = song.optStr("hash") ?: return@mapNotNull null
                    Track(
                        platform = platform,
                        id = hash,
                        title = song.optStr("songname") ?: return@mapNotNull null,
                        artist = song.optArr("authors")?.let { arr ->
                            (0 until arr.size()).mapNotNull { i ->
                                arr[i].asJsonObject.optStr("author_name")
                            }.joinToString("/")
                        }?.takeIf { it.isNotBlank() }
                            ?: song.optStr("singername") ?: "",
                        coverUrl = song.optStr("imgurl")?.replace("{size}", "400"),
                        duration = song.optLong("duration") ?: 0,
                        extra = buildMap {
                            put("hash", hash)
                            put("albumId", song.optStr("album_id") ?: "")
                            song.optStr("sqhash")?.let { put("sqhash", it) }
                            song.optStr("320hash")?.let { put("hqhash", it) }
                        }
                    )
                } catch (e: Exception) {
                    null
                }
            }.take(50)
        }
    }

    /**
     * 广场歌单曲目：mobilecdnbj special/song（实测 2026-08-06 可用，分页正常）。
     * 该端点无 songname 字段，标题/歌手从 filename（"歌手 - 歌名"）拆分。
     */
    private fun fetchSpecialTracks(specialId: String): List<Track> {
        val url = "http://mobilecdnbj.kugou.com/api/v3/special/song" +
            "?specialid=$specialId&page=1&pagesize=50"

        val json = runCatching {
            val request = Request.Builder().url(url).header("User-Agent", CHROME_UA).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                JsonParser.parseString(resp.body?.string() ?: return emptyList()).asJsonObject
            }
        }.getOrNull() ?: return emptyList()

        val list = json.optObj("data")?.optArr("info") ?: return emptyList()
        return list.mapNotNull { el ->
            try {
                val song = el.asJsonObject
                val hash = song.optStr("hash") ?: return@mapNotNull null
                val filename = song.optStr("filename") ?: return@mapNotNull null
                // "歌手 - 歌名" 拆分；无分隔符时整段当标题
                val sep = filename.indexOf(" - ")
                val (artist, title) = if (sep > 0) {
                    filename.substring(0, sep).trim() to filename.substring(sep + 3).trim()
                } else "" to filename.trim()
                Track(
                    platform = platform,
                    id = hash,
                    title = title,
                    artist = artist,
                    duration = song.optLong("duration") ?: 0,
                    extra = buildMap {
                        put("hash", hash)
                        put("albumId", song.optStr("album_id") ?: "")
                        song.optStr("sqhash")?.let { put("sqhash", it) }
                        song.optStr("320hash")?.let { put("hqhash", it) }
                    }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
