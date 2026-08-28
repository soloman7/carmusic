package com.carmusic.source.providers

import com.carmusic.source.MusicSource
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.UUID

/**
 * QQ 音乐 - 使用 guid + uin + vkey 签名机制
 */
class QQSource(private val client: OkHttpClient) : MusicSource {

    override val platform = "qq"
    override val displayName = "QQ音乐"

    // 匿名 guid 和 uin，公开获取 vkey 用
    private val guid: String = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
    private val uin = "0"

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val payload = """
            {
                "comm": {
                    "ct": "19", "cv": "1859", "uin": "$uin"
                },
                "req": {
                    "method": "DoSearchForQQMusicDesktop",
                    "module": "music.search.SearchCgiService",
                    "param": {
                        "grp": 1,
                        "num_per_page": $limit,
                        "page_num": $page,
                        "query": "${keyword.escapeJson()}",
                        "search_type": 0
                    }
                }
            }
            """.trimIndent()

            val json = client.postJson(
                "https://u.y.qq.com/cgi-bin/musicu.fcg",
                payload.toRequestBody("application/json".toMediaType()),
                mapOf("User-Agent" to CHROME_UA, "Referer" to "https://y.qq.com/")
            ) ?: return@withContext emptyList()
            val list = json.getAsJsonObject("req")
                ?.getAsJsonObject("data")
                ?.getAsJsonObject("body")
                ?.getAsJsonObject("song")
                ?.getAsJsonArray("list")
                ?: return@withContext emptyList()

