package com.carmusic.data.radio

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 电台台站(v5-D1 local-first)。health/deleted/localDeadUntil 三列语义互斥:
 * - health:服务器 lastcheckok 镜像(1=ok/0=down),同步可写
 * - deleted:byuuid 确认的墓碑(数据库已删除),永久,仅复验 worker 可写
 * - localDeadUntil:客户端播放失败挂账时间戳,同步禁触,随时间衰减,用户手点清零
 * 浏览可见性 = health==0 || deleted || now<localDeadUntil 任一命中;收藏页永远全量。
 */
@Entity(tableName = "radio_stations")
data class RadioStationEntity(
    @PrimaryKey @ColumnInfo(name = "stationUuid") val stationUuid: String,
    val name: String,
    val url: String,
    val urlResolved: String,
    val homepage: String,
    val favicon: String,
    val tags: String,
    val country: String,
    val countryCode: String,
    val state: String,
    val language: String,
    val codec: String,
    val bitrate: Int,
    /** 全球热门排名(1..1000,0=非热门);同步写 */
    val hotRank: Int,
    val health: Int,
    val deleted: Boolean,
    val localDeadUntil: Long,
    /** 本地连续失败计数(指数退避 24h/72h/7d 的档位;用户手点清零;同步禁触) */
    val localDeadCount: Int
) {
    val playUrl: String get() = urlResolved.ifBlank { url }
    val isHls: Boolean get() = playUrl.contains(".m3u8")
    val displayBitrate: String get() = if (bitrate > 0) "${codec.ifBlank { "?" }}·${bitrate}kbps" else "码率未知"
}

/** 收藏台:冗余播放字段,台站表删除后收藏仍可播 */
@Entity(tableName = "radio_favorites")
data class RadioFavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "stationUuid") val stationUuid: String,
    val sortOrder: Int,
    val addedAt: Long,
    val name: String,
    val url: String,
    val urlResolved: String,
    val favicon: String,
    val codec: String,
    val bitrate: Int
) {
    val playUrl: String get() = urlResolved.ifBlank { url }
    val isHls: Boolean get() = playUrl.contains(".m3u8")
    val displayBitrate: String get() = if (bitrate > 0) "${codec.ifBlank { "?" }}·${bitrate}kbps" else "码率未知"
}

@Dao
interface RadioStationDao {

    @Upsert
    suspend fun upsertAll(stations: List<RadioStationEntity>)

    @Query("SELECT COUNT(*) FROM radio_stations")
    suspend fun count(): Int

    @Query("SELECT stationUuid FROM radio_stations")
    suspend fun allUuids(): List<String>

    @Query("SELECT * FROM radio_stations WHERE stationUuid = :uuid")
    suspend fun getByUuid(uuid: String): RadioStationEntity?

    /** 本省:state 精确 ∪ 台名含省名 ∪ tag 含省名(数据脏,三路并集),不含墓碑/下线/本地挂账 */
    @Query(
        """SELECT * FROM radio_stations
           WHERE deleted = 0 AND health = 1 AND localDeadUntil <= :now AND countryCode = 'CN'
             AND (state = :province OR name LIKE '%' || :province || '%' OR tags LIKE '%' || :province || '%')
             AND (:bitrateLimit = 0 OR bitrate = 0 OR bitrate <= :bitrateLimit)
           ORDER BY CASE WHEN state = :province THEN 0 ELSE 1 END, bitrate DESC"""
    )
    suspend fun byProvince(province: String, now: Long, bitrateLimit: Int): List<RadioStationEntity>

    /** 台名搜索(可见台) */
    @Query(
        """SELECT * FROM radio_stations
           WHERE deleted = 0 AND health = 1 AND localDeadUntil <= :now
             AND TRIM(name) LIKE '%' || :kw || '%'
             AND (:bitrateLimit = 0 OR bitrate = 0 OR bitrate <= :bitrateLimit)
           ORDER BY hotRank DESC, bitrate DESC LIMIT 200"""
    )
    suspend fun search(kw: String, now: Long, bitrateLimit: Int): List<RadioStationEntity>

    /** 全球热门(搜索页入口) */
    @Query(
        """SELECT * FROM radio_stations
           WHERE deleted = 0 AND health = 1 AND localDeadUntil <= :now AND hotRank > 0
             AND (:bitrateLimit = 0 OR bitrate = 0 OR bitrate <= :bitrateLimit)
           ORDER BY hotRank LIMIT :limit"""
    )
    suspend fun hot(limit: Int, now: Long, bitrateLimit: Int): List<RadioStationEntity>

    @Query("SELECT * FROM radio_stations WHERE hotRank > 0 ORDER BY hotRank")
    suspend fun hotRankedAll(): List<RadioStationEntity>

    @Query("UPDATE radio_stations SET hotRank = 0 WHERE hotRank > 0")
    suspend fun resetHotRanks()

    @Query("UPDATE radio_stations SET hotRank = :rank WHERE stationUuid = :uuid")
    suspend fun setHotRank(uuid: String, rank: Int)

    /** 同步专用:本地 localDeadUntil/deleted 快照(防止 upsert 全列覆盖把挂账/墓碑冲掉) */
    @Query("SELECT stationUuid, localDeadUntil, localDeadCount, deleted FROM radio_stations")
    suspend fun localStateSnapshot(): List<RadioLocalState>
}

@Dao
interface RadioFavoriteDao {

    @Query("SELECT * FROM radio_favorites ORDER BY sortOrder ASC")
    fun getAllFlow(): Flow<List<RadioFavoriteEntity>>

    @Query("SELECT * FROM radio_favorites ORDER BY sortOrder ASC")
    suspend fun getAll(): List<RadioFavoriteEntity>

    @Query("SELECT MAX(sortOrder) FROM radio_favorites")
    suspend fun maxSortOrder(): Int?

    @Upsert
    suspend fun upsert(favorite: RadioFavoriteEntity)

    @Query("DELETE FROM radio_favorites WHERE stationUuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("SELECT COUNT(*) FROM radio_favorites")
    suspend fun count(): Int

    @Query("UPDATE radio_favorites SET sortOrder = :sortOrder WHERE stationUuid = :uuid")
    suspend fun updateSortOrder(uuid: String, sortOrder: Int)

    @Query("SELECT * FROM radio_favorites WHERE stationUuid = :uuid")
    suspend fun getByUuid(uuid: String): RadioFavoriteEntity?
}

/** 同步保护快照的轻量投影 */
data class RadioLocalState(
    val stationUuid: String,
    val localDeadUntil: Long,
    val localDeadCount: Int,
    val deleted: Boolean
)
