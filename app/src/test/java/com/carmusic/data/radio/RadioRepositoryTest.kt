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
 * M1b 电台仓库测试(v5 验证计划)+ v6 分类浏览扩展:
 * - seed 导入幂等 + hotRank/votes/clickcount 分配 + 行级合并(insert-if-absent,不覆盖本地挂账)
 * - 版本门命中跳过全量重导(版本串从真实资产读,不随发版漂移)
 * - 省份三路并集(v6 别名扩展:state 邮政罗马音变体)+ clickcount 排序
 * - 分类 tag 分隔符精确匹配(%,pop,% 不命中 synthpop/pop rock)+ 台名关键词双轨 + 国家 scoping
 * - LIMIT 100 / 三态可见性 / 国家分组(中国钉首位+clicks 降序)/ 省份卡同谓词计数
 * - 码率过滤(bitrate=0 放行;过滤只对显式码率生效)
 * - 挂账退避/清零、驾驶态跳台、收藏 sortOrder
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
        countryCode: String = "CN", votes: Int = 0, clickcount: Int = 0
    ) = RadioStationEntity(
        stationUuid = uuid, name = name, url = "https://s/$uuid", urlResolved = "https://s/$uuid",
        homepage = "", favicon = "", tags = tags, country = "China", countryCode = countryCode,
        state = state, language = "chinese", codec = "MP3", bitrate = bitrate,
        hotRank = hotRank, votes = votes, clickcount = clickcount, health = health, deleted = deleted,
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
        // 用带 hotRank/votes/clickcount 的合成语料
        val json = ("[" + rows.mapIndexed { idx, u ->
            """{"stationuuid":"$u","name":"台$u","url":"https://s/$u","url_resolved":"https://s/$u",""" +
                """"homepage":"","favicon":"","tags":"","country":"CN","countrycode":"CN",""" +
                """"state":"","language":"chinese","codec":"MP3","bitrate":128,"hotRank":${if (idx < 10) idx + 1 else 0},""" +
                """"votes":${if (idx < 10) idx else 0},"clickcount":${if (idx < 10) idx * 2 else 0},"lastcheckok":1}"""
        }.joinToString(",") + "]").toByteArray()
        repo.importSeed(java.io.ByteArrayInputStream(json), "v-test", 2500)

        assertEquals(2500, db.radioStationDao().count())
        assertEquals(10, db.radioStationDao().hotRankedAll().size)
        assertEquals(1, db.radioStationDao().hotRankedAll().first().hotRank)
        // v6-D-B:votes/clickcount 两列随 seed 导入
        val u3 = db.radioStationDao().getByUuid("u3")!!
        assertEquals(2, u3.votes)
        assertEquals(4, u3.clickcount)
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
        // (版本串从真实资产读,测试不随每周发版的 seed 版本字符串漂移)
        settings.setRadioSeedVersion(realSeedMetaVersion())
        repo.ensureSeeded()
        assertEquals(RadioRepository.SeedState.Ready, repo.seedState.value)
        assertEquals("版本门命中 → 没有导入 58k 全量(仍是 importSeed 的 2 行)", 2, db.radioStationDao().count())
    }

    /** 与 ensureSeeded 同口径读取真实 meta 版本 */
    private fun realSeedMetaVersion(): String {
        val json = ApplicationProvider.getApplicationContext<Context>()
            .assets.open("radio_seed_meta.json").bufferedReader().use { it.readText() }
        return Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
    }

    @Test
    fun `province matching is alias state name tags union with clickcount order`() = runBlocking {
        db.radioStationDao().upsertAll(
            listOf(
                station("a", state = "广东", clickcount = 50),
                station("b", name = "广东音乐台", clickcount = 30),
                station("c", tags = "pop,广东歌", clickcount = 10),
                station("d", state = "湖南", clickcount = 99),   // 他省不可入
                station("e", state = "Guangdong", name = "广东台", clickcount = 20), // 拼音别名命中,双路去重
                station("f", state = "Kiangsu", clickcount = 5)  // 邮政罗马音脏数据(v6-D-A)
            )
        )
        val gd = repo.provinceStations("广东")
        assertEquals(setOf("a", "b", "c", "e"), gd.map { it.stationUuid }.toSet())
        // v6-D-B 排序口径:clickcount 降序
        assertEquals(listOf("a", "b", "e", "c"), gd.map { it.stationUuid })
        // 别名谓词:Kiangsu 归江苏(旧 state 精确匹配找不到它)
        assertEquals(setOf("f"), repo.provinceStations("江苏").map { it.stationUuid }.toSet())
    }

    @Test
    fun `category tag matching is separator exact and visibility filtered`() = runBlocking {
        val now = System.currentTimeMillis()
        db.radioStationDao().upsertAll(
            listOf(
                station("p1", tags = "pop", clickcount = 1),
                station("p2", tags = "synthpop", clickcount = 1),          // 分隔符边界:不得入
                station("p3", tags = "pop rock", clickcount = 1),          // 分隔符边界:不得入
                station("p4", tags = "top 40", clickcount = 1),            // 流行的第二 tag:入
                station("p5", tags = "rock,pop", clickcount = 1),          // 逗号分隔多 tag:入
                station("p6", tags = "Pop", clickcount = 1),               // ASCII 大小写不敏感:入
                station("dead", tags = "pop", localDeadUntil = now + 3_600_000, localDeadCount = 1),
                station("down", tags = "pop", health = 0),
                station("gone", tags = "pop", deleted = true),
                station("us1", tags = "pop", countryCode = "US")
            )
        )
        val cn = repo.categoryStations("CN", "pop")
        assertEquals(setOf("p1", "p4", "p5", "p6"), cn.map { it.stationUuid }.toSet())
        // 国家 scoping:US 的 pop 台不进 CN 列表
        assertEquals(setOf("us1"), repo.categoryStations("US", "pop").map { it.stationUuid }.toSet())
    }

    @Test
    fun `category name keyword matches overseas chinese stations and orders by clickcount with limit`() = runBlocking {
        db.radioStationDao().upsertAll(
            listOf(
                // 海外中文台:tag 缺失,靠台名关键词命中「交通」(v6-D-A 双轨)
                station("kw1", name = "洛杉矶交通广播", countryCode = "US", clickcount = 5),
                station("kw2", name = "纽约交通电台", countryCode = "US", clickcount = 99),
                station("cnkw", name = "广东交通广播", countryCode = "CN", clickcount = 1),
                station("n1", name = "普通台", countryCode = "US", clickcount = 1)
            )
        )
        val traffic = repo.categoryStations("US", "traffic")
        assertEquals(setOf("kw1", "kw2"), traffic.map { it.stationUuid }.toSet())
        assertEquals("clickcount 降序", "kw2", traffic.first().stationUuid)

        // LIMIT 100(v5-C:分类=精选入口)
        db.radioStationDao().upsertAll(
            (1..120).map { station("lim$it", tags = "jazz", clickcount = it, countryCode = "CN") }
        )
        val jazz = repo.categoryStations("CN", "jazz")
        assertEquals(100, jazz.size)
        assertEquals("clickcount 最高的排第一", 120, jazz.first().clickcount)
    }

    @Test
    fun `country groups pin china first and sort by total clicks`() = runBlocking {
        db.radioStationDao().upsertAll(
            listOf(
                station("cn1", countryCode = "CN", clickcount = 10),
                station("cn2", countryCode = "CN", clickcount = 5),
                station("us1", countryCode = "US", clickcount = 100),
                station("us2", countryCode = "US", clickcount = 90),
                station("usdead", countryCode = "US", health = 0),           // 不可见不入组
                station("de1", countryCode = "DE", clickcount = 3),
                station("none", countryCode = "", clickcount = 1000)         // 空码不进国家网格
            )
        )
        val groups = repo.countryGroups()
        assertEquals("中国钉首位(v6-D-A)", "CN", groups.first().code)
        assertEquals("其后按总收听量降序", listOf("US", "DE"), groups.drop(1).map { it.code })
        assertEquals(2, groups.first().cnt)
        assertEquals(2, groups[1].cnt)
        assertEquals("中国", groups.first().displayName)
        assertEquals("美国", groups[1].displayName)
        assertEquals("🇺🇸", groups[1].flag)
    }

    @Test
    fun `province groups count with same alias predicate and drop empty`() = runBlocking {
        db.radioStationDao().upsertAll(
            listOf(
                station("g1", state = "广东", clickcount = 1),
                station("g2", state = "Kwangtung", clickcount = 1),
                station("g3", state = "Kiangsu", clickcount = 1),
                station("g4", state = "", name = "无省台")          // 无省不入省卡
            )
        )
        val groups = repo.provinceGroups()
        val byKey = groups.associate { it.key to it.cnt }
        assertEquals(2, byKey["广东"])
        assertEquals(1, byKey["江苏"])
        assertTrue("零台省份不进网格", byKey["西藏"] == null)
        assertEquals("按台数降序", "广东", groups.first().key)
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
