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
 *    连续两个清理周期（约 14 天）"平台确认无源"才删除——首轮失败只进 DataStore 挂账；
 *    出账靠下轮探测成功或期间播放成功（实时）。网络故障/超时/播放让路 = 证据不可信，不动账本。
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
     * v3.4.3 证据语义（修正 v3.4 把 DELETE 证据绑在"返回已过期 URL"上的不可达回归）：
     * - true  = 拿到可用播放地址（出账）
     * - false = 平台**确认无源**（provider 返回 null；挂账/删除的唯一删除性证据）
     * - null  = 网络故障（SourceUnavailableException）或探测超时或播放让路（证据不可信，不动账本）
     * 残余风险如实说明：provider 内部把 HTTP 错误也吞成 null（ProviderHttp），故平台级宕机
     * 会被误读为"确认无源"——由 ping 哨兵 + 两周期确认 + 播放成功实时出账三道闸兜底。
     */
    private suspend fun cleanDeadTracks(): Int {
        val favoriteDao = database.favoriteDao()
        val historyDao = database.historyDao()
        // 历史表只保留最近 200 条（insertAndTrim），取首帧全量即可
        val history = historyDao.getRecentFlow(200).first().map { it.toTrack() }
        val favorites = favoriteDao.getAll()
        val allIds = (favorites.map { it.trackId } + history.map { it.trackId }).toSet()
        val probeList = (favorites.map { it.toTrack() } + history)
            .distinctBy { it.trackId }
            .take(MAX_PROBE_PER_RUN)

        val previouslyPending = settings.pendingDeadTracks.first()
        val ledger = DeadTrackLedger(previouslyPending)
        val semaphore = Semaphore(3)
        var removed = 0
        coroutineScope {
            probeList.map { track ->
                async {
                    val probed: Boolean? = semaphore.withPermit {
                        // 让路也要有界：车机上音乐常播，无界等待会饿死整轮清理并锁死手动通道
                        val free = withTimeoutOrNull(PLAY_AWAIT_TIMEOUT_MS) { playerManager.awaitNotPlaying() }
                        if (free == null) {
                            null
                        } else {
                            withTimeoutOrNull(TRACK_PROBE_TIMEOUT_MS) {
                                try {
                                    sourceManager.getMediaSourceNoFallback(track)
                                        ?.let { !it.isExpired() } ?: false
                                } catch (e: com.carmusic.source.SourceUnavailableException) {
                                    null
                                }
                            }
                        }
                    }
                    when {
                        probed == null ->
                            Log.d(TAG, "probe inconclusive, no ledger change: ${track.trackId}")
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
        // 修剪挂账：只保留仍存在于收藏/历史的曲目——用户删掉挂账歌再重新收藏同 trackId 时，
        // 旧挂账会把两周期确认退化成单周期判死
        settings.setPendingDeadTracks(ledger.pendingAfterRun() intersect allIds)
        return removed
    }

    /**
     * 逐个验证推荐歌单，失败/空的歌单写黑名单；返回本轮确认无效数。
     * v3.4 两处语义修正：
     * 1. 黑名单成员也重新探测（includeInvalid=true）——恢复的歌单自动移出黑名单，
     *    不再是"隔周放出来挨一刀"的振荡；
     * 2. 黑名单移出条件 = 本轮**验证通过**，而非"出现在探测列表里"——
     *    v3.4 的 merge 把"探了但超时"的歌单误当"验证通过"放出来，重新引入振荡。
     */
    private suspend fun cleanInvalidPlaylists(): Int {
        val playlists = sourceManager.getRecommendedPlaylists(includeInvalid = true)
        val semaphore = Semaphore(2)
        val verdicts = coroutineScope {
            playlists.map { playlist ->
                async {
                    // 三态：true=有可播曲目 / false=确认无效 / null=超时（证据不可信）
                    val ok: Boolean? = semaphore.withPermit {
                        val free = withTimeoutOrNull(PLAY_AWAIT_TIMEOUT_MS) { playerManager.awaitNotPlaying() }
                        if (free == null) {
                            null
                        } else {
                            withTimeoutOrNull(PLAYLIST_PROBE_TIMEOUT_MS) {
                                try {
                                    sourceManager.getPlaylistTracks(playlist).let { it.isNotEmpty() }
                                } catch (e: com.carmusic.source.SourceUnavailableException) {
                                    null
                                }
                            }
                        }
                    }
                    playlist.playlistId to ok
                }
            }.map { it.await() }
        }
        val confirmedInvalid = verdicts.filter { it.second == false }.map { it.first }.toSet()
        val verifiedOk = verdicts.filter { it.second == true }.map { it.first }.toSet()
        // 合并写：旧黑名单 ∪ 本轮确认无效 − 本轮验证通过；超时(null)条目保留在黑名单
        val previous = settings.invalidPlaylists.first()
        val merged = (confirmedInvalid + previous.filter { it !in verifiedOk }).toSet()
        settings.setInvalidPlaylists(merged)
        if (confirmedInvalid.isNotEmpty()) Log.i(TAG, "invalid playlists: $confirmedInvalid")
        return confirmedInvalid.size
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

        /** 播放避让上限：车机上音乐常播，无界等待会让整轮清理饿死并锁死手动通道 */
        private const val PLAY_AWAIT_TIMEOUT_MS = 60_000L
    }
}
