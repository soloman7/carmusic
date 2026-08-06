package com.carmusic.source.providers

import com.carmusic.source.MusicSource
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import kotlin.math.roundToLong

/**
 * 酷我音乐 - 无签名直连（2026-08-03 实测，MusicFree/LX 底层同款端点）
 *
 * - 搜索：www.kuwo.cn/search/searchMusicBykeyWord（零鉴权，仅需 UA）
 * - 播放：antiserver.kuwo.cn/anti.s（零签名零 Cookie，十年老端点，仅 mp3）
 * - 旧 kw_token/csrf 系 /api/www 端点已被 WAF 升级拦死（2026-08 实测 "The request is illegal!"），已弃用
 *
 * 注意：kuwo 自己的 payInfo.feeType.vip 标记不可信——标 VIP 的歌 antiserver 常能播
 * （晴天实测）。只有 cannotOnlinePlay=1（区域版权封锁）才一定播不了，搜索时过滤。
 */
class KuwoSource(private val client: OkHttpClient) : MusicSource {

    override val platform = "kuwo"
    override val displayName = "酷我"

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Track> =
        withContext(Dispatchers.IO) {
            val encodedKw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://www.kuwo.cn/search/searchMusicBykeyWord" +
                "?vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8" +
                "&rformat=json&mobi=1&show_copyright_off=1&pn=${page - 1}&rn=$limit&all=$encodedKw"

            val json = client.getJson(url, mapOf("User-Agent" to CHROME_UA))
                ?: return@withContext emptyList()
            val list = json.getAsJsonArray("abslist") ?: return@withContext emptyList()

            list.mapNotNull { el ->
                try {
                    val song = el.asJsonObject
                    // 区域版权封锁一定播不了，直接过滤（"1"/1/boolean true 三种形态都兼容）
                    song.getAsJsonObject("payInfo")?.get("cannotOnlinePlay")
                        ?.takeIf { !it.isJsonNull }?.let { flag ->
                        if (flag.asString == "1" || flag.asBoolean) return@mapNotNull null
                    }
                    Track(
                        platform = platform,
                        id = song.get("MUSICRID").asString,  // "MUSIC_228908"，antiserver 直接用
                        title = song.get("NAME").asString,
                        artist = song.get("ARTIST")?.asString ?: "",
                        album = song.get("ALBUM")?.asString ?: "",
                        coverUrl = song.get("web_albumpic_short")?.asString
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "https://img1.kuwo.cn/star/albumcover/$it" },
                        duration = song.get("DURATION")?.asString?.toLongOrNull() ?: 0  // 秒
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }

    /** antiserver 裸 URL 端点：响应体直接是 http(s) URL 文本；VIP/无版权返回空或非 URL */
    override suspend fun getMediaSource(track: Track, quality: String): MediaSource? =
        withContext(Dispatchers.IO) {
            val rid = track.id.takeIf { it.startsWith("MUSIC_") } ?: "MUSIC_${track.id}"
            val url = "https://antiserver.kuwo.cn/anti.s" +
                "?type=convert_url&format=mp3&response=url&rid=$rid"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", CHROME_UA)
                .build()

            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val playUrl = resp.body?.string()?.trim()
                        ?.takeIf { it.startsWith("http") } ?: return@withContext null
                    MediaSource(
                        url = playUrl,
                        expireAt = System.currentTimeMillis() + 60 * 60 * 1000,  // 1 小时
                        quality = quality
                    )
                }
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun getLyric(track: Track): LyricResult? =
        withContext(Dispatchers.IO) {
            val numericId = track.id.removePrefix("MUSIC_")
            val url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$numericId"

            val json = client.getJson(url, mapOf("User-Agent" to CHROME_UA))
                ?: return@withContext null
            val lrcArray = json.getAsJsonObject("data")?.getAsJsonArray("lrclist")
                ?: return@withContext null

            // 酷我返回的是分段 lrc，需要拼接
            val lrc = StringBuilder()
            for (i in 0 until lrcArray.size()) {
                val item = lrcArray[i].asJsonObject
                val time = item.optStr("time")?.toDoubleOrNull() ?: continue
                val text = item.optStr("lineLyric") ?: continue
                // time 单位为秒（两位小数）；整体转厘秒再拆分，避免 (time % 1) 浮点精度丢失
                val totalCs = (time * 100).roundToLong()
                val m = totalCs / 6000
                val s = (totalCs / 100) % 60
                val cs = totalCs % 100
                lrc.append(String.format("[%02d:%02d.%02d]%s\n", m, s, cs, text))
            }
            LyricResult(lrc = lrc.toString())
        }
}
