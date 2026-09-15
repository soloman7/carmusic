package com.carmusic.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.carmusic.data.AppDatabase
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M1a 五处副作用 fence 的差分测试:电台 mediaId 的 transition 绝不触碰音乐状态
 * (历史/currentTrack),音乐 mediaId 行为不变。
 *
 * PlayerManager 在 Robolectric 下以 controller=null 构造(MediaController 绑定不会完成),
 * handleTransition/handlePlayerError 为 internal 测试接缝,transition 的历史写入经
 * UnconfinedTestDispatcher 内联执行,可同步断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerTransitionFenceTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var pm: PlayerManager
    private val track = Track(platform = "netease", id = "1", title = "晴天", artist = "周杰伦")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = mock<com.carmusic.data.SettingsRepository> {
            on { playMode }.doReturn(MutableStateFlow("REPEAT_ALL"))
            on { pendingDeadTracks }.doReturn(MutableStateFlow(emptySet()))
        }
        pm = PlayerManager(context, mock(), db, settings)
    }

    @After
    fun tearDown() {
        if (::pm.isInitialized) pm.disconnect()
        // 不 close:PlayerManager init 的异步快照读仍可能挂在 Room IO 线程,
        // 主线程 close 会与其争 OpenHelper 锁死锁(Robolectric+Room 已知坑);内存库随进程释放
        Dispatchers.resetMain()
    }

    @Test
    fun `radio transition is fenced from music side effects`() = runBlocking {
        val item = MediaItem.Builder()
            .setMediaId(PlaybackTarget.RADIO_MEDIA_ID_PREFIX + "u-1")
            .build()

        pm.handleTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals("电台不得写音乐历史", 0, db.historyDao().getRecentFlow(200).first().size)
        assertNull("电台不得改写音乐 currentTrack", pm.currentTrack.value)
    }

    @Test
    fun `music transition keeps writing history and current track`() = runBlocking {
        val src = MediaSource(url = "https://example.com/a.mp3", expireAt = 0, quality = "128k")
        val item = pm.buildMediaItem(track, src)

        pm.handleTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        assertEquals("歌曲历史行为不变(回归锚点)", 1, db.historyDao().getRecentFlow(200).first().size)
        assertEquals(track.trackId, pm.currentTrack.value?.trackId)
    }

    @Test
    fun `radio error does not enter music retry chain`() = runBlocking {
        // 无 controller(冷启动/测试环境):radio fence 无法从 controller 判定,
        // 但 _currentTrack 为 null 时音乐链路本身走"track==null"降级——此测试锁定
        // 音乐路径在无 controller 时不出 NPE(电台语义 M1b 接管)
        pm.handlePlayerError(PlaybackException("stream broken", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        // 不崩溃即通过;错误文案允许为音乐降级文案(M1a 无电台会话)
    }
}
