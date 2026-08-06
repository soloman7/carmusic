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
 * 咪咕音乐 - 中国移动旗下，免费曲库
 */
class MiguSource(private val client: OkHttpClient) : MusicSource {

    override val platform = "migu"
    override val displayName = "咪咕"

    /** 咪咕 URL 常是 "//" 开头的协议相对地址（封面/播放都有），统一补 https: */
    private fun absUrl(url: String?): String? =
        url?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("//")) "https:$it" else it }

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val encodedKw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://m.music.migu.cn/migu/remoting/scr_search_tag" +
                "?rows=$limit&type=2&keyword=$encodedKw&pgc=$page"

            val json = client.getJson(
                url,
                mapOf("User-Agent" to CHROME_UA, "Referer" to "https://m.music.migu.cn/")
            ) ?: return@withContext emptyList()
            val musics = json.getAsJsonArray("musics") ?: return@withContext emptyList()

            musics.mapNotNull { el ->
                try {
                    val song = el.asJsonObject
                    Track(
                        platform = platform,
                        id = song.get("copyrightId").asString,
                        title = song.get("songName").asString,
                        artist = song.get("artist").asString,
                        album = song.get("albumName")?.asString ?: "",
                        coverUrl = absUrl(song.get("cover")?.asString),
                        duration = song.get("duration")?.asLong ?: 0,
                        extra = mapOf(
                            "songId" to (song.get("songId")?.asString ?: ""),
                            "contentId" to (song.get("contentId")?.asString ?: "")
                        )
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    override suspend fun getMediaSource(track: Track, quality: String): MediaSource? =
        withContext(Dispatchers.IO) {
            val toneFlag = when (quality) {
                "flac" -> "SQ"
                "320k" -> "HQ"
                else -> "PQ"
            }
            // 主：Listen 1 现行端点（copyrightId+contentId）；备：旧 songId 端点（可能未死，860002 与限流同码难辨）
            fetchPlayUrlV1(track, toneFlag, quality) ?: fetchPlayUrlV2(track, toneFlag)?.let { url ->
                MediaSource(url = url, expireAt = System.currentTimeMillis() + 60 * 60 * 1000, quality = quality)
            }
        }

    /** 现行端点：MIGUM3.0 pc/listen/v1.0（copyrightId+contentId） */
    private fun fetchPlayUrlV1(track: Track, toneFlag: String, quality: String): MediaSource? {
        val contentId = track.extra["contentId"]?.takeIf { it.isNotBlank() } ?: return null
        val url = "https://app.c.nf.migu.cn/MIGUM3.0/strategy/pc/listen/v1.0" +
            "?scene=&netType=01&resourceType=2&copyrightId=${track.id}&contentId=$contentId&toneFlag=$toneFlag"
        return requestPlayUrlRaw(url)?.let {
            MediaSource(
                url = it,
                expireAt = System.currentTimeMillis() + 60 * 60 * 1000,  // 1 小时
                quality = quality
            )
        }
    }

    /** 旧端点：MIGUM2.0 listen-url/v2.4（songId） */
    private fun fetchPlayUrlV2(track: Track, toneFlag: String): String? {
        val songId = track.extra["songId"]?.takeIf { it.isNotBlank() } ?: return null
        val url = "https://app.c.nf.migu.cn/MIGUM2.0/strategy/listen-url/v2.4" +
            "?netType=01&resourceType=2&songId=$songId&toneFlag=$toneFlag"
        return requestPlayUrlRaw(url)
    }

    private fun requestPlayUrlRaw(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_UA)
            .header("channel", CHANNEL)
            .header("uid", UID)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
                val data = json.getAsJsonObject("data") ?: return null
                // url 可能为 JsonNull（VIP/无版权匿名常见），直接 .asString 会崩
                var playUrl = data.get("url")?.takeIf { !it.isJsonNull }?.asString
                    ?.takeIf { it.isNotBlank() } ?: return null
                playUrl = absUrl(playUrl)!!
                // 签名 URL 里的 + 不转义会被 CDN 当空格，403（Listen 1 同款处理）
                playUrl.replace("+", "%2B")
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getLyric(track: Track): LyricResult? =
        withContext(Dispatchers.IO) {
            val contentId = track.extra["contentId"] ?: return@withContext null
            val url = "https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId=${track.id}&contentId=$contentId"

            val json = client.getJson(
                url,
                mapOf("User-Agent" to CHROME_UA, "Referer" to "https://music.migu.cn/")
            ) ?: return@withContext null
            // 无歌词时返回 JsonNull，直接 .asString 会抛
            val lrc = json.get("lyric")?.takeIf { !it.isJsonNull }?.asString
                ?: return@withContext null
            LyricResult(lrc = lrc)
        }

    /** 推荐歌单：广场第一页（复刻 Listen 1 的 getMusicData 通道，remoting 旧接口已死） */
    override suspend fun getRecommendedPlaylists(): List<Playlist> = fetchSquarePage(0)

    /** 歌单广场：start 从 1 开始的页码 = offset/30+1 */
    override suspend fun getPlaylistSquare(offset: Int): List<Playlist> = fetchSquarePage(offset)

    private suspend fun fetchSquarePage(offset: Int): List<Playlist> =
        withContext(Dispatchers.IO) {
            val page = offset / 30 + 1
            val url = "https://app.c.nf.migu.cn/MIGUM2.0/v2.0/content/getMusicData.do" +
                "?count=30&start=$page&templateVersion=5&type=1"

            val json = client.getJson(
                url,
                mapOf("User-Agent" to CHROME_UA, "channel" to CHANNEL)
            ) ?: return@withContext emptyList()
            val items = json.getAsJsonObject("data")
                ?.getAsJsonArray("contentItemList")?.firstOrNull()?.asJsonObject
                ?.getAsJsonArray("itemList")
                ?: return@withContext emptyList()
            val idRegex = Regex("id=(\\d+)&")
            items.mapNotNull { el ->
                try {
                    val item = el.asJsonObject
                    val actionUrl = item.get("actionUrl")?.asString ?: return@mapNotNull null
                    val id = idRegex.find(actionUrl)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    Playlist(
                        platform = platform,
                        id = "pl:$id",
                        name = item.get("title").asString,
                        coverUrl = absUrl(item.get("imageUrl")?.asString),
                        description = "咪咕歌单"
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    /** 歌单曲目（注意参数名是小写 playlistId，实测大小写敏感） */
    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> =
        withContext(Dispatchers.IO) {
            if (!playlist.id.startsWith("pl:")) return@withContext emptyList()
            val plId = playlist.id.removePrefix("pl:")
            val url = "https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0" +
                "?playlistId=$plId&pageNo=1&pageSize=50"

            val json = client.getJson(
                url,
                mapOf("User-Agent" to CHROME_UA, "channel" to CHANNEL)
            ) ?: return@withContext emptyList()
            val songs = json.getAsJsonObject("data")?.getAsJsonArray("songList")
                ?: return@withContext emptyList()
            songs.mapNotNull { el ->
                try {
                    val s = el.asJsonObject
                    Track(
                        platform = platform,
                        id = s.get("copyrightId").asString,
                        title = s.get("songName").asString,
                        artist = s.getAsJsonArray("singerList")?.let { arr ->
                            (0 until arr.size()).mapNotNull { i ->
                                arr[i].asJsonObject.get("name")?.asString
                            }.joinToString("/")
                        } ?: "",
                        album = s.get("album")?.asString ?: "",
                        coverUrl = absUrl(s.get("img1")?.asString),
                        duration = s.get("duration")?.asLong ?: 0,  // 实测单位为秒
                        extra = buildMap {
                            put("songId", s.get("songId")?.asString ?: "")
                            put("contentId", s.get("contentId")?.asString ?: "")
                            s.get("lrcUrl")?.asString?.let { put("lrcUrl", it) }
                        }
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    companion object {
        /** 咪咕开放平台匿名渠道号/占位 uid：play 与歌单接口必填，缺了 400（Listen 1 同款值） */
        private const val CHANNEL = "0146951"
        private const val UID = "1234"
    }
}
