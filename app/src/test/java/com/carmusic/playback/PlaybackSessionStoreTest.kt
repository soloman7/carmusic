package com.carmusic.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.carmusic.data.AppDatabase
import com.carmusic.source.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放会话存取(v3.9 拆分回归):encode/decode + 空队列拒绝 + 索引/进度钳制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSessionStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: PlaybackSessionStore

    private fun track(id: String) = Track(platform = "netease", id = id, title = "歌$id", artist = "人$id")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = PlaybackSessionStore(db.playbackStateDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `load returns null on empty database`() = runBlocking {
        assertNull(store.load())
    }

    @Test
    fun `save and load roundtrip preserves queue index position`() = runBlocking {
        val queue = listOf(track("a"), track("b"), track("c"))
        store.save(queue, index = 1, positionMs = 42_000)
        val snap = store.load()!!
        assertEquals(listOf("a", "b", "c"), snap.queue.map { it.id })
        assertEquals(1, snap.index)
        assertEquals(42_000, snap.positionMs)
    }

    @Test
    fun `save rejects empty queue and keeps previous snapshot`() = runBlocking {
        store.save(listOf(track("keep")), 0, 100)
        store.save(emptyList(), 0, 0)
        assertEquals("空队列不得覆盖有效存档", listOf("keep"), store.load()!!.queue.map { it.id })
    }

    @Test
    fun `load clamps out of range index and negative position`() = runBlocking {
        val queue = listOf(track("a"), track("b"))
        // 直写 DAO 模拟历史脏数据:越界索引 + 负进度
        db.playbackStateDao().upsert(
            com.carmusic.data.PlaybackStateEntity(
                queueJson = PlaybackSessionCodec.encode(queue),
                currentIndex = 99, positionMs = -5, savedAt = 1
            )
        )
        val snap = store.load()!!
        assertEquals(1, snap.index)
        assertEquals(0, snap.positionMs)
        assertTrue(snap.queue.isNotEmpty())
    }

    @Test
    fun `load returns null on undecodable payload`() = runBlocking {
        db.playbackStateDao().upsert(
            com.carmusic.data.PlaybackStateEntity(
                queueJson = "not-a-track-array", currentIndex = 0, positionMs = 0, savedAt = 1
            )
        )
        assertNull(store.load())
    }
}
