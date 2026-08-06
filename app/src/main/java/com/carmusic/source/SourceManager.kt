package com.carmusic.source

import android.util.Log
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import com.carmusic.source.providers.GdStudioSource
import com.carmusic.source.providers.JamendoSource
import com.carmusic.source.providers.KugouSource
import com.carmusic.source.providers.KuwoSource
import com.carmusic.source.providers.MaoerSource
import com.carmusic.source.providers.MiguSource
import com.carmusic.source.providers.NeteaseSource
import com.carmusic.source.providers.QQSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

/**
 * 音源聚合管理：7 平台并发搜索、播放失败自动 fallback
 */
class SourceManager(okHttpClient: OkHttpClient, settingsRepository: com.carmusic.data.SettingsRepository) {

    private val sources: List<MusicSource> = listOf(
        MiguSource(okHttpClient),
        KuwoSource(okHttpClient),
        NeteaseSource(okHttpClient),
        KugouSource(okHttpClient),
        QQSource(okHttpClient),
        JamendoSource(okHttpClient) { settingsRepository.jamendoClientId.first() },
        MaoerSource(okHttpClient)
        // B站（BilibiliSource）v2.5 移除注册、v2.9 删除整个文件：车机网络下接口无数据，用户授意取消
    )

    /**
     * 按可播性排序：网易云 > 酷我（antiserver 无签名直连，2026-08 实测最稳）> 咪咕 > 酷狗 > QQ（vkey 对免费歌有效，VIP 快速失败）。
     * 同时影响搜索排序、去重保留和 fallback 候选顺序。
     */
    private val preferredOrder = listOf("netease", "kuwo", "migu", "kugou", "qq")

    /** 内容型源（广播剧/CC 欧美音乐）：标题撞车必误匹配，永不进跨平台 fallback 候选 */
    private val noFallbackPlatforms = setOf("maoer", "jamendo")

    /** 只作 fallback 最后一棒的源：不注册进 sources（不参与普通搜索/歌单），第三方公共实例无 SLA */
    private val fallbackResolvers = listOf<GdStudioSource>(GdStudioSource(okHttpClient))

    /**
     * 并发搜索启用平台，结果合并返回（带平台标识）。
     * 跨平台按 标题+主歌手 去重，优先保留偏好序靠前的平台。
     * 搜索结果走 ApiCache 5 分钟内存缓存。
     */
    suspend fun searchAll(keyword: String, enabledPlatforms: Set<String>? = null): List<Track> =
        ApiCache.getOrPut("search:$keyword:${enabledPlatforms?.sorted()?.joinToString(",")}") {
            coroutineScope {
                val filtered = enabledPlatforms?.let { en -> sources.filter { it.platform in en } }
                    ?: sources
                val deferreds = filtered.map { source ->
                    async {
                        try {
                            source.search(keyword, page = 1, limit = 15)
                        } catch (e: Exception) {
                            Log.w(TAG, "search failed for ${source.platform}: ${e.message}")
                            emptyList()
                        }
                    }
                }
                val merged = deferreds.flatMap { it.await() }
                // 按偏好序排前再去重，保留首条（即偏好序最高的那条）
                val sorted = merged.sortedBy {
                    preferredOrder.indexOf(it.platform).takeIf { idx -> idx >= 0 } ?: 99
                }
                sorted.distinctBy { track -> "${norm(track.title)}|${firstArtist(track.artist)}" }
            }
        }

