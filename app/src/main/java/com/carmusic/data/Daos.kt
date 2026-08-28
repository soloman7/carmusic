package com.carmusic.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    suspend fun getAll(): List<FavoriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    suspend fun isFavorite(trackId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackId = :trackId)")
    fun isFavoriteFlow(trackId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE trackId = :trackId")
    suspend fun deleteById(trackId: String)

    /**
     * 原子切换收藏状态，修复连点竞态（先查后写在同一事务内完成）：
     * 已收藏则删除并返回 false，否则插入并返回 true。
     */
    @Transaction
    suspend fun toggleFavorite(entity: FavoriteEntity): Boolean {
        return if (isFavorite(entity.trackId)) {
            deleteById(entity.trackId)
            false
        } else {
            insert(entity)
            true
        }
    }
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int = 100): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE trackId = :trackId")
    suspend fun deleteById(trackId: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("""
        DELETE FROM history WHERE trackId NOT IN (
            SELECT trackId FROM history ORDER BY playedAt DESC LIMIT :keep
        )
    """)
    suspend fun trimToSize(keep: Int = 200)

    @Transaction
    suspend fun insertAndTrim(entity: HistoryEntity, keep: Int = 200) {
        insert(entity)
        trimToSize(keep)
    }
}

@Dao
interface LyricDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: String): LyricEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LyricEntity)

    @Query("DELETE FROM lyrics WHERE cachedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE id = 0")
    suspend fun get(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackStateEntity)
}
