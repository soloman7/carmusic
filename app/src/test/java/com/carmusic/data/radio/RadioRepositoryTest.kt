package com.carmusic.data.radio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.carmusic.data.AppDatabase
import com.carmusic.data.SettingsRepository
import com.carmusic.drive.DrivingDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M1b 电台仓库测试(v5 验证计划):
 * - seed 导入幂等 + top 段 hotRank 分配 + 新鲜度规则(insert-if-absent,不覆盖本地挂账)
 * - 本省三路并集(state/name/tags)去重
 * - 码率过滤(bitrate=0 放行;CN 语料 89% 为 0,过滤只对显式码率生效)
 * - 三态可见性(health/deleted/localDeadUntil)+ 挂账退避/清零
 * - 驾驶态跳台:跳过不可见收藏、循环回绕
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RadioRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        settings = SettingsRepository(context)
        repo = RadioRepository(
            context, db, settings, DrivingDetector(context),
            OkHttpClient.Builder().build()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun station(
        uuid: String, name: String = "台$uuid", state: String = "", tags: String = "",
        bitrate: Int = 128, health: Int = 1, deleted: Boolean = false,
        localDeadUntil: Long = 0, localDeadCount: Int = 0, hotRank: Int = 0,
        countryCode: String = "CN"
    ) = RadioStationEntity(
        stationUuid = uuid, name = name, url = "https://s/$uuid", urlResolved = "https://s/$uuid",
        homepage = "", favicon = "", tags = tags, country = "China", countryCode = countryCode,
        state = state, language = "chinese", codec = "MP3", bitrate = bitrate,
        hotRank = hotRank, health = health, deleted = deleted,
        localDeadUntil = localDeadUntil, localDeadCount = localDeadCount
    )

    @Test
    fun `seed imports full corpus once and is idempotent`() = runBlocking {
        repo.ensureSeeded()
        assertEquals(RadioRepository.SeedState.Ready, repo.seedState.value)
        val first = db.radioStationDao().count()
        assertTrue("seed 应导入全量语料(实测 ~3323)", first >= 3000)
        val hot = db.radioStationDao().hotRankedAll()
        assertEquals("top 段 1000 台应有 hotRank", 1000, hot.size)
        assertEquals(1, hot.first().hotRank)

        repo.ensureSeeded()   // 第二次:幂等
        assertEquals(first, db.radioStationDao().count())
    }

    @Test
    fun `seed is insert-if-absent and never touches pre-existing rows`() = runBlocking {
        // 模拟同步过的本地状态:挂账中
        db.radioStationDao().upsertAll(
            listOf(station("local-1", localDeadUntil = System.currentTimeMillis() + 3_600_000, localDeadCount = 1))
        )
        repo.ensureSeeded()
        // count>0 → seed 整体跳过(新鲜度规则的结构化实现),本地挂账原样保留
        val row = db.radioStationDao().getByUuid("local-1")
        assertNotNull(row)
        assertTrue(row!!.localDeadUntil > System.currentTimeMillis())
        assertEquals(1, row.localDeadCount)
    }

    @Test
    fun `province matching is a state name tags union with dedup`() = runBlocking {
        db.radioStationDao().upsertAll(
            listOf(
                station("a", state = "广东"),
                station("b", name = "广东音乐台"),
                station("c", tags = "pop,广东歌"),
                station("d", state = "湖南"),
                station("e", state = "广东", name = "广东台")   // 双路命中,去重
            )
        )
        val result = repo.provinceStations("广东")
        val ids = result.map { it.stationUuid }.toSet()
        assertEquals(setOf("a", "b", "c", "e"), ids)
        // 精确 state 命中排前
        assertEquals("a", result.first().stationUuid)
    }

    @Test
    fun `bitrate filter passes unknown and hides over-limit`() = runBlocking {
        db.radioStationDao().upsertAll(
            listOf(
                station("u0", bitrate = 0),      // 未知码率:放行
                station("u64", bitrate = 64),
                station("u320", bitrate = 320)
            )
        )
        settings.setRadioBitrateLimit(128)       // 过滤值先设置(默认 0 = 不过滤)
        val visible = repo.searchStations("台").map { it.stationUuid }
        assertTrue("u0" in visible && "u64" in visible)
        assertTrue("320k 超限台被过滤", "u320" !in visible)
    }

    @Test
    fun `local dead backoff escalates and clears on user tap`() = runBlocking {
        db.radioStationDao().upsertAll(listOf(station("dead-1")))
        repo.markLocalDead("dead-1")
        val afterFirst = db.radioStationDao().getByUuid("dead-1")!!
        assertTrue(afterFirst.localDeadUntil > System.currentTimeMillis())
        assertEquals(1, afterFirst.localDeadCount)

        repo.clearLocalDead("dead-1")
        val cleared = db.radioStationDao().getByUuid("dead-1")!!
        assertEquals(0, cleared.localDeadUntil)
        assertEquals(0, cleared.localDeadCount)
    }

    @Test
    fun `favorites status maps three visibility states`() = runBlocking {
        val now = System.currentTimeMillis()
        db.radioStationDao().upsertAll(
            listOf(
                station("ok-1"),
                station("dead-1", localDeadUntil = now + 3_600_000, localDeadCount = 1),
                station("down-1", health = 0),
                station("gone-1", deleted = true)
            )
        )
        listOf("ok-1", "dead-1", "down-1", "gone-1").forEach { repo.toggleFavorite(it) }

        val statuses = repo.favoritesWithStatusFlow().first().associate { it.favorite.stationUuid to it.status }
        assertEquals(RadioRepository.StationStatus.OK, statuses["ok-1"])
        assertEquals(RadioRepository.StationStatus.LOCAL_DEAD, statuses["dead-1"])
        assertEquals(RadioRepository.StationStatus.SERVER_DOWN, statuses["down-1"])
        assertEquals(RadioRepository.StationStatus.DELETED, statuses["gone-1"])
    }

    @Test
    fun `next visible favorite skips invisible and wraps around`() = runBlocking {
        val now = System.currentTimeMillis()
        db.radioStationDao().upsertAll(
            listOf(
                station("f1"),
                station("f2", localDeadUntil = now + 3_600_000, localDeadCount = 1),  // 挂账不可见
                station("f3")
            )
        )
        listOf("f1", "f2", "f3").forEach { repo.toggleFavorite(it) }

        assertEquals("f1 之后可见的下一个是 f3(跳过挂账的 f2)", "f3", repo.nextVisibleFavoriteAfter("f1")?.stationUuid)
        assertEquals("f3 之后循环回 f1", "f1", repo.nextVisibleFavoriteAfter("f3")?.stationUuid)
        assertEquals("不在收藏中的 uuid → 第一可见台", "f1", repo.nextVisibleFavoriteAfter("nope")?.stationUuid)
    }

    @Test
    fun `toggle favorite inserts with incrementing sort order`() = runBlocking {
        db.radioStationDao().upsertAll(listOf(station("x1"), station("x2")))
        assertTrue(repo.toggleFavorite("x1"))
        assertTrue(repo.toggleFavorite("x2"))
        assertEquals(2, db.radioFavoriteDao().count())
        assertEquals(1, db.radioFavoriteDao().getByUuid("x1")!!.sortOrder)
        assertEquals(2, db.radioFavoriteDao().getByUuid("x2")!!.sortOrder)
        assertFalse(repo.toggleFavorite("x2"))   // 取消
        assertEquals(1, db.radioFavoriteDao().count())
    }
}
