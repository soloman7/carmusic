package com.carmusic.ui.drive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carmusic.data.FavoriteDao
import com.carmusic.playback.PlayerManager
import com.carmusic.ui.player.toFavoriteEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DriveViewModel(
    val playerManager: PlayerManager,
    private val favoriteDao: FavoriteDao
) : ViewModel() {
    val currentTrack = playerManager.currentTrack
    val isPlaying = playerManager.isPlaying

    /** null = 加载中（切歌瞬间），UI 此时不渲染收藏图标态，避免闪"未收藏" */
    val isFavorite: StateFlow<Boolean?> = currentTrack
        .flatMapLatest { t ->
            if (t == null) flowOf<Boolean?>(null)
            else favoriteDao.isFavoriteFlow(t.trackId)
                .map<Boolean, Boolean?> { it }
                .onStart { emit(null) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle() = playerManager.togglePlayPause()
    fun next() = playerManager.next()
    fun prev() = playerManager.previous()

    fun toggleFavorite() {
        val track = currentTrack.value ?: return
        viewModelScope.launch {
            favoriteDao.toggleFavorite(track.toFavoriteEntity())
        }
    }
}
