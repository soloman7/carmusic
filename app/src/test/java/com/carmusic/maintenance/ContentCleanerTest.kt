package com.carmusic.maintenance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.carmusic.data.AppDatabase
import com.carmusic.data.FavoriteEntity
import com.carmusic.data.HistoryEntity
import com.carmusic.data.SettingsRepository
import com.carmusic.source.SourceUnavailableException
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 每周清理语义测试(v3.8 收藏红线):
 * - **收藏永不清理**:收藏不进探测列表,平台"确认无源"跨任意周期也不删(用户红线 2026-09-17)
 * - 历史死链:两周期"平台确认无源"才删;单周期失败只挂账
 * - 收藏+历史同 trackId:删历史、留收藏
 * - 网络故障(SourceUnavailableException)= 证据不可信,不动账本不删数据
 * - 歌单黑名单三态:平台确认空 → 拉黑;网络故障 → 不拉黑;恢复 → 自动移出
 * - 连通性哨兵失败 → 整轮放弃,lastCleanupAt 不更新,零数据触碰
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentCleanerTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var probe: FakeProbe
    private lateinit var cleaner: ContentCleaner

    /** 探测网关伪造:probeResults/probeErrors 按 trackId 定向,缺省 defaultProbe */
    private class FakeProbe(
        var pingResult: Boolean = true,
        var defaultProbe: Boolean = true,
        val probeResults: MutableMap<String, Boolean> = mutableMapOf(),
        val probeErrors: MutableMap<String, Exception> = mutableMapOf(),
        val playlistTracksMap: MutableMap<String, List<Track>> = mutableMapOf(),
        val playlistErrors: MutableMap<String, Exception> = mutableMapOf(),
        var recommended: List<Playlist> = emptyList()
    ) : ProbeGateway {
        val probedTracks = mutableListOf<String>()
        val probedPlaylists = mutableListOf<String>()

        override suspend fun ping(): Boolean = pingResult

        override suspend fun probeTrack(track: Track): Boolean {
            probedTracks += track.trackId
            probeErrors[track.trackId]?.let { throw it }
            return probeResults[track.trackId] ?: defaultProbe
        }

        override suspend fun playlistTracks(playlist: Playlist): List<Track> {
            probedPlaylists += playlist.playlistId
            playlistErrors[playlist.playlistId]?.let { throw it }
            return playlistTracksMap[playlist.playlistId] ?: emptyList()
        }

        override suspend fun recommendedPlaylists(): List<Playlist> = recommended
    }

    private class FakeYield : PlaybackYield {
        val waits = mutableListOf<Int>()
        override suspend fun awaitNotPlaying() { waits += 1 }
    }

    private val yield = FakeYield()

    private fun track(id: String, platform: String = "netease") = Track(
        platform = platform, id = id, title = "歌$id", artist = "歌手$id"
    )

    private fun favorite(trackId: String) = FavoriteEntity(
        trackId = trackId, platform = trackId.substringBefore(':'), songId = trackId.substringAfter(':'),
        title = "歌", artist = "人", album = "", coverUrl = null, duration = 100
    )

    private fun history(trackId: String) = HistoryEntity(
        trackId = trackId, platform = trackId.substringBefore(':'), songId = trackId.substringAfter(':'),
        title = "歌", artist = "人", album = "", coverUrl = null, duration = 100
    )

    private fun playlist(platform: String, id: String) = Playlist(platform = platform, id = id, name = "歌单$id")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        settings = SettingsRepository(context)
        probe = FakeProbe()
        cleaner = ContentCleaner(settings, probe, db, yield)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `favorites are never probed and never deleted even when platform confirms dead`() = runBlocking {
        db.favoriteDao().insert(favorite("netease:f1"))
        probe.defaultProbe = false   // 平台"确认无源"

        cleaner.runIfDue(force = true)
        cleaner.runIfDue(force = true)   // 两周期后(旧语义此处已删)

        assertEquals("收藏跨任意周期必须存活", 1, db.favoriteDao().getAll().size)
        assertTrue(
            "收藏根本不该进探测列表(省流量且零误删面)",
            probe.probedTracks.none { it == "netease:f1" }
        )
        assertEquals("收藏不可删 → 挂账必须为空", emptySet<String>(), settings.pendingDeadTracks.first())
    }

    @Test
    fun `history dead track deleted only after two confirmed cycles`() = runBlocking {
        db.historyDao().insert(history("netease:h1"))
        probe.defaultProbe = false

        cleaner.runIfDue(force = true)
        assertEquals("首轮失败只挂账", 1, db.historyDao().getRecentFlow(200).first().size)
        assertEquals(setOf("netease:h1"), settings.pendingDeadTracks.first())

        cleaner.runIfDue(force = true)
        val done = cleaner.state.value
        assertTrue("次轮应完成清理", done is ContentCleaner.CleanState.Done)
        assertEquals("次轮确认死 → 从历史删除", 1, (done as ContentCleaner.CleanState.Done).removedTracks)
        assertEquals(0, db.historyDao().getRecentFlow(200).first().size)
        assertEquals("删除后出账", emptySet<String>(), settings.pendingDeadTracks.first())
    }

    @Test
    fun `track in both favorite and history deletes history only`() = runBlocking {
        db.favoriteDao().insert(favorite("netease:both"))
        db.historyDao().insert(history("netease:both"))
        probe.defaultProbe = false

        cleaner.runIfDue(force = true)
        cleaner.runIfDue(force = true)

        assertEquals("收藏保留", 1, db.favoriteDao().getAll().size)
        assertEquals("历史删除", 0, db.historyDao().getRecentFlow(200).first().size)
    }

    @Test
    fun `history track recovered on second cycle survives and clears pending`() = runBlocking {
        db.historyDao().insert(history("netease:rec"))
        probe.defaultProbe = false
        cleaner.runIfDue(force = true)
        assertEquals(setOf("netease:rec"), settings.pendingDeadTracks.first())

        probe.defaultProbe = true   // 期间恢复可播
        cleaner.runIfDue(force = true)
        assertEquals("恢复可播 → 出账不删", 1, db.historyDao().getRecentFlow(200).first().size)
        assertEquals(emptySet<String>(), settings.pendingDeadTracks.first())
    }

    @Test
    fun `source unavailable leaves ledger and data untouched`() = runBlocking {
        db.historyDao().insert(history("netease:net1"))
        db.historyDao().insert(history("netease:net2"))
        probe.probeErrors["netease:net1"] = SourceUnavailableException("timeout")
        probe.probeResults["netease:net2"] = false

        cleaner.runIfDue(force = true)

        assertEquals("网络故障不挂账", setOf("netease:net2"), settings.pendingDeadTracks.first())
        assertEquals("证据不可信不删数据", 2, db.historyDao().getRecentFlow(200).first().size)
    }

    @Test
    fun `playlist blacklist is three-state and self healing`() = runBlocking {
        probe.recommended = listOf(playlist("netease", "p1"), playlist("qq", "p2"), playlist("kuwo", "p3"))
        probe.playlistTracksMap["netease:p1"] = emptyList()                     // 平台确认空 → 拉黑
        probe.playlistErrors["qq:p2"] = SourceUnavailableException("down")      // 网络故障 → 不拉黑
        probe.playlistTracksMap["kuwo:p3"] = listOf(track("1", "kuwo"))         // 有效

        cleaner.runIfDue(force = true)
        assertEquals("只有平台确认空才进黑名单", setOf("netease:p1"), settings.invalidPlaylists.first())

        // 下一轮恢复 → 自动移出黑名单
        probe.playlistTracksMap["netease:p1"] = listOf(track("9"))
        cleaner.runIfDue(force = true)
        assertEquals("恢复的歌单自动移出", emptySet<String>(), settings.invalidPlaylists.first())
    }

    @Test
    fun `connectivity sentinel failure aborts run without touching anything`() = runBlocking {
        db.historyDao().insert(history("netease:keep"))
        db.favoriteDao().insert(favorite("netease:keepf"))
        probe.pingResult = false
        val lastBefore = settings.lastCleanupAt.first()

        cleaner.runIfDue(force = true)

        assertTrue("哨兵失败应给出错误态", cleaner.state.value is ContentCleaner.CleanState.Error)
        assertEquals("哨兵失败不更新清理时间(下个启动窗口重试)", lastBefore, settings.lastCleanupAt.first())
        assertEquals("零数据触碰", 1, db.historyDao().getRecentFlow(200).first().size)
        assertTrue(probe.probedTracks.isEmpty())
        assertTrue("歌单探测也不该发生", probe.probedPlaylists.isEmpty())
    }

    @Test
    fun `stale pending entries pointing outside history are pruned`() = runBlocking {
        // 旧版本挂账指向收藏(v3.8 前语义遗留):收藏不参与删除,挂账必须被修剪掉
        settings.setPendingDeadTracks(setOf("netease:stale"))
        db.favoriteDao().insert(favorite("netease:stale"))
        db.historyDao().insert(history("netease:fresh"))
        probe.probeResults["netease:fresh"] = false

        cleaner.runIfDue(force = true)

        assertEquals("旧收藏挂账修剪,新历史挂账保留", setOf("netease:fresh"), settings.pendingDeadTracks.first())
    }
}
