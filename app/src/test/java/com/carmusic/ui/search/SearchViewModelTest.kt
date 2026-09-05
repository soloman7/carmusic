package com.carmusic.ui.search

import com.carmusic.data.SettingsRepository
import com.carmusic.drive.DrivingDetector
import com.carmusic.playback.PlayerManager
import com.carmusic.source.SourceManager
import com.carmusic.source.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 流式搜索取消语义测试。
 * 回归背景：v3.3 及之前 catch(Exception) 把旧搜索被取消时的 CancellationException
 * 吞成"搜索失败，请检查网络后重试"，清空新搜索的结果并冲掉 isSearching。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var sourceManager: SourceManager
    private lateinit var settingsRepo: SettingsRepository

    private val track = Track(platform = "netease", id = "1", title = "晴天", artist = "周杰伦")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sourceManager = mock()
        settingsRepo = mock {
            on { enabledPlatforms }.doReturn(MutableStateFlow(SettingsRepository.ALL_PLATFORMS))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(): SearchViewModel {
        val player = mock<PlayerManager>()
        val driving = mock<DrivingDetector> {
            on { isDriving }.doReturn(MutableStateFlow(false))
        }
        return SearchViewModel(sourceManager, settingsRepo, player, driving)
    }

    private fun stubStream(flow: Flow<List<Track>>) {
        whenever(sourceManager.searchAllStream(any(), any())).doReturn(flow)
    }

    @Test
    fun `rapid re-search does not fake an error from cancelled old job`() = runTest(dispatcher) {
        // 第一次搜索：发一帧后永不结束（模拟慢源）
        stubStream(flow {
            emit(listOf(track))
            awaitCancellation()
        })
        val viewModel = newVm()

        viewModel.setKeyword("A")
        viewModel.search()
        advanceUntilIdle()
        assertEquals(listOf(track), viewModel.results.value)

        // 第二次搜索取消旧 job：旧 job 的取消不得清空结果/伪造错误/冲掉 isSearching
        val newTrack = track.copy(id = "2", title = "晴天2")
        stubStream(flow {
            emit(listOf(newTrack))
            awaitCancellation()
        })
        viewModel.setKeyword("B")
        viewModel.search()
        advanceUntilIdle()

        assertNull("取消旧搜索不能伪造'搜索失败'", viewModel.error.value)
        assertTrue("新搜索仍在进行，isSearching 不能被旧 job 冲掉", viewModel.isSearching.value)
        assertEquals(listOf(newTrack), viewModel.results.value)
    }

    @Test
    fun `genuine network failure surfaces error`() = runTest(dispatcher) {
        stubStream(flow<List<Track>> { throw java.io.IOException("offline") })
        val viewModel = newVm()

        viewModel.setKeyword("X")
        viewModel.search()
        advanceUntilIdle()

        assertEquals("搜索失败，请检查网络后重试", viewModel.error.value)
    }
}
