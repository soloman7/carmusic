package com.carmusic.data.radio

import android.content.Context
import android.util.Log
import com.carmusic.data.AppDatabase
import com.carmusic.data.SettingsRepository
import com.carmusic.drive.DrivingDetector
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import kotlin.math.abs

/**
 * 电台仓库(v5-D1 local-first):本地 Room 为唯一浏览/搜索来源,API 只是数据更新器。
 *
 * - seed:APK 内置 assets/radio_seed.json(与同步同口径语料),首启 insert-if-absent;
 *   health/deleted/localDeadUntil 永不接受 seed 降级(v3 审查缺陷 3 修复)。
 * - 同步:确定性分页全量重拉(CN search + topvote)+ 三态 diff + 消失嫌疑队列(FIFO 500)。
 *   changed 端点冻结 243 天(〇表 B),禁用;byuuid 只留给 M3 复验 worker。
 * - 墓碑唯一来源 = M3 复验 worker 的 byuuid diff;同步里的"消失"只怀疑不处决。
 */
class RadioRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val drivingDetector: DrivingDetector,
    okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "RadioRepository"
        private const val SEED_ASSET = "radio_seed.json"
        private const val SYNC_INTERVAL_MS = 7L * 24 * 3600 * 1000
        private const val PAGE_SIZE = 500
        private const val PAGE_BUDGET_MS = 150_000L      // v5-C3:p95 = 单点实测×3
        private const val PAGE_RETRIES = 2
        private const val TOPVOTE_PAGE = 250
        private const val TOPVOTE_TOTAL = 1000
        private const val MAX_SUSPECTS = 500
        private const val COMPLETENESS_RATIO = 0.8       // v5-P1:行数下限防截断静默入库
        private const val CLICK_THROTTLE_MS = 3600_000L
        private val UA = "carmusic/3.5.0 (github.com/soloman7/carmusic)"

        /** 镜像候选(v5:轮转,last-known-good 置前;"仅 de1"是带日期的观测,非常量) */
        private val MIRRORS = listOf(
            "https://de1.api.radio-browser.info",
            "https://de2.api.radio-browser.info",
            "https://all.api.radio-browser.info",
            "https://fr1.api.radio-browser.info",
            "https://fi1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
        )

        /** 省/直辖市/自治区 质心(GPS 最近省份推荐,离线) */
        val PROVINCES = listOf(
            "北京" to (39.9 to 116.4), "上海" to (31.2 to 121.5), "天津" to (39.1 to 117.2),
            "重庆" to (29.6 to 106.5), "河北" to (38.0 to 114.5), "山西" to (37.9 to 112.5),
            "内蒙古" to (40.8 to 111.7), "辽宁" to (41.8 to 123.4), "吉林" to (43.9 to 125.3),
            "黑龙江" to (45.8 to 126.5), "江苏" to (32.1 to 118.8), "浙江" to (30.3 to 120.2),
            "安徽" to (31.9 to 117.3), "福建" to (26.1 to 119.3), "江西" to (28.7 to 115.9),
            "山东" to (36.7 to 117.0), "河南" to (34.8 to 113.7), "湖北" to (30.6 to 114.3),
            "湖南" to (28.2 to 113.0), "广东" to (23.1 to 113.3), "广西" to (22.8 to 108.3),
            "海南" to (20.0 to 110.3), "四川" to (30.7 to 104.1), "贵州" to (26.6 to 106.7),
            "云南" to (25.0 to 102.7), "西藏" to (29.7 to 91.1), "陕西" to (34.3 to 108.9),
            "甘肃" to (36.1 to 103.8), "青海" to (36.6 to 101.8), "宁夏" to (38.5 to 106.3),
            "新疆" to (43.8 to 87.6), "香港" to (22.3 to 114.2), "澳门" to (22.2 to 113.5),
            "台湾" to (25.0 to 121.5)
        )
    }

    /** 本地死台指数退避档(24h/72h/7d);localDeadCount 计数列支撑,M3 worker 接管后统一管理 */
    private val deadBackoffMs = listOf(24L * 3600_000, 72L * 3600_000, 7L * 24 * 3600_000)

    private val gson = Gson()

    /** 轻调用档(健康检查/click 上报):5s connect + 10s total */
    private val lightClient = okHttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(10))
        .callTimeout(Duration.ofSeconds(10))
        .build()

    /** 语料分页档:5s connect + 150s total/页(v5-C3) */
    private val corpusClient = okHttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(150))
        .callTimeout(Duration.ofSeconds(150))
        .build()

    private val stationDao get() = db.radioStationDao()
    private val favoriteDao get() = db.radioFavoriteDao()

    // ---- seed 导入状态(UI 骨架态/错误态) ----
    sealed class SeedState {
        data object Loading : SeedState()
        data object Ready : SeedState()
        data class Error(val message: String) : SeedState()
    }

    private val _seedState = MutableStateFlow<SeedState>(SeedState.Loading)
    val seedState: StateFlow<SeedState> = _seedState.asStateFlow()

    private val seedMutex = Mutex()

    /** 首启导入:insert-if-absent;health/deleted/localDeadUntil/localDeadCount 永不接受 seed 降级 */
    suspend fun ensureSeeded() = seedMutex.withLock {
        if (_seedState.value is SeedState.Ready) return@withLock
        try {
            if (stationDao.count() == 0) {
                withContext(Dispatchers.IO) {
                    val json = context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() }
                    val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
                    val raw: List<Map<String, Any?>> = gson.fromJson(json, type)
                    val entities = raw.map { row ->
                        RadioStationEntity(
                            stationUuid = row["stationuuid"]?.toString() ?: "",
                            name = row["name"]?.toString() ?: "",
                            url = row["url"]?.toString() ?: "",
                            urlResolved = row["url_resolved"]?.toString() ?: "",
                            homepage = row["homepage"]?.toString() ?: "",
                            favicon = row["favicon"]?.toString() ?: "",
                            tags = row["tags"]?.toString() ?: "",
                            country = row["country"]?.toString() ?: "",
                            countryCode = row["countrycode"]?.toString() ?: "",
                            state = row["state"]?.toString() ?: "",
                            language = row["language"]?.toString() ?: "",
                            codec = row["codec"]?.toString() ?: "",
                            bitrate = (row["bitrate"] as? Double)?.toInt() ?: 0,
                            hotRank = (row["hotRank"] as? Double)?.toInt() ?: 0,
                            health = 1, deleted = false, localDeadUntil = 0, localDeadCount = 0
                        )
                    }.filter { it.stationUuid.isNotBlank() }
                    // seed 文件结构 = [CN 段(含 9 个高票 CN 台也出现在 top 段)] + [top 段]
                    // top 段边界 = 第一个非 CN 行(9 个 CN∩top 台按票数排在 top 段头部)
                    val boundary = entities.indexOfFirst { it.countryCode != "CN" }
                    val cnCount = if (boundary == -1) entities.size else boundary
                    val withRank = entities.mapIndexed { idx, e ->
                        if (boundary != -1 && idx >= boundary) e.copy(hotRank = idx - boundary + 1) else e
                    }
                    stationDao.upsertAll(withRank)
                    // 行数断言分母的 seed 基准(分母本地可得,C7)
                    settings.setRadioLastSyncCnRows(cnCount)
                }
            }
            _seedState.value = SeedState.Ready
        } catch (e: Exception) {
            Log.w(TAG, "seed import failed: ${e.message}")
            _seedState.value = SeedState.Error(e.message ?: "导入失败")
        }
    }

    // ---- 同步 ----

    sealed class SyncResult {
        data object Success : SyncResult()
        data class Aborted(val reason: String) : SyncResult()
        data object SkippedNoMirror : SyncResult()
    }

    private val syncMutex = Mutex()

    /** 周期同步入口:距上次 >7 天;逐页事务,失败/中断静默放弃本轮(local-first 兜底) */
    suspend fun syncIfDue() {
        if (_seedState.value !is SeedState.Ready) return
        val last = settings.radioLastSyncAt.first()
        if (System.currentTimeMillis() - last < SYNC_INTERVAL_MS) return
        syncMutex.withLock { doSync() }
    }

    /** 强制同步(测试/调试用):绕过 7 天窗口 */
    suspend fun syncNow(): SyncResult = syncMutex.withLock { doSync() }

    private data class ApiStation(
        val stationuuid: String? = null,
        val name: String? = null,
        val url: String? = null,
        val url_resolved: String? = null,
        val homepage: String? = null,
        val favicon: String? = null,
        val tags: String? = null,
        val country: String? = null,
        val countrycode: String? = null,
        val state: String? = null,
        val language: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        val lastcheckok: Int? = null
    )

    private suspend fun doSync(): SyncResult {
        val mirror = pickMirror()
            ?: return SyncResult.SkippedNoMirror.also { Log.w(TAG, "sync aborted: no mirror alive") }
        settings.setRadioMirrorLastGood(mirror)
        val type = object : TypeToken<List<ApiStation>>() {}.type

        // a. CN 全量(不带 hidebroken;显式 limit=v5-P1 修复;分页 500;行数完整性断言)
        val cnRows = mutableListOf<ApiStation>()
        var offset = 0
        while (true) {
            val page = fetchPage(mirror, "/json/stations/search", mapOf(
                "countrycode" to "CN", "limit" to PAGE_SIZE, "offset" to offset,
                "order" to "stationuuid"), type, offset)
                ?: return SyncResult.Aborted("CN page @$offset unreachable")
            cnRows += page
            offset += PAGE_SIZE
            if (page.size < PAGE_SIZE || offset > 10000) break
        }
        val denom = maxOf(settings.radioLastSyncCnRows.first(), 1)
        if (cnRows.size < denom * COMPLETENESS_RATIO) {
            return SyncResult.Aborted("CN rows ${cnRows.size} < $COMPLETENESS_RATIO×$denom, possible truncation")
        }
        // b. 全球 top1000(hidebroken,分页 v5-C2)
        val topRows = mutableListOf<ApiStation>()
        offset = 0
        while (offset < TOPVOTE_TOTAL) {
            val page = fetchPage(mirror, "/json/stations/topvote", mapOf(
                "hidebroken" to "true", "limit" to TOPVOTE_PAGE, "offset" to offset), type, offset)
                ?: return SyncResult.Aborted("topvote page @$offset unreachable")
            topRows += page
            offset += TOPVOTE_PAGE
            if (page.size < TOPVOTE_PAGE) break
        }

        // c. 三态 diff(health 可写;deleted/localDeadUntil 同步禁触——F2)
        val before = stationDao.allUuids().toSet()
        val preserved = stationDao.localStateSnapshot().associate { it.stationUuid to it }
        val entities = (cnRows + topRows).mapNotNull { s ->
            val uuid = s.stationuuid ?: return@mapNotNull null
            val prev = preserved[uuid]
            RadioStationEntity(
                stationUuid = uuid,
                name = s.name ?: "",
                url = s.url ?: "",
                urlResolved = s.url_resolved ?: "",
                homepage = s.homepage ?: "",
                favicon = s.favicon ?: "",
                tags = s.tags ?: "",
                country = s.country ?: "",
                countryCode = s.countrycode ?: "",
                state = s.state ?: "",
                language = s.language ?: "",
                codec = s.codec ?: "",
                bitrate = s.bitrate ?: 0,
                hotRank = 0,
                health = s.lastcheckok ?: 1,
                deleted = prev?.deleted ?: false,
                localDeadUntil = prev?.localDeadUntil ?: 0,
                localDeadCount = prev?.localDeadCount ?: 0
            )
        }.filter { it.stationUuid.isNotBlank() }
        stationDao.upsertAll(entities)

        // hotRank:b 段排名(reset + 逐台写)
        stationDao.resetHotRanks()
        topRows.mapNotNull { it.stationuuid }.forEachIndexed { idx, uuid ->
            stationDao.setHotRank(uuid, idx + 1)
        }

        // 消失嫌疑(a∪b 中消失):只怀疑不处决,入 FIFO 队列(上限 500)
        val after = entities.map { it.stationUuid }.toSet()
        val disappeared = before - after
        if (disappeared.isNotEmpty()) {
            val existing = settings.radioSyncSuspects.first()
            val merged = (existing + disappeared).toList().takeLast(MAX_SUSPECTS).toSet()
            settings.setRadioSyncSuspects(merged)
        }

        settings.setRadioLastSyncAt(System.currentTimeMillis())
        settings.setRadioLastSyncCnRows(cnRows.size)
        settings.setRadioLastSyncTotalRows(entities.size)
        Log.i(TAG, "radio sync done: cn=${cnRows.size} total=${entities.size} suspects=${disappeared.size}")
        return SyncResult.Success
    }

    /** 单页拉取:150s 预算 + 重试 ×2(upsert 幂等,重试零成本) */
    private suspend fun fetchPage(
        mirror: String, path: String, params: Map<String, Any>,
        type: java.lang.reflect.Type, offset: Int
    ): List<ApiStation>? {
        val query = params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value.toString(), "UTF-8")}"
        }
        repeat(PAGE_RETRIES + 1) { attempt ->
            try {
                val request = Request.Builder().url("$mirror$path?$query")
                    .header("User-Agent", UA).build()
                corpusClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "page $path@$offset attempt $attempt HTTP ${resp.code}")
                        null
                    } else {
                        val body = resp.body?.string()
                        if (body == null) null
                        else return gson.fromJson<List<ApiStation>>(body, type) ?: emptyList()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "page fetch attempt $attempt failed: ${e.message}")
            }
        }
        return null
    }

    /** 镜像轮转:last-known-good 置前,健康检查轻调用档(5s/10s) */
    private suspend fun pickMirror(): String? {
        val lastGood = settings.radioMirrorLastGood.first()
        val candidates = (listOf(lastGood) + MIRRORS).filter { it.isNotBlank() }.distinct()
        for (mirror in candidates) {
            try {
                lightClient.newCall(
                    Request.Builder().url("$mirror/json/stats")
                        .header("User-Agent", UA).build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) return mirror
                }
            } catch (e: Exception) {
                Log.d(TAG, "mirror $mirror dead: ${e.message}")
            }
        }
        return null
    }

    // ---- 浏览查询(全部本地;码率过滤;收藏页不过滤) ----

    suspend fun provinceStations(province: String): List<RadioStationEntity> {
        val limit = settings.radioBitrateLimit.first()
        return stationDao.byProvince(province, System.currentTimeMillis(), limit)
    }

    suspend fun searchStations(kw: String): List<RadioStationEntity> {
        val trimmed = kw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val limit = settings.radioBitrateLimit.first()
        return stationDao.search(trimmed, System.currentTimeMillis(), limit)
    }

    suspend fun hotStations(limit: Int = 50): List<RadioStationEntity> {
        val bl = settings.radioBitrateLimit.first()
        return stationDao.hot(limit, System.currentTimeMillis(), bl)
    }

    suspend fun station(uuid: String): RadioStationEntity? = stationDao.getByUuid(uuid)

    // ---- 收藏(收藏页永远全量显示,异常台徽标态——过滤不作用于收藏,C4) ----

    fun favoritesFlow(): Flow<List<RadioFavoriteEntity>> = favoriteDao.getAllFlow()

    /** 收藏页徽标态(C4):收藏页永远全量显示,异常台带"点击重试/已失效"徽标 */
    enum class StationStatus { OK, LOCAL_DEAD, SERVER_DOWN, DELETED }

    data class FavoriteWithStatus(val favorite: RadioFavoriteEntity, val status: StationStatus)

    fun favoritesWithStatusFlow(): Flow<List<FavoriteWithStatus>> =
        favoriteDao.getAllFlow().map { favs ->
            val now = System.currentTimeMillis()
            favs.map { f ->
                val s = stationDao.getByUuid(f.stationUuid)
                val status = when {
                    s == null || s.deleted -> StationStatus.DELETED
                    s.health == 0 -> StationStatus.SERVER_DOWN
                    s.localDeadUntil > now -> StationStatus.LOCAL_DEAD
                    else -> StationStatus.OK
                }
                FavoriteWithStatus(f, status)
            }
        }

    /** 驾驶态(UI 搜索禁用/错误路径分支) */
    val isDriving: StateFlow<Boolean> get() = drivingDetector.isDriving

    suspend fun favorites(): List<RadioFavoriteEntity> = favoriteDao.getAll()

    suspend fun favorite(uuid: String): RadioFavoriteEntity? = favoriteDao.getByUuid(uuid)

    /** @return true=已收藏,false=已取消 */
    suspend fun toggleFavorite(uuid: String): Boolean {
        val existing = favoriteDao.getByUuid(uuid)
        if (existing != null) {
            favoriteDao.deleteByUuid(uuid)
            return false
        }
        val station = stationDao.getByUuid(uuid) ?: return false
        val max = favoriteDao.maxSortOrder() ?: 0
        favoriteDao.upsert(
            RadioFavoriteEntity(
                stationUuid = uuid, sortOrder = max + 1, addedAt = System.currentTimeMillis(),
                name = station.name, url = station.url, urlResolved = station.playUrl,
                favicon = station.favicon, codec = station.codec, bitrate = station.bitrate
            )
        )
        return true
    }

    // ---- 本地死台挂账(D5 错误路径,指数退避 24h/72h/7d) ----

    suspend fun markLocalDead(uuid: String) {
        val station = stationDao.getByUuid(uuid) ?: return
        val next = (station.localDeadCount + 1).coerceAtMost(deadBackoffMs.size)
        val until = System.currentTimeMillis() + deadBackoffMs[next - 1]
        stationDao.upsertAll(listOf(station.copy(localDeadUntil = until, localDeadCount = next)))
    }

    /** 用户手点异常台(徽标)→ 清零重试(用户意图优先) */
    suspend fun clearLocalDead(uuid: String) {
        val station = stationDao.getByUuid(uuid) ?: return
        if (station.localDeadUntil == 0L && station.localDeadCount == 0) return
        stationDao.upsertAll(listOf(station.copy(localDeadUntil = 0, localDeadCount = 0)))
    }

    /** 驾驶态(D5 错误路径分支用) */
    fun isDrivingNow(): Boolean = drivingDetector.isDriving.value

    // ---- GPS 省份推荐(离线质心) ----

    suspend fun suggestedProvince(): String? {
        val loc = drivingDetector.lastKnownLocation.value ?: return null
        return PROVINCES.minByOrNull { (_, latLon) ->
            val (lat, lon) = latLon
            abs(lat - loc.latitude) + abs(lon - loc.longitude)
        }?.first
    }

    // ---- click 上报(轻调用档 5s/10s,起播成功后异步,节流 1h/台) ----

    private val clickAt = mutableMapOf<String, Long>()

    suspend fun reportClick(uuid: String) {
        val now = System.currentTimeMillis()
        synchronized(clickAt) {
            if (now - (clickAt[uuid] ?: 0) < CLICK_THROTTLE_MS) return
            clickAt[uuid] = now
        }
        val mirror = pickMirror() ?: return
        try {
            lightClient.newCall(
                Request.Builder().url("$mirror/json/url/$uuid")
                    .header("User-Agent", UA).build()
            ).execute().use { /* fire-and-forget,节流防污染 topclick */ }
        } catch (e: Exception) {
            Log.d(TAG, "click report failed: ${e.message}")
        }
    }

    // ---- 驾驶态跳台(D5):下一可见收藏台 ----

    suspend fun nextVisibleFavoriteAfter(uuid: String): RadioFavoriteEntity? {
        val favs = favoriteDao.getAll()
        if (favs.isEmpty()) return null
        val now = System.currentTimeMillis()
        val visible = favs.filter { f ->
            val s = stationDao.getByUuid(f.stationUuid)
            s == null || (s.health == 1 && !s.deleted && s.localDeadUntil <= now)
        }
        if (visible.isEmpty()) return null
        val idx = favs.indexOfFirst { it.stationUuid == uuid }
        if (idx == -1) return visible.first()
        val startInVisible = visible.indexOfFirst { it.stationUuid == favs[idx].stationUuid }
        return visible[(startInVisible + 1) % visible.size]
    }
}
