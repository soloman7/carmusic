package com.carmusic.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carmusic.data.SettingsRepository
import com.carmusic.data.radio.RadioFavoriteEntity
import com.carmusic.data.radio.RadioRepository
import com.carmusic.data.radio.RadioStationEntity
import com.carmusic.playback.PlayerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 电台三屏(收藏/本省/搜索)。全部数据来自本地 Room(local-first),唯一网络是后台同步。
 * 收藏页永远全量显示(异常台徽标态,C4);可见性过滤只作用于本省/搜索/热门。
 */
class RadioViewModel(
    private val radioRepository: RadioRepository,
    private val settingsRepository: SettingsRepository,
    private val playerManager: PlayerManager
) : ViewModel() {

    val seedState = radioRepository.seedState
    val currentStation = playerManager.currentStation
    val isDriving = radioRepository.isDriving

    enum class Tab { FAVORITES, PROVINCE, SEARCH }

    private val _tab = MutableStateFlow(Tab.FAVORITES)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    fun selectTab(t: Tab) {
        _tab.value = t
        if (t == Tab.PROVINCE) refreshProvince()
    }

    // ---- 收藏页(全量 + 徽标态,C4) ----
    val favoritesWithStatus = radioRepository.favoritesWithStatusFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 本省 ----
    val province = settingsRepository.radioProvince
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _provinceList = MutableStateFlow<List<RadioStationEntity>>(emptyList())
    val provinceList: StateFlow<List<RadioStationEntity>> = _provinceList.asStateFlow()

    /** 省份切换对话框的 GPS 推荐(离线质心) */
    val gpsSuggestion = MutableStateFlow<String?>(null)

    fun refreshProvince() {
        val p = province.value
        if (p.isBlank()) {
            _provinceList.value = emptyList()
            viewModelScope.launch {
                val suggestion = radioRepository.suggestedProvince()
                gpsSuggestion.value = suggestion
                // GPS 可用且用户未设置过 → 自动采用推荐省(可随时手动改)
                if (suggestion != null && province.value.isBlank()) {
                    settingsRepository.setRadioProvince(suggestion)
                    _provinceList.value = runCatching { radioRepository.provinceStations(suggestion) }
                        .getOrDefault(emptyList())
                }
            }
            return
        }
        viewModelScope.launch {
            _provinceList.value = runCatching { radioRepository.provinceStations(p) }.getOrDefault(emptyList())
        }
    }

    fun setProvince(p: String) {
        viewModelScope.launch {
            settingsRepository.setRadioProvince(p)
            _provinceList.value = runCatching { radioRepository.provinceStations(p) }.getOrDefault(emptyList())
        }
    }

    // ---- 搜索(本地 LIKE,防抖 200ms,零网络) ----
    val query = MutableStateFlow("")

    private val _searchResults = MutableStateFlow<List<RadioStationEntity>>(emptyList())
    val searchResults: StateFlow<List<RadioStationEntity>> = _searchResults.asStateFlow()

    private val _hotList = MutableStateFlow<List<RadioStationEntity>>(emptyList())
    val hotList: StateFlow<List<RadioStationEntity>> = _hotList.asStateFlow()

    private val _hotLoaded = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            query.debounce(200).collect { kw ->
                _searchResults.value = if (kw.isBlank()) emptyList()
                else runCatching { radioRepository.searchStations(kw) }.getOrDefault(emptyList())
            }
        }
    }

    fun loadHot() {
        if (_hotLoaded.value) return
        viewModelScope.launch {
            _hotList.value = runCatching { radioRepository.hotStations(50) }.getOrDefault(emptyList())
            _hotLoaded.value = true
        }
    }

    // ---- 播放/收藏/重试 ----

    fun play(station: RadioStationEntity) = playerManager.playRadio(station)

    /** 徽标"点击重试":清零挂账后按收藏播放(用户意图优先,C4) */
    fun play(favorite: RadioFavoriteEntity) {
        viewModelScope.launch {
            radioRepository.clearLocalDead(favorite.stationUuid)
            playerManager.playRadio(favorite)
        }
    }

    fun stop() = playerManager.stopRadio()

    fun toggleFavorite(uuid: String) {
        viewModelScope.launch { radioRepository.toggleFavorite(uuid) }
    }

    fun retrySeed() {
        viewModelScope.launch { radioRepository.ensureSeeded() }
    }
}
