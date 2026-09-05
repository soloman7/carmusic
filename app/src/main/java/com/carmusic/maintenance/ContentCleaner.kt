package com.carmusic.maintenance

import android.util.Log
import com.carmusic.data.AppDatabase
import com.carmusic.data.SettingsRepository
import com.carmusic.playback.PlayerManager
import com.carmusic.source.SourceManager
import com.carmusic.source.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 每周清理无效内容（v3.2 新增，v3.3 加固）。
 *
 * 项目无 WorkManager/常驻后台，"每周"= App 启动时检查距上次清理是否超 7 天
 * （AppContainer 启动后延迟触发，也可在设置页手动"立即清理"）。清理内容：
 * 1. 收藏/历史里的死链歌曲：逐首用 getMediaSourceNoFallback 轻量探测（不触发跨平台 fallback），
 *    连续两个清理周期（约 14 天）都拿不到播放地址才删除——首轮失败只进 DataStore 挂账，
 *    中间恢复播放自动出账。车库弱网/平台抖动的"探测失败"不再等于死链。
 * 2. 无效歌单：推荐歌单逐个拉曲目，拉取失败或可播曲目为 0 的进 DataStore 黑名单
 *    （invalid_playlists，合并写入；黑名单成员每轮仍被重新探测，恢复即自动移出）。
 * 3. 过期歌词：LyricDao.deleteOlderThan(30 天)。
 *
 * 安全阀：
 * - 开跑前先打连通性哨兵（SourceManager.ping），网络不可用整轮放弃（本轮不更新清理时间，
 *   下次启动重试）；绝不基于"全网探测失败"销毁用户数据。
 * - 每次探测前检查播放状态，正在播放则挂起让路，维护流量不与播放抢带宽。
 * - 全程 runCatching 兜底，任何异常不崩溃、只记日志。
 */
