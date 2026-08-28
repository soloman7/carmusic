package com.carmusic.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackId: String,  // platform:id 形式
    val platform: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val duration: Long,  // 秒
    val extra: Map<String, String> = emptyMap(),
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "history",
    // v3 起按 playedAt 倒序分页查询是热路径，补索引避免全表排序
    indices = [Index("playedAt")]
)
data class HistoryEntity(
    @PrimaryKey val trackId: String,
    val platform: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val duration: Long,
    val extra: Map<String, String> = emptyMap(),
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "lyrics")
data class LyricEntity(
    @PrimaryKey val trackId: String,
    val lrc: String,
    val tlyric: String? = null,  // 翻译歌词
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * 播放会话快照（单行，id 恒为 0）：队列 + 当前索引 + 进度。
 * DiLink 杀进程后恢复"停车前听到哪"，队列 Track 列表以 JSON 序列化存放。
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = 0,
    val queueJson: String,
    val currentIndex: Int,
    val positionMs: Long,
    val savedAt: Long = System.currentTimeMillis()
)
