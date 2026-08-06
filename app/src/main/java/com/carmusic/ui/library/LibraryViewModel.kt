package com.carmusic.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carmusic.data.FavoriteDao
import com.carmusic.data.FavoriteEntity
import com.carmusic.data.HistoryDao
import com.carmusic.data.HistoryEntity
import com.carmusic.playback.PlayerManager
import com.carmusic.source.model.Track
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class FavoriteViewModel(
    favoriteDao: FavoriteDao,
    private val playerManager: PlayerManager
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteEntity>> = favoriteDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 点单行 = 以全部收藏为队列、从该首开播（方向盘可遍历整个收藏夹） */
    fun playAt(index: Int) = playerManager.playAll(favorites.value.map { it.toTrack() }, index)
    fun playAll() = playerManager.playAll(favorites.value.map { it.toTrack() }, 0)
}

class HistoryViewModel(
    historyDao: HistoryDao,
    private val playerManager: PlayerManager
) : ViewModel() {

    val history: StateFlow<List<HistoryEntity>> = historyDao.getRecentFlow(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 点单行 = 以全部历史为队列、从该首开播 */
    fun playAt(index: Int) = playerManager.playAll(history.value.map { it.toTrack() }, index)
    fun playAll() = playerManager.playAll(history.value.map { it.toTrack() }, 0)
}

private fun FavoriteEntity.toTrack() = Track(
    platform = platform,
    id = songId,
    title = title,
    artist = artist,
    album = album,
    coverUrl = coverUrl,
    duration = duration,
    extra = extra
)

private fun HistoryEntity.toTrack() = Track(
    platform = platform,
    id = songId,
    title = title,
    artist = artist,
    album = album,
    coverUrl = coverUrl,
    duration = duration,
    extra = extra
)
