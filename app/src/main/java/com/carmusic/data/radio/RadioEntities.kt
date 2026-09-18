package com.carmusic.data.radio

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * 电台台站(v5-D1 local-first)。health/deleted/localDeadUntil 三列语义互斥:
 * - health:服务器 lastcheckok 镜像(1=ok/0=down),同步可写
 * - deleted:byuuid 确认的墓碑(数据库已删除),永久,仅复验 worker 可写
 * - localDeadUntil:客户端播放失败挂账时间戳,同步禁触,随时间衰减,用户手点清零
 * 浏览可见性 = health==0 || deleted || now<localDeadUntil 任一命中;收藏页永远全量。
 */
@Entity(
    tableName = "radio_stations",
    indices = [
        androidx.room.Index("state"),
        androidx.room.Index("countryCode"),
        // v3.7.1:浏览覆盖索引——国家/分类/省份的计数与列表谓词(deleted/health/localDeadUntil/
        // bitrate)+排序口径(clickcount)全部在索引内,免 58k 胖行回表(车机上秒级→毫秒级)。
        // 与 MIGRATION_6_7 的 CREATE INDEX 同名同列序,错位 = Room 校验失败(v3.5.1 教训)。
        androidx.room.Index(
            value = ["countryCode", "health", "deleted", "localDeadUntil", "clickcount", "bitrate"],
            name = "index_radio_stations_browse"
        )
    ]
)
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
    /** 累计投票数(v6-D-B:排序辅助口径);seed/同步写 */
    @ColumnInfo(defaultValue = "0") val votes: Int = 0,
    /** 近期实际收听次数(v6-D-B:分类/国家排序主口径,"现在能用"的代理信号);seed/同步写 */
    @ColumnInfo(defaultValue = "0") val clickcount: Int = 0,
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
@Entity(tableName = "radio_favorites", indices = [androidx.room.Index("sortOrder")])
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

    /** 本省/省份浏览由 Repository 的别名谓词 @RawQuery 承接(v6-D-A:state 邮政罗马音脏数据,64 变体别名表) */

    /** 台名搜索(可见台) */
    @Query(
        """SELECT * FROM radio_stations
           WHERE deleted = 0 AND health = 1 AND localDeadUntil <= :now
             AND TRIM(name) LIKE '%' || :kw || '%'
             AND (:bitrateLimit = 0 OR bitrate = 0 OR bitrate <= :bitrateLimit)
           ORDER BY hotRank DESC, bitrate DESC LIMIT 200"""
    )
    suspend fun search(kw: String, now: Long, bitrateLimit: Int): List<RadioStationEntity>

    /** v6-D-A:动态谓词查询(分类/省份的 tag 与台名匹配串由 Repository 按 RadioCatalog 构建) */
    @RawQuery(observedEntities = [RadioStationEntity::class])
    suspend fun rawStations(query: SupportSQLiteQuery): List<RadioStationEntity>

    /** v6-D-A:动态谓词计数(分类卡/省份卡台数) */
    @RawQuery(observedEntities = [RadioStationEntity::class])
    suspend fun rawCount(query: SupportSQLiteQuery): Int

    @Query("SELECT * FROM radio_stations WHERE hotRank > 0 ORDER BY hotRank")
    suspend fun hotRankedAll(): List<RadioStationEntity>

    /** 国家分组(v6-D-A 第一级):可见台按国家聚合,中国钉首位,其余按总收听量降序 */
    @Query(
        """SELECT countryCode AS code, COUNT(*) AS cnt, SUM(clickcount) AS clicks
           FROM radio_stations
           WHERE deleted = 0 AND health = 1 AND localDeadUntil <= :now AND countryCode != ''
             AND (:bitrateLimit = 0 OR bitrate = 0 OR bitrate <= :bitrateLimit)
           GROUP BY countryCode
           ORDER BY (countryCode = 'CN') DESC, clicks DESC
           LIMIT :limit"""
    )
    suspend fun countryGroups(now: Long, bitrateLimit: Int, limit: Int): List<RadioCountryGroup>

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

/** 国家分组卡(v6-D-A 第一级):code=ISO 码,clicks=可见台总收听量 */
data class RadioCountryGroup(
    val code: String,
    val cnt: Int,
    val clicks: Long
)
