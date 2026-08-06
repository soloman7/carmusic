package com.carmusic.lyric

import android.util.Log
import com.carmusic.data.LyricDao
import com.carmusic.data.LyricEntity
import com.carmusic.source.SourceManager
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌词仓库：先查本地缓存，未命中（或过期）再从音源拉。
 * 负缓存：无歌词的歌存 lrc="" 记录，24h 内不再发网络请求；
 * 正缓存 30 天、负缓存 24 小时，过期视为 miss 重新拉取并覆盖写入。
 * Room/网络任一环节失败都返回 null（歌词是增强功能，绝不允许搞崩播放）。
 */
class LyricRepository(
    private val lyricDao: LyricDao,
    private val sourceManager: SourceManager
) {

    companion object {
        private const val POSITIVE_TTL_MS = 30L * 24 * 60 * 60 * 1000   // 30 天
        private const val NEGATIVE_TTL_MS = 24L * 60 * 60 * 1000        // 24 小时
    }

    suspend fun getLyric(track: Track): LyricResult? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val cached = lyricDao.get(track.trackId)
            if (cached != null) {
                val ttl = if (cached.lrc.isBlank()) NEGATIVE_TTL_MS else POSITIVE_TTL_MS
                if (now - cached.cachedAt < ttl) {
                    // 负缓存命中直接返回 null，不再发网络请求
                    if (cached.lrc.isBlank()) return@withContext null
                    return@withContext LyricResult(lrc = cached.lrc, tlyric = cached.tlyric)
                }
                // 过期：视为 miss，继续走网络
            }
            val result = runCatching { sourceManager.getLyric(track) }.getOrNull()
            runCatching {
                lyricDao.insert(
                    LyricEntity(
                        trackId = track.trackId,
                        lrc = result?.lrc ?: "",   // null/无歌词 → 负缓存
                        tlyric = result?.tlyric
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.w("LyricRepository", "getLyric failed for ${track.trackId}: ${e.message}")
            null
        }
    }
}
