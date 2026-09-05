package com.carmusic.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.carmusic.data.HistoryEntity
import com.carmusic.data.PlaybackStateEntity
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Track
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 播放管理器：包装 MediaController，暴露 StateFlow 给 Compose
 */
class PlayerManager(
    context: Context,
    private val sourceManager: com.carmusic.source.SourceManager,
    private val database: com.carmusic.data.AppDatabase,
    private val settingsRepository: com.carmusic.data.SettingsRepository
) {

    private val appContext: Context = context.applicationContext
    // 逃逸异常打日志并提示，不崩进程（咪咕闪退防护之一）
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "coroutine error escaped", e)
            _error.value = "内部错误：${e.javaClass.simpleName}"
        }
    )
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /** 取流并发上限：整单解析时最多 8 路并发 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val resolveLimiter = Dispatchers.IO.limitedParallelism(8)

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var retryCount = 0
    private val maxRetry = 2   // 车机场景 6 秒无声已难接受，2 次重试后自动跳歌
    private var retryJob: Job? = null
    private var consecutiveFailTrack: String? = null

    // 播放模式 + 队列导航（索引数学全在 QueueNavigator，可单测）
    private val navigator = QueueNavigator()
    private val _playMode = MutableStateFlow(PlayMode.REPEAT_ALL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    /** 音频会话 ID（EqManager attach audiofx 用；由 PlaybackService 推送到 AudioSessionHub，0 = 未分配） */
    val audioSessionId: StateFlow<Int> = AudioSessionHub.audioSessionId

    // 自动跳歌保护：连续跳过整轮队列仍未成功 → 放弃，避免无限跳歌
    private var consecutiveSkips = 0

    // SHUFFLE 自然播完重定向用：记录上一个 timeline 索引
    private var lastTimelineIndex = 0

    // 渐进式 playAll 的后台协程句柄：新播放请求必须取消它，否则旧 phase2 会往
    // 已重置的队列 addMediaItem(pos,…)，pos 越界抛在 ExoPlayer 主线程上（咪咕闪退根因）
    private var playAllJob: Job? = null

    // MediaController 连接完成前点击的播放请求，连接后补播
    private var pendingPlay: Track? = null

    // ---- 播放会话持久化（DiLink 杀进程后恢复"停车前听到哪"）----
    private data class RestoredPlayback(val queue: List<Track>, val index: Int, val positionMs: Long)

    /** 已从 DB 读出但尚未消费的快照：UI 先显示上次播放内容，用户点播放/切歌才真正重建队列 */
    private var restoredSnapshot: RestoredPlayback? = null

    /** 恢复播放待消费的进度（ms）与目标 trackId：恢复队列首次 READY 时 seek 过去，-1 表示无 */
    private var pendingRestorePositionMs = -1L
    private var pendingRestoreTrackId: String? = null

    // 位置更新节拍计数：播放中每 ~10s 落盘一次进度
    private var persistTick = 0

    // Track 登记表：MediaItem 只带 mediaId，不跨 binder 传 Serializable（BadParcelable 风险敞口归零）
    private val trackRegistry = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Track>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > 500
        }
    )

    // 下一首 URL 预加载（不触发跨平台 fallback，避免放大请求量）
    // IO 线程（preload）写 + Main 线程读删，必须同步包装（同 trackRegistry）
    private val preloadedSources = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, MediaSource>(5, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaSource>?) = size > 5
        }
    )
    private var preloadJob: Job? = null

    init {
        connect()
        startPositionUpdater()
        // 恢复持久化的播放模式
        scope.launch {
            val saved = runCatching { PlayMode.valueOf(settingsRepository.playMode.first()) }
                .getOrDefault(PlayMode.REPEAT_ALL)
            applyPlayMode(saved, persist = false)
        }
        // 读取上次播放会话快照（只读不播：等用户点播放/切歌再重建队列）
        scope.launch {
            val saved = runCatching { database.playbackStateDao().get() }.getOrNull() ?: return@launch
            val queue = PlaybackSessionCodec.decode(saved.queueJson) ?: return@launch
            if (queue.isEmpty()) return@launch
            val snapshot = RestoredPlayback(
                queue,
                saved.currentIndex.coerceIn(0, queue.size - 1),
                saved.positionMs.coerceAtLeast(0L)
            )
            restoredSnapshot = snapshot
            // UI 先呈现上次内容（timeline 仍为空，点播放才真正恢复）
            _queue.value = queue
            _currentTrack.value = queue.getOrNull(snapshot.index)
        }
    }

    private fun connect() {
        // 幂等：已连接或正在连接时跳过（Activity 重建/重进时经 ensureConnected() 反复调用）
        if (controller != null || controllerFuture != null) return
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture?.addListener({
            // future 可能以异常完成（服务绑定失败/车机省电限制）：
            // 清掉 future 允许下次重连，绝不能让异常逃出 directExecutor 崩进程
            val c = controllerFuture?.let { runCatching { it.get() }.getOrNull() }
            if (c == null) {
                Log.e(TAG, "MediaController connect failed, will retry on next ensureConnected()")
                controllerFuture = null
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            c.repeatMode = exoRepeatMode(_playMode.value)
            // 补播连接完成前点击的歌曲
            pendingPlay?.let { pendingPlay = null; play(it) }
        }, MoreExecutors.directExecutor())
    }

    /** controller 是否已连接（PlaybackService 方向盘按键冷启动判备用） */
    val isControllerReady: Boolean get() = controller != null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) consecutiveSkips = 0   // 有歌成功出声，重置跳歌保护
            else persistNow()   // 暂停点即存档点（含熄火、导航打断）
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller
            // SHUFFLE 自然播完时 ExoPlayer 会顺序切到下一首，重定向到随机目标
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                _playMode.value == PlayMode.SHUFFLE && c != null && c.mediaItemCount > 1
            ) {
                val target = navigator.nextIndex(lastTimelineIndex, c.mediaItemCount, PlayMode.SHUFFLE) {
                    Random.nextInt(it)
                }
                val newIndex = c.currentMediaItemIndex
                if (target != null && target != newIndex) {
                    lastTimelineIndex = target
                    c.seekTo(target, 0L)
                    return   // seek 触发的 SEEK transition 会走下面的正常路径
                }
            }
            if (c != null) lastTimelineIndex = c.currentMediaItemIndex
            // Track 不再跨 binder 传 Serializable，用 mediaId 查登记表
            val track = mediaItem?.mediaId?.let { trackRegistry[it] }
            _currentTrack.value = track
            _duration.value = controller?.duration ?: 0L

            // 写历史（自动裁剪至 200 条）
            track?.let { t ->
                scope.launch {
                    database.historyDao().insertAndTrim(
                        HistoryEntity(
                            trackId = t.trackId,
                            platform = t.platform,
                            songId = t.id,
                            title = t.title,
                            artist = t.artist,
                            album = t.album,
                            coverUrl = t.coverUrl,
                            duration = t.duration,
                            extra = t.extra
                        )
                    )
                }
            }

            // 触发下一首 URL 预加载
            schedulePreloadNext()
            persistNow()   // 切歌即存档（索引、队列）
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _duration.value = controller?.duration ?: 0L
                // 恢复播放：首轮 READY seek 到上次进度（仅当起始曲目就是存档曲目）
                if (pendingRestorePositionMs >= 0) {
                    val c = controller
                    if (c != null && c.currentMediaItem?.mediaId == pendingRestoreTrackId) {
                        c.seekTo(pendingRestorePositionMs)
                    }
                    pendingRestorePositionMs = -1L
                    pendingRestoreTrackId = null
                }
            }
            // ENDED 只在 repeatMode=OFF 的 SEQUENCE/SHUFFLE 下到达（REPEAT_ALL/ONE 由 ExoPlayer 原生处理）
            if (playbackState == Player.STATE_ENDED && _playMode.value == PlayMode.SHUFFLE) {
                next()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback error: ${error.message}", error)
            val track = _currentTrack.value
            // 切歌则重置计数
            if (track?.trackId != consecutiveFailTrack) {
                retryCount = 0
                consecutiveFailTrack = track?.trackId
            }
            if (track == null || retryCount >= maxRetry) {
                retryJob?.cancel()
                val count = controller?.mediaItemCount ?: 0
                if (count > 1 && consecutiveSkips < count) {
                    // 重试耗尽：自动跳下一首而不是卡死在错误条
                    consecutiveSkips++
                    _error.value = "「${track?.title ?: "当前歌曲"}」播放失败，自动跳到下一首"
                    retryCount = 0
                    consecutiveFailTrack = null
                    next()
                } else {
                    _error.value = if (count > 1) {
                        "列表歌曲均播放失败，请检查网络后重试"
                    } else {
                        "播放失败（已重试 $maxRetry 次）：${error.message}"
                    }
                }
                return
            }
            retryCount++
            _error.value = "加载失败，第 $retryCount/$maxRetry 次重试…"
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(1000L * retryCount)  // 线性退避 1s / 2s
                // 用户已切歌（next/previous）则本次重签作废：
                // 旧曲目的 refreshAndRetry 会 cancel 当前会话的 playAllJob，炸掉 phase2 补缺
                if (_currentTrack.value?.trackId != track.trackId) return@launch
                refreshAndRetry(track)
            }
        }
    }

    /** 播放一首歌曲 */
    fun play(track: Track, addToQueue: Boolean = false) {
        // 用户主动播放视为新会话，重置重试状态与待恢复快照
        retryCount = 0
        consecutiveFailTrack = null
        retryJob?.cancel()
        restoredSnapshot = null
        if (!addToQueue) {
            // 单曲播放替换队列，必须先杀掉进行中的整单解析，否则旧 phase2 会越界插队
            playAllJob?.cancel()
            playAllJob = null
        }
        scope.launch {
            _error.value = null
            if (controller == null) {
                // 冷启动慢设备上 controller 尚未连接，连接完成后补播
                pendingPlay = track
                return@launch
            }
            // 优先用预加载的 URL
            val mediaSource = preloadedSources.remove(track.trackId)
                ?.takeIf { !it.isExpired() }
            if (mediaSource == null) {
                val resolved = try {
                    sourceManager.getMediaSource(track)
                } catch (e: com.carmusic.source.SourceUnavailableException) {
                    // 原平台网络故障：提示重试，而不是误报"全平台无此歌曲"
                    _error.value = "网络异常，请稍后重试"
                    return@launch
                }
                if (resolved == null) {
                    _error.value = "无法播放：所有平台均无此歌曲的播放源"
                    return@launch
                }
                setAndPlay(track, resolved, addToQueue)
                return@launch
            }
            setAndPlay(track, mediaSource, addToQueue)
        }
    }

    /** 已拿到可用音源：构建 MediaItem 落入播放器。timeline 为空且是"加入队列"时降级为单曲播放，
     *  避免"恢复快照未消费"状态下 1 首入空 timeline 而 _queue 记 N+1 的错位（会污染存档）。 */
    private fun setAndPlay(track: Track, mediaSource: MediaSource, addToQueue: Boolean) {
        val mediaItem = buildMediaItem(track, mediaSource)
        val player = controller ?: return
        if (addToQueue && player.mediaItemCount > 0) {
            runCatching { player.addMediaItem(mediaItem) }
            _queue.value = _queue.value + track
        } else {
            runCatching {
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }.onFailure { _error.value = "播放启动失败：${it.message}" }
            _queue.value = listOf(track)
        }
    }

    /** 播放列表（替换当前队列）。渐进式：phase1 原平台快路径出结果立即开播，
     *  phase2 跨平台 fallback 后台继续，解析出一首按原始位置插入一首——
     *  避免大批 VIP/独家歌触发 fallback 时几十秒静默，感知"播放不了"。 */
    fun playAll(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        retryCount = 0
        consecutiveFailTrack = null
        retryJob?.cancel()
        restoredSnapshot = null
        preloadedSources.clear()
        // 关键：取消上一个还在跑的整单解析。旧 phase2 若继续往新队列插队，
        // pos 按旧队列算出、队列却已被 setMediaItems 重置 → addMediaItem 越界
        // 抛在 ExoPlayer 主线程 → 进程死（咪咕闪退根因，app 侧防护全接不到）
        playAllJob?.cancel()
        playAllJob = scope.launch {
            _error.value = null
            val player = controller ?: return@launch

            fun buildItem(t: Track, source: MediaSource): MediaItem = buildMediaItem(t, source)

            // phase1：原平台快路径（8 路并发）
            val phase1 = coroutineScope {
                tracks.mapIndexed { idx, t ->
                    async(resolveLimiter) {
                        idx to (t to runCatching { sourceManager.getMediaSourceNoFallback(t) }.getOrNull())
                    }
                }.awaitAll()
            }

            // 已入队的原始索引（有序，插位置计算用；全部操作在主线程，无线程安全问题）
            val resolvedIdxs = sortedSetOf<Int>()
            val trackByIdx = mutableMapOf<Int, Track>()
            phase1.forEach { (idx, pair) ->
                if (pair.second != null) {
                    resolvedIdxs.add(idx)
                    trackByIdx[idx] = pair.first
                }
            }

            // phase1 全灭时 phase2 第一首解析成功才开播（需要 prepare+play）
            var started = false
            if (resolvedIdxs.isNotEmpty()) {
                // 立即开播：原 startIndex 若被跳过，取其后第一个可用项
                val firstResolved = resolvedIdxs.toList()
                val startPos = firstResolved.indexOfFirst { it >= startIndex }
                    .let { if (it == -1) 0 else it }
                _queue.value = firstResolved.map { trackByIdx.getValue(it) }
                runCatching {
                    player.setMediaItems(
                        firstResolved.map { idx -> buildItem(trackByIdx.getValue(idx), phase1[idx].second.second!!) },
                        startPos, 0L
                    )
                    player.prepare()
                    player.play()
                    started = true
                }.onFailure { Log.e(TAG, "phase1 setMediaItems failed", it) }
            }

            // phase2：fallback 补缺，边解析边按原位插入
            val missingIdxs = phase1.filter { it.second.second == null }.map { it.first }
            if (missingIdxs.isNotEmpty() && isActive) {
                _error.value = if (started) null else "正在跨平台匹配音源…"
                coroutineScope {
                    missingIdxs.map { idx ->
                        async(resolveLimiter) {
                            val t = tracks[idx]
                            val src = runCatching { sourceManager.getMediaSource(t) }.getOrNull()
                            if (src != null) {
                                // 回到主线程插队：insertPos = 已入队且原 idx 更小的数量
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    // 被取消后（用户点了别的歌）绝不再碰播放器
                                    if (!isActive) return@withContext
                                    runCatching {
                                        // clamp：队列可能已被截短，越界 addMediaItem 会抛在 ExoPlayer 主线程
                                        val pos = resolvedIdxs.count { it < idx }
                                            .coerceAtMost(player.mediaItemCount)
                                        resolvedIdxs.add(idx)
                                        trackByIdx[idx] = t
                                        player.addMediaItem(pos, buildItem(t, src))
                                        _queue.value = _queue.value.toMutableList().apply {
                                            add(pos.coerceAtMost(size), t)
                                        }
                                        if (!started) {
                                            started = true
                                            // 对齐 startIndex 后第一个可用项
                                            val firstResolved = resolvedIdxs.toList()
                                            val startPos = firstResolved.indexOfFirst { it >= startIndex }
                                                .let { if (it == -1) 0 else it }
                                            player.seekTo(startPos, 0L)
                                            player.prepare()
                                            player.play()
                                        }
                                    }.onFailure { Log.e(TAG, "phase2 insert failed idx=$idx", it) }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            if (isActive && resolvedIdxs.isEmpty()) {
                _error.value = "无法播放：列表歌曲均无播放源"
            }
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        // 上次会话恢复：timeline 空 + 有未消费快照 → 点播放 = 续播上次的队列和进度
        if (c.mediaItemCount == 0) {
            restoredSnapshot?.let { resumeRestored(it); return }
        }
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun resumeRestored(snap: RestoredPlayback) {
        restoredSnapshot = null
        pendingRestorePositionMs = snap.positionMs
        pendingRestoreTrackId = snap.queue.getOrNull(snap.index)?.trackId
        playAll(snap.queue, snap.index)
    }

    // ---- 播放模式 ----

    fun cyclePlayMode() {
        val entries = PlayMode.entries
        setPlayMode(entries[(_playMode.value.ordinal + 1) % entries.size])
    }

    fun setPlayMode(mode: PlayMode) = applyPlayMode(mode, persist = true)

    private fun applyPlayMode(mode: PlayMode, persist: Boolean) {
        _playMode.value = mode
        navigator.reset()
        controller?.repeatMode = exoRepeatMode(mode)
        if (persist) scope.launch { settingsRepository.setPlayMode(mode.name) }
    }

    /**
     * REPEAT_ALL/ONE 的自然播完交给 ExoPlayer 原生 repeatMode（无缝隙）；
     * SEQUENCE/SHUFFLE 保持 OFF，手动切歌永远走自己的 navigator。
     */
    private fun exoRepeatMode(mode: PlayMode): Int = when (mode) {
        PlayMode.SEQUENCE, PlayMode.SHUFFLE -> Player.REPEAT_MODE_OFF
        PlayMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
    }

    // ---- 切歌（自定义索引逻辑，UI 按钮与方向盘按键共用）----

    fun next() = nextOn(null)

    fun previous() = previousOn(null)

    /** @param playerOverride controller 未连接时由 PlaybackService 传入 ExoPlayer 直连 */
    fun nextOn(playerOverride: Player?) {
        val c = playerOverride ?: controller ?: return
        // 切歌即放弃待执行的重签，防止旧曲目 retry 炸掉当前会话的 phase2
        retryJob?.cancel()
        // 杀进程后方向盘按键直接恢复上次会话（车机常见：上车先按下一首）
        if (c.mediaItemCount == 0) {
            restoredSnapshot?.let { resumeRestored(it); return }
        }
        val target = navigator.nextIndex(
            c.currentMediaItemIndex, c.mediaItemCount, _playMode.value
        ) { Random.nextInt(it) }
        seekToIndexOn(c, target)
    }

    fun previousOn(playerOverride: Player?) {
        val c = playerOverride ?: controller ?: return
        retryJob?.cancel()
        if (c.mediaItemCount == 0) {
            restoredSnapshot?.let { resumeRestored(it); return }
        }
        // 车机惯例：当前曲已播 >3s，"上一首"先回本曲开头；再按才真上一首
        if (c.playbackState != Player.STATE_ENDED && c.currentPosition > 3000 && c.mediaItemCount > 1) {
            c.seekTo(0)
            return
        }
        val target = navigator.prevIndex(c.currentMediaItemIndex, c.mediaItemCount, _playMode.value)
        seekToIndexOn(c, target)
    }

    private fun seekToIndexOn(c: Player, index: Int?) {
        if (index == null) return
        // phase2 插队期间 timeline 在漂移，防御性 clamp
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        // ENDED/IDLE 下 seek 后必须显式恢复，否则停在暂停态
        if (c.playbackState == Player.STATE_ENDED || c.playbackState == Player.STATE_IDLE) {
            c.prepare()
        }
        c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun clearError() {
        _error.value = null
    }

    /** 维护任务避让：播放中挂起等待，空闲则立即返回。清理等后台网络任务在每次探测前调用。 */
    suspend fun awaitNotPlaying() {
        isPlaying.first { !it }
    }

    private fun refreshAndRetry(track: Track) {
        // URL 过期重新拉；先杀掉进行中的整单解析，避免与 phase2 插队互踩
        playAllJob?.cancel()
        playAllJob = null
        scope.launch {
            val newSource = runCatching { sourceManager.getMediaSource(track) }.getOrNull()
            if (newSource == null) {
                _error.value = "「${track.title}」重新获取播放地址失败"
                return@launch
            }
            val player = controller ?: run { play(track); return@launch }
            // 队列里仍有该曲目时原位替换：队列、位置、后续播放顺序全部保留。
            // （旧实现调 play(track) → setMediaItem 会把整个队列炸成单曲，
            //   停车 20 分钟回来恢复播放即触发，队列丢失。）
            val index = (0 until player.mediaItemCount)
                .firstOrNull { player.getMediaItemAt(it).mediaId == track.trackId }
            if (index == null) {
                play(track)
                return@launch
            }
            runCatching {
                player.replaceMediaItem(index, buildMediaItem(track, newSource))
                if (player.playbackState == Player.STATE_IDLE ||
                    player.playbackState == Player.STATE_ENDED
                ) {
                    player.prepare()
                }
                player.play()
            }.onFailure {
                Log.e(TAG, "replaceMediaItem failed", it)
                play(track)   // 原位替换失败时兜底，宁可丢队列也不能停摆
            }
        }
    }

    /** Track + 已解析音源 → MediaItem（登记进 trackRegistry，供 transition 回调查 Track） */
    private fun buildMediaItem(t: Track, source: MediaSource): MediaItem {
        trackRegistry[t.trackId] = t
        return MediaItem.Builder()
            .setUri(source.url)
            .setMediaId(t.trackId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(t.coverUrl?.let { android.net.Uri.parse(it) })
                    .build()
            )
            .build()
    }

    /** 预加载下一首的 URL，不走跨平台 fallback */
    private fun schedulePreloadNext() {
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            val q = _queue.value
            val cur = _currentTrack.value ?: return@launch
            val idx = q.indexOfFirst { it.trackId == cur.trackId }
            val nextTrack = q.getOrNull(idx + 1) ?: return@launch

            preloadedSources[nextTrack.trackId]
                ?.takeIf { !it.isExpiredForPreload() }
                ?.let { return@launch }

            runCatching { sourceManager.getMediaSourceNoFallback(nextTrack) }
                .getOrNull()
                ?.let { preloadedSources[nextTrack.trackId] = it }
        }
    }

    /** 预加载专用：过期前 5 分钟就视为不可用，避免压在过期边上 */
    private fun MediaSource.isExpiredForPreload(): Boolean {
        if (expireAt == 0L) return false
        return System.currentTimeMillis() > expireAt - 5 * 60_000
    }

    private fun startPositionUpdater() {
        scope.launch {
            while (isActive) {
                controller?.let { c ->
                    if (c.isPlaying || c.playbackState == Player.STATE_READY) {
                        _position.value = c.currentPosition
                    }
                    // 播放中每 ~10s 落盘一次进度（进程被杀最多丢 10 秒）
                    if (c.isPlaying) {
                        if (++persistTick >= 20) {
                            persistTick = 0
                            persistNow()
                        }
                    } else {
                        persistTick = 0
                    }
                }
                delay(500)
            }
        }
    }

    /** 当前队列 + 索引 + 进度写 playback_state（单行覆盖）。timeline 为空时不写，避免覆盖有效存档。 */
    private fun persistNow() {
        val c = controller ?: return
        val q = _queue.value
        if (c.mediaItemCount == 0 || q.isEmpty()) return
        val index = c.currentMediaItemIndex.coerceIn(0, q.size - 1)
        val entity = PlaybackStateEntity(
            queueJson = PlaybackSessionCodec.encode(q),
            currentIndex = index,
            positionMs = c.currentPosition.coerceAtLeast(0L)
        )
        scope.launch { runCatching { database.playbackStateDao().upsert(entity) } }
    }

    /**
     * Activity finish 时调用：只断开 UI 侧 MediaController 连接。
     * 进程级单例必须保持可用（DiLink 杀 Activity 留进程是常态）：
     * 不 cancel scope 与进行中的 job（后台播放/重试/phase2 继续走完，
     * 对已释放 controller 的调用均有 runCatching 包裹或 controller==null 短路），
     * 下次 MainActivity onCreate 经 [ensureConnected] 重连。
     */
    fun disconnect() {
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
        controllerFuture = null
    }

    /** 重进 App 时重连 controller（幂等，已在连接中则跳过）。 */
    fun ensureConnected() = connect()

    companion object {
        private const val TAG = "PlayerManager"
    }
}
