package com.carmusic.source.providers

import com.carmusic.source.MusicSource
import com.carmusic.source.crypto.NeteaseCrypto
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 网易云音乐
 * API: weapi（AES+RSA 加密）
 */
class NeteaseSource(private val client: OkHttpClient) : MusicSource {

    override val platform = "netease"
    override val displayName = "网易云"

    private val baseUrl = "https://music.163.com"

    /** weapi 公共请求块：加密 → FormBody → POST → 解析 JsonObject */
    private suspend fun weapi(path: String, payload: String): JsonObject? {
        val (params, encSecKey) = NeteaseCrypto.encrypt(payload)
        val body = FormBody.Builder()
            .add("params", params)
            .add("encSecKey", encSecKey)
            .build()
        return client.postJson(
            "$baseUrl$path", body,
            mapOf(
                "User-Agent" to CHROME_UA,
                "Referer" to baseUrl,
                "Content-Type" to "application/x-www-form-urlencoded"
            )
        )
    }

    /** 单曲 JSON → Track（search 与 playlist.tracks 结构一致）；element 求值也在 try 内，非对象元素不抛 */
    private fun parseTrack(el: JsonElement): Track? = try {
        val song = el.asJsonObject
        Track(
            platform = platform,
            id = song.get("id").asString,
            title = song.get("name").asString,
            artist = song.getAsJsonArray("ar")?.let { arr ->
                (0 until arr.size()).joinToString("/") { i ->
                    arr[i].asJsonObject.get("name").asString
                }
            } ?: "",
            album = song.getAsJsonObject("al")?.get("name")?.asString ?: "",
            coverUrl = song.getAsJsonObject("al")?.get("picUrl")?.asString,
            duration = song.get("dt")?.asLong?.div(1000) ?: 0
        )
    } catch (e: Exception) {
        null
    }

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val payload = """{"s":"${keyword.escapeJson()}","type":1,"offset":${(page - 1) * limit},"limit":$limit,"csrf_token":""}"""
            val json = weapi("/weapi/cloudsearch/get/web", payload)
                ?: return@withContext emptyList()
            val songs = json.getAsJsonObject("result")?.getAsJsonArray("songs")
                ?: return@withContext emptyList()
            songs.mapNotNull(::parseTrack)
        }

    override suspend fun getMediaSource(track: Track, quality: String): MediaSource? =
        withContext(Dispatchers.IO) {
            val payload = """{"ids":"[${track.id.escapeJson()}]","level":"standard","encodeType":"aac","csrf_token":""}"""
            val json = weapi("/weapi/song/enhance/player/url/v1", payload)
            val officialUrl = json?.getAsJsonArray("data")?.takeIf { it.size() > 0 }
                ?.get(0)?.asJsonObject?.get("url")
                ?.takeIf { !it.isJsonNull }?.asString
            if (officialUrl != null) {
                return@withContext MediaSource(
                    url = officialUrl,
                    expireAt = System.currentTimeMillis() + 20 * 60 * 1000,  // 20 分钟
                    quality = quality
                )
            }
            // 官方接口对 VIP/版权歌返回 url=null → 外链兜底（实测匿名 12/12 可播）。
            // 必须先 Range 探测：灰歌外链 302 到 404，直接返回会让跨平台 fallback 失去机会
            val outerUrl = "$baseUrl/song/media/outer/url?id=${track.id}.mp3"
            if (probePlayable(outerUrl)) {
                MediaSource(
                    url = outerUrl,
                    expireAt = System.currentTimeMillis() + 20 * 60 * 1000,
                    quality = quality
                )
            } else null
        }

    /** 轻量探测：GET + Range 只看响应码，body 立即关闭（OkHttp 自动跟 302 到 126.net） */
    private fun probePlayable(url: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_UA)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(req).execute().use { it.code in 200..299 }
    } catch (e: Exception) {
        false
    }

    override suspend fun getLyric(track: Track): LyricResult? =
        withContext(Dispatchers.IO) {
            val payload = """{"id":"${track.id.escapeJson()}","lv":-1,"tv":-1,"csrf_token":""}"""
            val json = weapi("/weapi/song/lyric", payload) ?: return@withContext null
            val lrc = json.getAsJsonObject("lrc")?.get("lyric")?.asString
                ?: return@withContext null
            val tlrc = json.getAsJsonObject("tlyric")?.get("lyric")?.asString
            LyricResult(lrc = lrc, tlyric = tlrc)
        }

    override suspend fun getRecommendedPlaylists(): List<Playlist> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val topListDeferred = async { fetchTopLists() }
                val highqualityDeferred = async { fetchHighquality() }
                val personalizedDeferred = async { fetchPersonalized() }
                // 榜单在前；任一接口失败只影响自己的分区
                topListDeferred.await() + highqualityDeferred.await() + personalizedDeferred.await()
            }
        }

    /** 官方榜单：动态拉取全部榜单取前 8，失败回退 4 个硬编码稳定 id */
    private suspend fun fetchTopLists(): List<Playlist> {
        val fallback = listOf(
            Playlist(platform = platform, id = "19723756", name = "网易云 · 飙升榜", trackCount = 100, description = "官方榜单", isTopList = true),
            Playlist(platform = platform, id = "3779629", name = "网易云 · 新歌榜", trackCount = 100, description = "官方榜单", isTopList = true),
            Playlist(platform = platform, id = "3778678", name = "网易云 · 热歌榜", trackCount = 200, description = "官方榜单", isTopList = true),
            Playlist(platform = platform, id = "2884035", name = "网易云 · 原创榜", trackCount = 100, description = "官方榜单", isTopList = true)
        )
        val json = runCatching { weapi("/weapi/toplist", """{"csrf_token":""}""") }.getOrNull()
            ?: return fallback
        val list = json.optArr("list") ?: return fallback
        val dynamic = (0 until minOf(list.size(), 8)).mapNotNull { i ->
            try {
                val p = list[i].asJsonObject
                Playlist(
                    platform = platform,
                    id = p.get("id").asString,
                    name = "网易云 · ${p.get("name").asString}",
                    coverUrl = p.optStr("coverImgUrl")?.let { "$it?param=400y400" },
                    trackCount = p.optLong("trackCount")?.toInt() ?: 100,
                    description = p.optStr("updateFrequency") ?: "官方榜单",
                    isTopList = true
                )
            } catch (e: Exception) {
                null
            }
        }
        return dynamic.ifEmpty { fallback }
    }

    /** 分类精品歌单：车载场景导向的 4 个分类，每类 6 个 */
    private suspend fun fetchHighquality(): List<Playlist> {
        val cats = listOf("华语", "流行", "轻音乐", "电子")
        return cats.flatMap { cat ->
            val json = runCatching {
                weapi("/weapi/playlist/highquality/list", """{"cat":"$cat","limit":6,"csrf_token":""}""")
            }.getOrNull() ?: return@flatMap emptyList()
            json.optArr("playlists")?.mapNotNull { el ->
                try {
                    val p = el.asJsonObject
                    Playlist(
                        platform = platform,
                        id = p.get("id").asString,
                        name = p.get("name").asString,
                        coverUrl = p.optStr("coverImgUrl")?.let { "$it?param=400y400" },
                        trackCount = p.optLong("trackCount")?.toInt() ?: 0,
                        description = "精品 · $cat"
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
        }
    }

    /** 歌单广场（复刻 Listen 1）：最热排序，offset 分页；纯数字 id 复用现有曲目路径 */
    override suspend fun getPlaylistSquare(offset: Int): List<Playlist> =
        withContext(Dispatchers.IO) {
            val payload = """{"cat":"全部","order":"hot","limit":30,"offset":$offset,"total":true,"csrf_token":""}"""
            val json = weapi("/weapi/playlist/list", payload) ?: return@withContext emptyList()
            json.optArr("playlists")?.mapNotNull { el ->
                try {
                    val p = el.asJsonObject
                    Playlist(
                        platform = platform,
                        id = p.get("id").asString,
                        name = p.get("name").asString,
                        coverUrl = p.optStr("coverImgUrl")?.let { "$it?param=400y400" },
                        trackCount = p.optLong("trackCount")?.toInt() ?: 0,
                        description = "歌单广场"
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
        }

    /** 个性化推荐（原有接口） */
    private suspend fun fetchPersonalized(): List<Playlist> {
        val payload = """{"limit":30,"total":true,"n":1000,"csrf_token":""}"""
        val json = runCatching { weapi("/weapi/personalized/playlist", payload) }.getOrNull()
        val result = json?.optArr("result")
        return result?.mapNotNull { el ->
            try {
                val p = el.asJsonObject
                Playlist(
                    platform = platform,
                    id = p.get("id").asString,
                    name = p.get("name").asString,
                    coverUrl = p.optStr("picUrl")?.let { "$it?param=400y400" },
                    trackCount = p.optLong("trackCount")?.toInt() ?: 0,
                    description = "网易云推荐"
                )
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> =
        withContext(Dispatchers.IO) {
            // id 拼进 JSON 且不加引号，必须是纯数字（防注入）
            val plId = playlist.id.toLongOrNull() ?: return@withContext emptyList()
            val payload = """{"id":$plId,"n":100000,"s":8,"csrf_token":""}"""
            // v6 匿名调用可能截断，空结果回退 v3
            var detailJson = weapi("/weapi/v6/playlist/detail", payload)
            var tracks = detailJson?.optObj("playlist")?.optArr("tracks")
            if (tracks == null || tracks.size() == 0) {
                detailJson = weapi("/weapi/v3/playlist/detail", payload)
                tracks = detailJson?.optObj("playlist")?.optArr("tracks")
            }
            tracks ?: return@withContext emptyList()
            // privileges 与 tracks 平行：st<0 即无版权灰歌；fee 1=VIP 4=付费专辑（匿名必播不了）
            // 预标记省掉播放时的无效解析，UI 层直接剔除不显示
            val greyIds = detailJson?.optObj("playlist")?.optArr("privileges")?.let { arr ->
                (0 until arr.size()).mapNotNull { i ->
                    val p = arr[i].asJsonObject
                    val st = p.get("st")?.takeIf { !it.isJsonNull }?.asInt ?: return@mapNotNull null
                    val fee = p.get("fee")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    val id = p.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                    if (st < 0 || fee == 1 || fee == 4) id else null
                }.toSet()
            } ?: emptySet()
            tracks.mapNotNull(::parseTrack).map { t ->
                if (t.id in greyIds) t.copy(extra = t.extra + ("grey" to "1")) else t
            }
        }
}
