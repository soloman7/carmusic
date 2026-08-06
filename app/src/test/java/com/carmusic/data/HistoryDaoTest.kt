package com.carmusic.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * HistoryDao 的 Room 内存库测试（Robolectric）。
 * 重点验证 insertAndTrim 的事务性裁剪：只保留最新 200 条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: HistoryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()  // 仅测试用，简化协程调度
            .build()
        dao = db.historyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(trackId: String, playedAt: Long) = HistoryEntity(
        trackId = trackId,
        platform = trackId.substringBefore(':'),
        songId = trackId.substringAfter(':'),
        title = "标题 $trackId",
        artist = "歌手",
        album = "专辑",
        coverUrl = null,
        duration = 180,
        playedAt = playedAt
    )

    /** 读全部历史（limit 给足），按 playedAt 倒序返回 */
    private suspend fun readAll(limit: Int = 1000): List<HistoryEntity> =
        dao.getRecentFlow(limit).first()

    @Test
    fun `insert then recent flow returns rows ordered by playedAt desc`() = runTest {
        dao.insert(entity("netease:1", playedAt = 100L))
        dao.insert(entity("qq:2", playedAt = 300L))
        dao.insert(entity("migu:3", playedAt = 200L))

        assertEquals(listOf("qq:2", "migu:3", "netease:1"), readAll().map { it.trackId })
    }

    @Test
    fun `insert with same trackId replaces existing row`() = runTest {
        dao.insert(entity("netease:1", playedAt = 100L))
        dao.insert(entity("netease:1", playedAt = 500L).copy(title = "重播"))

        val all = readAll()
        assertEquals(1, all.size)
        assertEquals("重播", all[0].title)
        assertEquals(500L, all[0].playedAt)
    }

    @Test
    fun `insertAndTrim keeps only newest 200 of 210 rows`() = runTest {
        // playedAt 从 1 递增到 210，trackId 与序号一一对应
        for (i in 1L..210L) {
            dao.insertAndTrim(entity("netease:$i", playedAt = i))
        }

        val all = readAll()
        assertEquals(200, all.size)

        val remainingIds = all.map { it.trackId }.toSet()
        // 最旧的 10 条（playedAt 1..10）被裁掉
        for (i in 1L..10L) {
            assertFalse("最旧的 netease:$i 应被裁剪", "netease:$i" in remainingIds)
        }
        // 最新的 200 条（playedAt 11..210）全部保留
        for (i in 11L..210L) {
            assertTrue("netease:$i 应保留", "netease:$i" in remainingIds)
        }
        // 顺序仍为 playedAt 倒序：第一条是最新插入的
        assertEquals("netease:210", all.first().trackId)
        assertEquals("netease:11", all.last().trackId)
    }

    @Test
    fun `insertAndTrim below capacity keeps everything`() = runTest {
        for (i in 1L..50L) {
            dao.insertAndTrim(entity("netease:$i", playedAt = i))
        }
        assertEquals(50, readAll().size)
    }

    @Test
    fun `insertAndTrim respects custom keep size`() = runTest {
        for (i in 1L..10L) {
            dao.insertAndTrim(entity("netease:$i", playedAt = i), keep = 5)
        }
        assertEquals(
            listOf("netease:10", "netease:9", "netease:8", "netease:7", "netease:6"),
            readAll().map { it.trackId }
        )
    }

    @Test
    fun `insertAndTrim on replayed track does not grow the table`() = runTest {
        // 同一首歌反复播放：REPLACE 语义下总行数不变，playedAt 更新
        for (i in 1L..5L) {
            dao.insertAndTrim(entity("netease:1", playedAt = i))
        }
        val all = readAll()
        assertEquals(1, all.size)
        assertEquals(5L, all[0].playedAt)
    }

    @Test
    fun `deleteById and clearAll work`() = runTest {
        dao.insert(entity("netease:1", playedAt = 1L))
        dao.insert(entity("qq:2", playedAt = 2L))

        dao.deleteById("netease:1")
        assertEquals(listOf("qq:2"), readAll().map { it.trackId })

        dao.clearAll()
        assertEquals(0, readAll().size)
    }
}