    /**
     * 搜索单个平台（用于过滤场景）
     */
    suspend fun searchPlatform(platform: String, keyword: String): List<Track> {
        val source = sources.find { it.platform == platform } ?: return emptyList()
        return try {
            source.search(keyword)
        } catch (e: Exception) {
            Log.w(TAG, "search failed for $platform: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取播放 URL。
     * 先尝试原平台；若失败，自动 fallback 到其他平台搜索同名歌曲。
     */
    suspend fun getMediaSource(track: Track): MediaSource? {
        // 先试原平台
        val origin = sources.find { it.platform == track.platform }
        if (origin != null) {
            try {
                val src = origin.getMediaSource(track)
                if (src != null && !src.isExpired()) return src
            } catch (e: Exception) {
                Log.w(TAG, "getMediaSource failed for ${track.platform}: ${e.message}")
            }
        }

        // fallback：在其他平台搜同名歌曲（标题去括号归一化 + 主歌手校验，最多试 3 个候选）
        // QQ vkey 实测对免费歌有效、VIP 快速失败（2026-07-29），重新纳入候选
        val keyword = "${track.title} ${track.artist}"
        val candidates = runCatching {
            searchAll(keyword)
                .filter { it.platform != track.platform }
                .filter { it.platform !in noFallbackPlatforms }
                .filter { isSameSong(it, track) }
                .sortedBy { preferredOrder.indexOf(it.platform).takeIf { idx -> idx >= 0 } ?: 99 }
        }.getOrElse {
            Log.w(TAG, "fallback searchAll failed: ${it.message}")
            emptyList()
        }

        for (candidate in candidates.take(3)) {
            val source = sources.find { it.platform == candidate.platform } ?: continue
            try {
                val src = source.getMediaSource(candidate)
                if (src != null && !src.isExpired()) {
                    Log.i(TAG, "fallback success: ${track.platform} -> ${candidate.platform}")
                    return src
                }
            } catch (e: Exception) {
                Log.w(TAG, "fallback ${candidate.platform} failed: ${e.message}")
            }
        }
        // 最后一棒：GD Studio 公共解析器（熔断保护，挂了不拖累主流程）
        for (resolver in fallbackResolvers) {
            try {
                val src = resolver.resolveFor(track)
                if (src != null && !src.isExpired()) {
                    Log.i(TAG, "fallback success: ${track.platform} -> gdstudio")
                    return src
                }
            } catch (e: Exception) {
                Log.w(TAG, "gdstudio resolver failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * 获取歌词
     */
    suspend fun getLyric(track: Track): LyricResult? {
        val source = sources.find { it.platform == track.platform } ?: return null
        return try {
            source.getLyric(track)
        } catch (e: Exception) {
            Log.w(TAG, "getLyric failed for ${track.platform}: ${e.message}")
            null
        }
    }

    /**
     * 轻量版：只走原平台，不触发跨平台 fallback。
     * 用于预加载场景，避免一次切换放大成多次跨平台搜索请求。
     */
    suspend fun getMediaSourceNoFallback(track: Track): MediaSource? {
        val origin = sources.find { it.platform == track.platform } ?: return null
        return runCatching { origin.getMediaSource(track) }.getOrNull()
    }

    fun getSource(platform: String): MusicSource? = sources.find { it.platform == platform }

    /**
     * 聚合所有平台的推荐歌单/榜单。
     * 按平台隔离缓存（30 分钟），单平台失败只影响自己的分区。
     * 榜单（QQ/酷狗/酷我热歌榜）排在网易云推荐前面。
     */
    suspend fun getRecommendedPlaylists(): List<Playlist> = coroutineScope {
        sources.map { source ->
            async {
                ApiCache.getOrPut("playlists:${source.platform}", ttlMs = 30 * 60_000) {
                    runCatching { source.getRecommendedPlaylists() }
                        .onFailure { Log.w(TAG, "playlists failed for ${source.platform}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }
        }.flatMap { it.await() }
            .sortedBy { if (it.isTopList) 0 else 1 }
    }

    /**
     * 歌单曲目（按歌单 id 缓存 30 分钟）。
     * 剔除各平台预标记的无效单曲（网易 st<0/fee 1|4 → "grey"；QQ pay_play/msgid 解析时已过滤）——
     * 用户授意：播不了的歌不显示（2026-08-03）。
     */
    suspend fun getPlaylistTracks(playlist: Playlist): List<Track> {
        val source = sources.find { it.platform == playlist.platform } ?: return emptyList()
        return ApiCache.getOrPut("playlist:${playlist.playlistId}", ttlMs = 30 * 60_000) {
            runCatching { source.getPlaylistTracks(playlist) }
                .onFailure { Log.w(TAG, "playlist tracks failed for ${playlist.playlistId}: ${it.message}") }
                .getOrDefault(emptyList())
        }.filter { it.extra["grey"] != "1" }
    }

    /**
     * 歌单广场分页（单平台，5 分钟短缓存——分页内容时效性高于稳定性）
     * offset=已加载条数，空列表表示没有更多。
     */
    suspend fun getPlaylistSquare(platform: String, offset: Int): List<Playlist> {
        val source = sources.find { it.platform == platform } ?: return emptyList()
        return ApiCache.getOrPut("square:$platform:$offset", ttlMs = 5 * 60_000) {
            runCatching { source.getPlaylistSquare(offset) }
                .onFailure { Log.w(TAG, "square failed for $platform@$offset: ${it.message}") }
                .getOrDefault(emptyList())
        }
    }

    companion object {
        private const val TAG = "SourceManager"

        /** 标题归一化：小写、去【】/()（）内容、去空白（"xx（现场版）"≈"xx"，可用性优先） */
        private fun norm(s: String): String = s.lowercase()
            .replace(Regex("【[^】]*】"), "")
            .replace(Regex("[(（][^)）]*[)）]"), "")
            .replace(Regex("\\s+"), "")

        /** 主歌手：取第一个分隔符前的名字并归一化 */
        private fun firstArtist(s: String): String =
            s.split("/", "、", ",", "，", "&").firstOrNull()?.let(::norm) ?: ""

        /** 同名判断：归一化标题相等 + 主歌手匹配（一边缺歌手时只按标题） */
        private fun isSameSong(a: Track, b: Track): Boolean {
            if (norm(a.title) != norm(b.title)) return false
            val x = firstArtist(a.artist)
            val y = firstArtist(b.artist)
            if (x.isEmpty() || y.isEmpty()) return true
            return x == y || x.contains(y) || y.contains(x)
        }
    }
}
