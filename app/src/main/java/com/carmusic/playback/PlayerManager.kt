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

    // Track 登记表：MediaItem 只带 mediaId，不跨 binder 传 Serializable（BadParcelable 风险敞口归零）
    private val trackRegistry = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Track>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?) = size > 500
        }
    )

    // 下一首 URL 预加载（不触发跨平台 fallback，避免放大请求量）
    private val preloadedSources = object : LinkedHashMap<String, MediaSource>(5, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaSource>?) = size > 5
    }
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
    }

    private fun connect() {
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)
            controller?.repeatMode = exoRepeatMode(_playMode.value)
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
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _duration.value = controller?.duration ?: 0L
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
                refreshAndRetry(track)
            }
        }
    }

    /** 播放一首歌曲 */
    fun play(track: Track, addToQueue: Boolean = false) {
        // 用户主动播放视为新会话，重置重试状态
        retryCount = 0
        consecutiveFailTrack = null
        retryJob?.cancel()
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
                ?: runCatching { sourceManager.getMediaSource(track) }.getOrNull()
            if (mediaSource == null) {
                _error.value = "无法播放：所有平台均无此歌曲的播放源"
                return@launch
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.coverUrl?.let { android.net.Uri.parse(it) })
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(mediaSource.url)
                .setMediaId(track.trackId)
                .setMediaMetadata(metadata)
                .build()
            trackRegistry[track.trackId] = track

            val player = controller ?: return@launch

            if (addToQueue) {
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
    }

    /** 播放列表（替换当前队列）。渐进式：phase1 原平台快路径出结果立即开播，
     *  phase2 跨平台 fallback 后台继续，解析出一首按原始位置插入一首——
     *  避免大批 VIP/独家歌触发 fallback 时几十秒静默，感知"播放不了"。 */
    fun playAll(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        retryCount = 0
        consecutiveFailTrack = null
        retryJob?.cancel()
        preloadedSources.clear()
        // 关键：取消上一个还在跑的整单解析。旧 phase2 若继续往新队列插队，
        // pos 按旧队列算出、队列却已被 setMediaItems 重置 → addMediaItem 越界
        // 抛在 ExoPlayer 主线程 → 进程死（咪咕闪退根因，app 侧防护全接不到）
        playAllJob?.cancel()
        playAllJob = scope.launch {
            _error.value = null
            val player = controller ?: return@launch

            fun buildItem(t: Track, source: MediaSource): MediaItem {
                val meta = MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(t.coverUrl?.let { android.net.Uri.parse(it) })
                    .build()
                trackRegistry[t.trackId] = t
                return MediaItem.Builder()
                    .setUri(source.url)
                    .setMediaId(t.trackId)
                    .setMediaMetadata(meta)
                    .build()
            }

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
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
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
        val target = navigator.nextIndex(
            c.currentMediaItemIndex, c.mediaItemCount, _playMode.value
        ) { Random.nextInt(it) }
        seekToIndexOn(c, target)
    }

    fun previousOn(playerOverride: Player?) {
        val c = playerOverride ?: controller ?: return
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

    private fun refreshAndRetry(track: Track) {
        // URL 过期重新拉；先杀掉进行中的整单解析（refreshAndRetry→play 会重置队列）
        playAllJob?.cancel()
        playAllJob = null
        scope.launch {
            val newSource = runCatching { sourceManager.getMediaSource(track) }.getOrNull()
            if (newSource != null) {
                play(track)
            }
        }
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
                }
                delay(500)
            }
        }
    }

    fun release() {
        retryJob?.cancel()
        preloadJob?.cancel()
        playAllJob?.cancel()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        scope.cancel()   // 同时终止 startPositionUpdater 的 while(isActive)
    }

    companion object {
        private const val TAG = "PlayerManager"
    }
}
