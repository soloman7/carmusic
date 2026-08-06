package com.carmusic.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carmusic.data.FavoriteDao
import com.carmusic.data.FavoriteEntity
import com.carmusic.lyric.LyricRepository
import com.carmusic.playback.PlayerManager
import com.carmusic.source.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    val playerManager: PlayerManager,
    private val lyricRepo: LyricRepository,
    private val favoriteDao: FavoriteDao
) : ViewModel() {

    val currentTrack = playerManager.currentTrack
    val isPlaying = playerManager.isPlaying
    val position = playerManager.position
    val duration = playerManager.duration
    val error = playerManager.error
    val playMode = playerManager.playMode

    private val _lyric = MutableStateFlow<String?>(null)
    val lyric: StateFlow<String?> = _lyric.asStateFlow()

    val isFavorite: Flow<Boolean> = currentTrack
        .filterNotNull()
        .flatMapLatest { favoriteDao.isFavoriteFlow(it.trackId) }

    init {
        viewModelScope.launch {
            // collectLatest：切歌时取消旧歌词加载，避免旧请求晚返回覆盖/残留旧歌词
            currentTrack.collectLatest { t ->
                // 歌词是增强功能，任何异常都不能影响播放主链路
                runCatching {
                    if (t == null) {
                        // 无播放内容时置空串，UI 显示提示文案而非永久转圈
                        _lyric.value = ""
                    } else {
                        _lyric.value = null
                        _lyric.value = lyricRepo.getLyric(t)?.lrc ?: ""
                    }
                }.onFailure { _lyric.value = "" }
            }
        }
    }

    fun toggleFavorite() {
        val track = currentTrack.value ?: return
        viewModelScope.launch {
            favoriteDao.toggleFavorite(track.toFavoriteEntity())
        }
    }

    fun toggle() = playerManager.togglePlayPause()
    fun next() = playerManager.next()
    fun prev() = playerManager.previous()
    fun cyclePlayMode() = playerManager.cyclePlayMode()
    fun seekTo(ms: Long) = playerManager.seekTo(ms)
    fun clearError() = playerManager.clearError()
}

/** Track → 收藏实体（PlayerViewModel/DriveViewModel 共用） */
internal fun Track.toFavoriteEntity() = FavoriteEntity(
    trackId = trackId,
    platform = platform,
    songId = id,
    title = title,
    artist = artist,
    album = album,
    coverUrl = coverUrl,
    duration = duration,
    extra = extra
)
