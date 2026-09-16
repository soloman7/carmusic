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

    private fun seedJson(rows: List<String>): java.io.ByteArrayInputStream =
        java.io.ByteArrayInputStream(
            ("[" + rows.joinToString(",") {
                """{"stationuuid":"$it","name":"台$it","url":"https://s/$it","url_resolved":"https://s/$it",""" +
                    """"homepage":"","favicon":"","tags":"","country":"China","countrycode":"CN",""" +
                    """"state":"","language":"chinese","codec":"MP3","bitrate":128,"hotRank":0,"lastcheckok":1}"""
            } + "]").toByteArray()
        )

    @Test
    fun `importSeed streams rows and assigns hot rank from seed`() = runBlocking {
        val rows = (1..2500).map { "u$it" }.mapIndexed { idx, u -> if (idx == 0) "u1:hot" else u }
        // 用带 hotRank 的合成语料
        val json = ("[" + rows.mapIndexed { idx, u ->
            """{"stationuuid":"$u","name":"台$u","url":"https://s/$u","url_resolved":"https://s/$u",""" +
                """"homepage":"","favicon":"","tags":"","country":"CN","countrycode":"CN",""" +
                """"state":"","language":"chinese","codec":"MP3","bitrate":128,"hotRank":${if (idx < 10) idx + 1 else 0},"lastcheckok":1}"""
        }.joinToString(",") + "]").toByteArray()
        repo.importSeed(java.io.ByteArrayInputStream(json), "v-test", 2500)

        assertEquals(2500, db.radioStationDao().count())
        assertEquals(10, db.radioStationDao().hotRankedAll().size)
        assertEquals(1, db.radioStationDao().hotRankedAll().first().hotRank)
    }

    @Test
    fun `seed version gate skips reimport and row merge preserves local dead state`() = runBlocking {
        // 本地已有:同一 uuid 挂账中 + 不同 uuid 已存在
        db.radioStationDao().upsertAll(
            listOf(station("u1", localDeadUntil = System.currentTimeMillis() + 3_600_000, localDeadCount = 2))
        )
        repo.importSeed(seedJson(listOf("u1", "u2")), "v1", 2)
        // 行级合并:u1 的挂账保留(F2),u2 新增
        val u1 = db.radioStationDao().getByUuid("u1")!!
        assertTrue(u1.localDeadUntil > System.currentTimeMillis())
        assertEquals(2, u1.localDeadCount)
        assertEquals(2, db.radioStationDao().count())

        // 版本门:settings 版本 == 真实 meta 版本 → ensureSeeded 命中跳过路径(Ready,不导入 58k)
        settings.setRadioSeedVersion("2026-09-16")
        repo.ensureSeeded()
        assertEquals(RadioRepository.SeedState.Ready, repo.seedState.value)
        assertEquals("版本门命中 → 没有导入 58k 全量(仍是 importSeed 的 2 行)", 2, db.radioStationDao().count())
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