class ContentCleaner(
    private val settings: SettingsRepository,
    private val sourceManager: SourceManager,
    private val database: AppDatabase,
    private val playerManager: PlayerManager
) {

    sealed class CleanState {
        data object Idle : CleanState()
        data object Running : CleanState()
        /** 清理完成：removedTracks=删除的死链数，invalidPlaylists=无效歌单数 */
        data class Done(val removedTracks: Int, val invalidPlaylists: Int) : CleanState()
        data class Error(val message: String) : CleanState()
    }

    private val _state = MutableStateFlow<CleanState>(CleanState.Idle)
    val state: StateFlow<CleanState> = _state.asStateFlow()

    /** 重入互斥：启动自动清理与设置页手动触发并发时，挂账集合互相覆盖会破坏两周期确认 */
    private val runMutex = Mutex()

    /** 距上次清理满 7 天（或从未清理）且开关打开时执行；force=true 无条件执行 */
    suspend fun runIfDue(force: Boolean = false) {
        // 原子占位：拿到锁的才清理，其余直接返回（check-then-act 不能拆开）
        if (!runMutex.tryLock()) return
        try {
            if (_state.value is CleanState.Running) return
            if (!force) {
                if (!settings.autoClean.first()) return
                val last = settings.lastCleanupAt.first()
                if (System.currentTimeMillis() - last < WEEK_MS) return
            }
            _state.value = CleanState.Running
            runCatching {
                // 哨兵不过 = 网络死，全部探测结果不可信。静默轮空（force 手动触发时给出反馈），
                // 不更新 lastCleanupAt，让下个启动窗口重试。
                if (!sourceManager.ping()) {
                    Log.w(TAG, "cleanup skipped: connectivity sentinel failed")
                    _state.value = if (force) {
                        CleanState.Error("网络不可用，本次未清理")
                    } else {
                        CleanState.Idle
                    }
                    return
                }
                val removed = cleanDeadTracks()
                val invalid = cleanInvalidPlaylists()
                database.lyricDao().deleteOlderThan(System.currentTimeMillis() - LYRIC_TTL_MS)
                settings.setLastCleanupAt(System.currentTimeMillis())
                _state.value = CleanState.Done(removed, invalid)
                Log.i(TAG, "cleanup done: removedTracks=$removed invalidPlaylists=$invalid")
            }.onFailure { e ->
                Log.w(TAG, "cleanup failed: ${e.message}")
                _state.value = CleanState.Error(e.message ?: "清理失败")
            }
        } finally {
            runMutex.unlock()
        }
    }

    /**
     * 探测收藏+历史（按 trackId 去重），连续两轮失败才从两张表删掉；返回删除数。
     * 上轮挂账（pendingDeadTracks）里本轮恢复可播的自动出账。
     * v3.4：探测超时按"本轮不可信"处理——既不挂账也不出账/删除，
     * 连续两个弱网周期不会把好歌推进删除流程。
     */
    private suspend fun cleanDeadTracks(): Int {
        val favoriteDao = database.favoriteDao()
        val historyDao = database.historyDao()
        // 历史表只保留最近 200 条（insertAndTrim），取首帧全量即可
        val history = historyDao.getRecentFlow(200).first().map { it.toTrack() }
        val all = (favoriteDao.getAll().map { it.toTrack() } + history)
            .distinctBy { it.trackId }
            .take(MAX_PROBE_PER_RUN)

        val previouslyPending = settings.pendingDeadTracks.first()
        val ledger = DeadTrackLedger(previouslyPending)
        val semaphore = Semaphore(3)
        var removed = 0
        coroutineScope {
            all.map { track ->
                async {
                    // 三态：true=确认可播 / false=平台确认不可播 / null=超时（本轮证据不可信）
                    val probed: Boolean? = semaphore.withPermit {
                        playerManager.awaitNotPlaying()   // 播放中让路，不抢带宽
                        withTimeoutOrNull(TRACK_PROBE_TIMEOUT_MS) {
                            runCatching { sourceManager.getMediaSourceNoFallback(track) }
                                .getOrNull()?.let { !it.isExpired() }
                        }
                    }
                    when {
                        probed == null ->
                            Log.d(TAG, "track probe timed out, no ledger change: ${track.trackId}")
                        else -> when (ledger.onProbed(track.trackId, probed)) {
                            DeadTrackLedger.Decision.DELETE -> {
                                favoriteDao.deleteById(track.trackId)
                                historyDao.deleteById(track.trackId)
                                synchronized(this@ContentCleaner) { removed++ }
                                Log.d(TAG, "removed dead track: ${track.trackId} ${track.title}")
                            }
                            DeadTrackLedger.Decision.PENDING ->
                                Log.d(TAG, "track probe failed, pending next cycle: ${track.trackId}")
                            DeadTrackLedger.Decision.KEEP -> Unit
                        }
                    }
                }
            }.forEach { it.await() }
        }
        settings.setPendingDeadTracks(ledger.pendingAfterRun())
        return removed
    }

    /**
     * 逐个验证推荐歌单，失败/空的歌单写黑名单；返回本轮确认无效数。
     * v3.4 两处语义修正：
     * 1. 黑名单成员也重新探测（includeInvalid=true）——恢复的歌单自动移出黑名单，
     *    不再是"隔周放出来挨一刀"的振荡；
     * 2. 本轮未探测到的旧黑名单条目保留（合并写而非覆盖写），不因一次探测缺席就放出来。
     */
    private suspend fun cleanInvalidPlaylists(): Int {
        val playlists = sourceManager.getRecommendedPlaylists(includeInvalid = true)
        val semaphore = Semaphore(2)
        val invalid = coroutineScope {
            playlists.map { playlist ->
                async {
                    // 三态：true=有可播曲目 / false=确认无效 / null=超时（不计入黑名单）
                    val ok: Boolean? = semaphore.withPermit {
                        playerManager.awaitNotPlaying()
                        withTimeoutOrNull(PLAYLIST_PROBE_TIMEOUT_MS) {
                            runCatching { sourceManager.getPlaylistTracks(playlist).isNotEmpty() }
                                .getOrNull()
                        }
                    }
                    if (ok == false) playlist.playlistId else null
                }
            }.mapNotNull { it.await() }.toSet()
        }
        // 合并写：旧黑名单 ∪ 本轮确认无效，减去本轮验证通过的
        val previous = settings.invalidPlaylists.first()
        val probedThisRun = playlists.map { it.playlistId }.toSet()
        val merged = (invalid + previous.filter { it !in probedThisRun }).toSet()
        settings.setInvalidPlaylists(merged)
        if (invalid.isNotEmpty()) Log.i(TAG, "invalid playlists: $invalid")
        return invalid.size
    }

    private fun com.carmusic.data.FavoriteEntity.toTrack() = Track(
        platform = platform, id = songId, title = title, artist = artist,
        album = album, coverUrl = coverUrl, duration = duration, extra = extra
    )

    private fun com.carmusic.data.HistoryEntity.toTrack() = Track(
        platform = platform, id = songId, title = title, artist = artist,
        album = album, coverUrl = coverUrl, duration = duration, extra = extra
    )

    companion object {
        private const val TAG = "ContentCleaner"
        private const val WEEK_MS = 7L * 24 * 3600 * 1000
        private const val LYRIC_TTL_MS = 30L * 24 * 3600 * 1000
        private const val MAX_PROBE_PER_RUN = 200
        private const val TRACK_PROBE_TIMEOUT_MS = 8_000L
        private const val PLAYLIST_PROBE_TIMEOUT_MS = 15_000L
    }
}
