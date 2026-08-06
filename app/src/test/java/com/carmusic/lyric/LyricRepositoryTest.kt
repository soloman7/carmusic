package com.carmusic.lyric

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.carmusic.data.AppDatabase
import com.carmusic.data.LyricEntity
import com.carmusic.source.SourceManager
import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LyricRepository 缓存策略测试（Robolectric 内存 Room + mockito-kotlin mock 网络层）。
 * 契约：无歌词的歌写负缓存（lrc=""，24h TTL），命中负缓存不再请求网络；正缓存 30 天 TTL。
 * 验证核心行为：缓存命中时 SourceManager.getLyric 的调用次数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LyricRepository
    private lateinit var sourceManager: SourceManager

    private val track = Track(
        platform = "netease",
        id = "123",
        title = "测试曲",
        artist = "测试歌手"
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()  // 仅测试用，简化协程调度
            .build()
        sourceManager = mock()
        repository = LyricRepository(db.lyricDao(), sourceManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `negative cache - second call for lyric-less track hits no network`() = runTest {
        // 网络层返回 null（该歌无歌词），第一次调用后应写入负缓存
        sourceManager = mock {
            onBlocking { getLyric(any()) } doReturn null
        }
        repository = LyricRepository(db.lyricDao(), sourceManager)

        val first = repository.getLyric(track)
        assertNull(first)

        // 第二次调用：命中负缓存，直接返回 null，不再发网络请求
        val second = repository.getLyric(track)
        assertNull(second)

        verifyBlocking(sourceManager, times(1)) { getLyric(any()) }
    }

    @Test
    fun `positive cache - second call returns cached lyric without network`() = runTest {
        val lyric = LyricResult(lrc = "[00:00.00]第一句歌词", tlyric = "[00:00.00]翻译")
        sourceManager = mock {
            onBlocking { getLyric(any()) } doReturn lyric
        }
        repository = LyricRepository(db.lyricDao(), sourceManager)

        val first = repository.getLyric(track)
        assertEquals(lyric, first)

        // 第二次调用：命中正缓存，内容一致且不再发网络请求
        val second = repository.getLyric(track)
        assertEquals(lyric, second)

        verifyBlocking(sourceManager, times(1)) { getLyric(any()) }
    }

    @Test
    fun `pre-existing positive cache - no network request at all`() = runTest {
        // 直接预置正缓存（30 天 TTL 内），首次调用就不该碰网络
        db.lyricDao().insert(
            LyricEntity(
                trackId = track.trackId,
                lrc = "[00:00.00]预缓存歌词",
                tlyric = null,
                cachedAt = System.currentTimeMillis()
            )
        )
        sourceManager = mock {
            onBlocking { getLyric(any()) } doReturn LyricResult(lrc = "不应被用到")
        }
        repository = LyricRepository(db.lyricDao(), sourceManager)

        val result = repository.getLyric(track)

        assertEquals("[00:00.00]预缓存歌词", result?.lrc)
        assertNull(result?.tlyric)
        verifyBlocking(sourceManager, never()) { getLyric(any()) }
    }
}