            list.mapNotNull { el ->
                try {
                    val song = el.asJsonObject
                    Track(
                        platform = platform,
                        id = song.get("mid").asString,
                        title = song.get("name").asString,
                        artist = song.getAsJsonArray("singer")?.let { arr ->
                            (0 until arr.size()).joinToString("/") { i ->
                                arr[i].asJsonObject.get("name").asString
                            }
                        } ?: "",
                        album = song.getAsJsonObject("album")?.get("name")?.asString ?: "",
                        coverUrl = song.getAsJsonObject("album")?.get("mid")?.asString?.let { mid ->
                            "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg"
                        },
                        duration = song.get("interval")?.asLong ?: 0,
                        extra = mapOf(
                            "songMid" to song.get("mid").asString,
                            "mediaMid" to (song.getAsJsonObject("file")?.get("media_mid")?.asString
                                ?: song.get("mid").asString),
                            "strMediaMid" to (song.getAsJsonObject("file")?.get("media_mid")?.asString
                                ?: song.get("mid").asString)
                        )
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    override suspend fun getMediaSource(track: Track, quality: String): MediaSource? =
        withContext(Dispatchers.IO) {
            val songMid = track.extra["songMid"] ?: track.id
            val mediaMid = track.extra["mediaMid"] ?: track.id

            val (prefix, ext) = when (quality) {
                "flac" -> "F000" to "flac"
                "320k" -> "M800" to "mp3"
                else -> "M500" to "mp3"
            }
            val filename = "$prefix$mediaMid.$ext"

            val payload = """
            {
                "req_0": {
                    "module": "vkey.GetVkeyServer",
                    "method": "CgiGetVkey",
                    "param": {
                        "filename": ["$filename"],
                        "guid": "$guid",
                        "songmid": ["$songMid"],
                        "songtype": [0],
                        "uin": "$uin",
                        "loginflag": 1,
                        "platform": "20"
                    }
                },
                "comm": {
                    "qq": "$uin",
                    "authst": "",
                    "ct": "26",
                    "cv": "2010101",
                    "v": "2010101"
                }
            }
            """.trimIndent()

            val json = client.postJson(
                "https://u.y.qq.com/cgi-bin/musicu.fcg",
                payload.toRequestBody("application/json".toMediaType()),
                mapOf("User-Agent" to CHROME_UA, "Referer" to "https://y.qq.com/")
            ) ?: return@withContext null
            val data = json.getAsJsonObject("req_0")?.getAsJsonObject("data")
                ?: return@withContext null
            val midurlinfo = data.getAsJsonArray("midurlinfo") ?: return@withContext null
            if (midurlinfo.size() == 0) return@withContext null
            val info = midurlinfo[0].asJsonObject
            val purl = info.get("purl")?.asString
            if (purl.isNullOrBlank()) return@withContext null

            val sipArr = data.getAsJsonArray("sip")
            val sip = if (sipArr != null && sipArr.size() > 0) {
                sipArr[0].asString
            } else {
                "https://dl.stream.qqmusic.qq.com/"
            }

            MediaSource(
                url = sip + purl,
                expireAt = System.currentTimeMillis() + 30 * 60 * 1000,  // 30 分钟
                quality = quality
            )
        }

    override suspend fun getLyric(track: Track): LyricResult? =
        withContext(Dispatchers.IO) {
            val songMid = track.extra["songMid"] ?: track.id
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                "?songmid=$songMid&pcachetime=${System.currentTimeMillis()}" +
                "&g_tk=5381&loginUin=$uin&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0&format=json"

            val json = client.getJson(
                url,
                mapOf("User-Agent" to CHROME_UA, "Referer" to "https://y.qq.com/")
            ) ?: return@withContext null
            val lyricB64 = json.get("lyric")?.asString ?: return@withContext null
            val lrc = String(android.util.Base64.decode(lyricB64, android.util.Base64.DEFAULT))
            val transB64 = json.get("trans")?.asString
            val tlrc = transB64?.let {
                String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
            }
            LyricResult(lrc = lrc, tlyric = tlrc)
        }

    /** 榜单定义（topid 全部 curl 实测有效、含 pic_v12 封面：前 12 个 2026-08-05，v3.2.0 新增 6 个 2026-08-24） */
    private data class ToplistDef(
        val id: String, val name: String, val updateDesc: String, val trackCount: Int = 100
    )

    private val toplists = listOf(
        ToplistDef("26", "QQ音乐 · 热歌榜", "每日更新", 50),
        ToplistDef("62", "QQ音乐 · 飙升榜", "每日更新"),
        ToplistDef("27", "QQ音乐 · 新歌榜", "每日更新"),
        ToplistDef("4", "QQ音乐 · 流行指数榜", "每日更新"),
        ToplistDef("5", "QQ音乐 · 内地榜", "每周更新"),
        ToplistDef("6", "QQ音乐 · 港台榜", "每周更新"),
        ToplistDef("3", "QQ音乐 · 欧美榜", "每周更新"),
        ToplistDef("60", "QQ音乐 · 抖音热歌榜", "每日更新"),
        ToplistDef("57", "QQ音乐 · 电音榜", "每周更新", 50),
        ToplistDef("58", "QQ音乐 · 说唱榜", "每周更新", 50),
        ToplistDef("67", "QQ音乐 · 听歌识曲榜", "每日更新"),
        ToplistDef("108", "QQ音乐 · 美国公告牌榜", "每周更新"),
        ToplistDef("28", "QQ音乐 · 网络歌曲榜", "每周更新"),
        ToplistDef("29", "QQ音乐 · 影视金曲榜", "每周更新"),
        ToplistDef("65", "QQ音乐 · 国风热歌榜", "每周更新"),
        ToplistDef("63", "QQ音乐 · DJ舞曲榜", "每周更新"),
        ToplistDef("72", "QQ音乐 · 动漫音乐榜", "每周更新"),
        ToplistDef("16", "QQ音乐 · 韩国榜", "每周更新")
    )

    /** 并行拉全部榜单的真封面（topinfo.pic_v12），单榜失败兜底 null（UI 占位） */
    override suspend fun getRecommendedPlaylists(): List<Playlist> = coroutineScope {
        toplists.map { def ->
            async {
                Playlist(
                    platform = platform,
                    id = "top:${def.id}",
                    name = def.name,
                    coverUrl = fetchToplistCover(def.id),
                    trackCount = def.trackCount,
                    description = def.updateDesc,
                    isTopList = true
                )
            }
        }.awaitAll()
    }

    /** 榜单封面：fcg_v8_toplist_cp 的 topinfo.pic_v12，转 https（明文 http 会被 Coil 拦） */
    private suspend fun fetchToplistCover(topId: String): String? {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg" +
            "?topid=$topId&num=1&page=1&type=1&format=json"
        val json = client.getJson(
            url,
            mapOf("User-Agent" to CHROME_UA, "Referer" to "https://y.qq.com/")
        ) ?: return null
        return json.optObj("topinfo")?.optStr("pic_v12")?.takeIf { it.isNotBlank() }?.https()
    }

    /** http → https（Android 9+ 默认禁 cleartext，qpic/y.gtimg 均实测支持 https） */
    private fun String.https(): String = if (startsWith("http://")) "https://" + substring(7) else this

    /** 歌单广场（复刻 Listen 1）：最热排序，sin/ein 分页，实测 sum≈11620 */
    override suspend fun getPlaylistSquare(offset: Int): List<Playlist> =
        withContext(Dispatchers.IO) {
            val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg" +
                "?picmid=1&rnd=0.5&g_tk=732560869" +
                "&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0" +
                "&categoryId=10000000&sortId=5&sin=$offset&ein=${offset + 29}"

            val json = client.getJson(
                url,
                mapOf("User-Agent" to CHROME_UA, "Referer" to "https://y.qq.com/")
            ) ?: return@withContext emptyList()
            json.getAsJsonObject("data")?.getAsJsonArray("list")?.mapNotNull { el ->
                try {
                    val d = el.asJsonObject
                    Playlist(
                        platform = platform,
                        id = "diss:${d.get("dissid").asString}",
                        name = htmlDecode(d.get("dissname").asString),
                        coverUrl = d.get("imgurl")?.asString?.https(),
                        trackCount = d.get("songnum")?.asInt ?: 0,
                        description = "歌单广场"
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
        }

    /** dissname 常见 HTML 实体解码（Gson 不处理） */
    private fun htmlDecode(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    override suspend fun getPlaylistTracks(playlist: Playlist): List<Track> =
        withContext(Dispatchers.IO) {
            when {
                playlist.id.startsWith("top:") -> getToplistTracks(playlist.id.removePrefix("top:"))
                playlist.id.startsWith("diss:") -> getDissTracks(playlist.id.removePrefix("diss:"))
                else -> emptyList()
            }
        }

    /** 榜单曲目（fcg_v8_toplist_cp） */
    private fun getToplistTracks(topId: String): List<Track> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg" +
            "?topid=$topId&num=50&page=1&type=1&format=json"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_UA)
            .header("Referer", "https://y.qq.com/")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val text = resp.body?.string() ?: return emptyList()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val songlist = json?.optArr("songlist")
            if (songlist == null) {
                android.util.Log.w("QQSource", "toplist parse failed: ${text.take(500)}")
                return emptyList()
            }
            return filterPlayable(songlist.mapNotNull { el -> runCatching { parseSong(el.asJsonObject) }.getOrNull() })
        }
    }

    /** 广场歌单曲目（fcg_ucc_getcdinfo_byids_cp，复刻 Listen 1 参数） */
    private fun getDissTracks(dissId: String): List<Track> {
        val url = "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
            "?type=1&json=1&utf8=1&onlysong=0" +
            "&nosign=1&disstid=$dissId&g_tk=5381&loginUin=0&hostUin=0" +
            "&format=json&inCharset=GB2312&outCharset=utf-8&notice=0" +
            "&platform=yqq&needNewCode=0"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CHROME_UA)
            .header("Referer", "https://y.qq.com/")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val text = resp.body?.string() ?: return emptyList()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val songlist = json?.optArr("cdlist")?.takeIf { it.size() > 0 }
                ?.get(0)?.asJsonObject?.optArr("songlist")
            if (songlist == null) {
                android.util.Log.w("QQSource", "diss parse failed: ${text.take(500)}")
                return emptyList()
            }
            return filterPlayable(songlist.mapNotNull { el -> runCatching { parseSong(el.asJsonObject) }.getOrNull() })
        }
    }

    /**
     * 批量 vkey 预检：实测哪些歌匿名能出播放地址（M500 探测），purl 空的剔除。
     * 根因：约 1/3 的 VIP/无版权歌在榜单接口里不带 pay_play/msgid 标记
     * （2026-08-05 实测内地榜 100 首 33 首漏网），标记过滤抓不全，只能实测。
     * vkey 请求本身失败时该批原样保留（宁漏杀不错杀）。
     */
    private fun filterPlayable(tracks: List<Track>): List<Track> {
        if (tracks.isEmpty()) return tracks
        val result = mutableListOf<Track>()
        tracks.chunked(100).forEach { batch ->
            val playable = probePlayable(batch)
            result += if (playable == null) batch
            else batch.filter { (it.extra["songMid"] ?: it.id) in playable }
        }
        return result
    }

    /** 单批（≤100 首）vkey 探测，返回可出 purl 的 songMid 集合；请求失败返回 null */
    private fun probePlayable(batch: List<Track>): Set<String>? {
        val songMids = batch.map { it.extra["songMid"] ?: it.id }
        val filenames = batch.map { "M500${it.extra["mediaMid"] ?: it.id}.mp3" }
        val payload = """
        {
            "req_0": {
                "module": "vkey.GetVkeyServer",
                "method": "CgiGetVkey",
                "param": {
                    "filename": [${filenames.joinToString(",") { "\"$it\"" }}],
                    "guid": "$guid",
                    "songmid": [${songMids.joinToString(",") { "\"$it\"" }}],
                    "songtype": [${songMids.joinToString(",") { "0" }}],
                    "uin": "$uin",
                    "loginflag": 1,
                    "platform": "20"
                }
            },
            "comm": { "qq": "$uin", "authst": "", "ct": "26", "cv": "2010101", "v": "2010101" }
        }
        """.trimIndent()

        val request = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", CHROME_UA)
            .header("Referer", "https://y.qq.com/")
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = runCatching {
                    JsonParser.parseString(resp.body?.string() ?: return null).asJsonObject
                }.getOrNull() ?: return null
                val infos = json.getAsJsonObject("req_0")?.getAsJsonObject("data")
                    ?.getAsJsonArray("midurlinfo") ?: return null
                (0 until infos.size()).mapNotNull { i ->
                    val info = infos[i].asJsonObject
                    val purl = info.get("purl")?.asString
                    if (purl.isNullOrBlank()) null else info.get("songmid")?.asString
                }.toSet()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 榜单新版（data 包裹）与 diss 老版（平铺）共用的单曲解析 */
    private fun parseSong(entry: com.google.gson.JsonObject): Track? {
        val song = entry.optObj("data") ?: entry
        // 剔除无效单曲（2026-08-03 实测字段）：
        // pay.pay_play=1 → VIP 专属（匿名 vkey 必拿不到 purl）；
        // action.msgid 13=VIP 试听 / 14=无版权下架——vkey 必然失败的歌直接不显示
        val payPlay = song.optObj("pay")?.optStr("pay_play")?.toIntOrNull() ?: 0
        val msgid = song.optObj("action")?.optStr("msgid")?.toIntOrNull() ?: 0
        if (payPlay == 1 || msgid == 13 || msgid == 14) return null
        val songMid = song.optStr("songmid") ?: return null
        val strMediaMid = song.optStr("strMediaMid") ?: songMid
        return Track(
            platform = platform,
            id = songMid,
            title = song.optStr("songname") ?: return null,
            artist = song.optArr("singer")?.let { arr ->
                (0 until arr.size()).mapNotNull { i ->
                    arr[i].asJsonObject.optStr("name")
                }.joinToString("/")
            } ?: "",
            album = song.optStr("albumname") ?: "",
            coverUrl = song.optStr("albummid")?.takeIf { it.isNotBlank() }?.let { mid ->
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg"
            },
            duration = song.optLong("interval") ?: 0,
            extra = mapOf(
                "songMid" to songMid,
                "mediaMid" to strMediaMid,
                "strMediaMid" to strMediaMid
            )
        )
    }
}
