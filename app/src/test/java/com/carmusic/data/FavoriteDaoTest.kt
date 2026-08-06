package com.carmusic.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * FavoriteDao 的 Room 内存库测试（Robolectric，无需真机）。
 * 覆盖基础增删查 + toggleFavorite 原子切换的幂等性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FavoriteDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()  // 仅测试用，简化协程调度
            .build()
        dao = db.favoriteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(trackId: String, addedAt: Long = 0L) = FavoriteEntity(
        trackId = trackId,
        platform = trackId.substringBefore(':'),
        songId = trackId.substringAfter(':'),
        title = "标题 $trackId",
        artist = "歌手",
        album = "专辑",
        coverUrl = null,
        duration = 180,
        addedAt = addedAt
    )

    @Test
    fun `insert then isFavorite returns true`() = runTest {
        val e = entity("netease:1")
        assertFalse(dao.isFavorite(e.trackId))

        dao.insert(e)

        assertTrue(dao.isFavorite(e.trackId))
        assertEquals(listOf(e), dao.getAll())
    }

    @Test
    fun `insert with same trackId replaces existing row`() = runTest {
        dao.insert(entity("netease:1", addedAt = 100L))
        dao.insert(entity("netease:1", addedAt = 200L).copy(title = "新标题"))

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("新标题", all[0].title)
        assertEquals(200L, all[0].addedAt)
    }

    @Test
    fun `getAll orders by addedAt descending`() = runTest {
        dao.insert(entity("netease:1", addedAt = 100L))
        dao.insert(entity("qq:2", addedAt = 300L))
        dao.insert(entity("migu:3", addedAt = 200L))

        assertEquals(listOf("qq:2", "migu:3", "netease:1"), dao.getAll().map { it.trackId })
    }

    @Test
    fun `deleteById removes only the target row`() = runTest {
        dao.insert(entity("netease:1"))
        dao.insert(entity("qq:2"))

        dao.deleteById("netease:1")

        assertFalse(dao.isFavorite("netease:1"))
        assertTrue(dao.isFavorite("qq:2"))
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `deleteById on missing row is a no-op`() = runTest {
        dao.deleteById("netease:404")
        assertEquals(0, dao.getAll().size)
    }

    // ---- toggleFavorite（原子切换契约：返回切换后的状态）----

    @Test
    fun `toggleFavorite on absent track inserts and returns true`() = runTest {
        val e = entity("netease:1")

        val state = dao.toggleFavorite(e)

        assertTrue(state)
        assertTrue(dao.isFavorite(e.trackId))
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `toggleFavorite on existing track deletes and returns false`() = runTest {
        val e = entity("netease:1")
        dao.insert(e)

        val state = dao.toggleFavorite(e)

        assertFalse(state)
        assertFalse(dao.isFavorite(e.trackId))
        assertEquals(0, dao.getAll().size)
    }

    @Test
    fun `toggleFavorite twice returns to original state`() = runTest {
        val e = entity("netease:1")

        val first = dao.toggleFavorite(e)
        val second = dao.toggleFavorite(e)

        assertTrue(first)
        assertFalse(second)
        // 幂等：连切两次回到最初状态（未收藏）
        assertFalse(dao.isFavorite(e.trackId))
        assertEquals(0, dao.getAll().size)

        // 再切两次依然成立
        assertTrue(dao.toggleFavorite(e))
        assertFalse(dao.toggleFavorite(e))
        assertFalse(dao.isFavorite(e.trackId))
    }

    @Test
    fun `toggleFavorite does not affect other rows`() = runTest {
        val target = entity("netease:1")
        val other = entity("qq:2")
        dao.insert(other)

        dao.toggleFavorite(target)
        dao.toggleFavorite(target)

        assertTrue(dao.isFavorite(other.trackId))
        assertEquals(1, dao.getAll().size)
    }
}
