package com.carmusic.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carmusic.data.SettingsRepository
import com.carmusic.drive.DrivingDetector
import com.carmusic.playback.PlayerManager
import com.carmusic.source.SourceManager
import com.carmusic.source.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(
    private val sourceManager: SourceManager,
    private val settingsRepo: SettingsRepository,
    private val playerManager: PlayerManager,
    drivingDetector: DrivingDetector
) : ViewModel() {

    val isDriving = drivingDetector.isDriving

    private val enabledPlatforms = settingsRepo.enabledPlatforms
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.ALL_PLATFORMS)

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    private val _results = MutableStateFlow<List<Track>>(emptyList())
    val results: StateFlow<List<Track>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /** 非 null 表示最近一次搜索失败（区别于"真无结果"） */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 连续搜索时先取消旧 job，避免慢旧响应覆盖新结果 */
    private var searchJob: Job? = null

    fun setKeyword(k: String) {
        _keyword.value = k
        if (k.isBlank()) {
            _results.value = emptyList()
            _error.value = null
        }
    }

    fun search() {
        val k = _keyword.value
        if (k.isBlank()) return
        searchJob?.cancel()
        _isSearching.value = true
        _error.value = null
        searchJob = viewModelScope.launch {
            try {
                _results.value = sourceManager.searchAll(k, enabledPlatforms.value)
            } catch (e: Exception) {
                Log.e(TAG, "searchAll failed: $k", e)
                _results.value = emptyList()
                _error.value = "搜索失败，请检查网络后重试"
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun play(track: Track) = playerManager.play(track)

    private companion object {
        const val TAG = "SearchViewModel"
    }
}
