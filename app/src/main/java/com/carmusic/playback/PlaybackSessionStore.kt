package com.carmusic.playback

import com.carmusic.data.PlaybackStateDao
import com.carmusic.data.PlaybackStateEntity
import com.carmusic.source.model.Track

/**
 * 播放会话持久化（v3.9 从 PlayerManager 拆出，可单测）。
 * DiLink 杀进程后恢复"停车前听到哪"：队列 + 当前索引 + 进度单行覆盖存取。
 * 编解码细节在 [PlaybackSessionCodec]；本层只做校验（空队列/索引钳制）与 DAO 封装。
 */
class PlaybackSessionStore(private val dao: PlaybackStateDao) {

    data class Snapshot(val queue: List<Track>, val index: Int, val positionMs: Long)

    /** 读上次会话快照；无存档/解码失败/空队列 = null（只读不播，等用户动作） */
    suspend fun load(): Snapshot? {
        val saved = runCatching { dao.get() }.getOrNull() ?: return null
        val queue = PlaybackSessionCodec.decode(saved.queueJson) ?: return null
        if (queue.isEmpty()) return null
        return Snapshot(
            queue = queue,
            index = saved.currentIndex.coerceIn(0, queue.size - 1),
            positionMs = saved.positionMs.coerceAtLeast(0L)
        )
    }

    /** 覆盖写当前会话；空队列拒绝写（避免用空快照覆盖有效存档）。静默失败不打扰播放。 */
    suspend fun save(queue: List<Track>, index: Int, positionMs: Long) {
        if (queue.isEmpty()) return
        runCatching {
            dao.upsert(
                PlaybackStateEntity(
                    queueJson = PlaybackSessionCodec.encode(queue),
                    currentIndex = index.coerceIn(0, queue.size - 1),
                    positionMs = positionMs.coerceAtLeast(0L)
                )
            )
        }
    }
}
