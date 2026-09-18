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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 电台四屏(分类/收藏/本省/搜索)。全部数据来自本地 Room(local-first),唯一网络是后台同步。
 * 收藏页永远全量显示(异常台徽标态,C4);可见性过滤只作用于分类/本省/搜索。
 * 分类页三层浏览栈(v6-D-C:国家 → 省份/分类 → 台列表)由 VM 持有,切 tab 不丢,物理返回逐层回退。
 */
class RadioViewModel(
    private val radioRepository: RadioRepository,
    private val settingsRepository: SettingsRepository,
    private val playerManager: PlayerManager
) : ViewModel() {

    val seedState = radioRepository.seedState
    val seedProgress = radioRepository.seedProgress
    val currentStation = playerManager.currentStation
    val isDriving = radioRepository.isDriving

    enum class Tab { CATEGORY, FAVORITES, PROVINCE, SEARCH }

    private val _tab = MutableStateFlow(Tab.CATEGORY)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    fun selectTab(t: Tab) {
        _tab.value = t
        if (t == Tab.PROVINCE) refreshProvince()
        if (t == Tab.CATEGORY) loadCountries()   // loadCountries 自带去重,重复点击无副作用
    }

    // ---- 分类页(v6-D-A 用户定稿:国家 → 省份/分类;中国 → 省份) ----

    data class CountryCard(val code: String, val displayName: String, val flag: String, val cnt: Int)

    /** 二级网格卡:key = 省名(中国)或分类 id(他国) */
    data class GroupCard(val key: String, val label: String, val cnt: Int)

    private var countriesLoaded = false

    private val _countries = MutableStateFlow<List<CountryCard>>(emptyList())
    val countries: StateFlow<List<CountryCard>> = _countries.asStateFlow()

    /** 进入分类页即加载(v3.7.1:此前只挂 selectTab,默认 tab 进入时无人触发=永久转圈) */
    private val _countriesLoading = MutableStateFlow(false)
    val countriesLoading: StateFlow<Boolean> = _countriesLoading.asStateFlow()

    /** 加载失败态(空列表≠加载中:失败不得伪装成转圈,给重试入口) */
    private val _countriesError = MutableStateFlow<String?>(null)
    val countriesError: StateFlow<String?> = _countriesError.asStateFlow()

    private val _selectedCountry = MutableStateFlow<CountryCard?>(null)
    val selectedCountry: StateFlow<CountryCard?> = _selectedCountry.asStateFlow()

    private val _secondLevel = MutableStateFlow<List<GroupCard>>(emptyList())
    val secondLevel: StateFlow<List<GroupCard>> = _secondLevel.asStateFlow()

    private val _secondLoading = MutableStateFlow(false)
    val secondLoading: StateFlow<Boolean> = _secondLoading.asStateFlow()

    private val _selectedLeaf = MutableStateFlow<GroupCard?>(null)
    val selectedLeaf: StateFlow<GroupCard?> = _selectedLeaf.asStateFlow()

    private val _leafList = MutableStateFlow<List<RadioStationEntity>>(emptyList())
    val leafList: StateFlow<List<RadioStationEntity>> = _leafList.asStateFlow()

    private val _leafLoading = MutableStateFlow(false)
    val leafLoading: StateFlow<Boolean> = _leafLoading.asStateFlow()

    private fun loadCountries(force: Boolean = false) {
        if (_countriesLoading.value) return
        if (countriesLoaded && !force) return
        _countriesLoading.value = true
        _countriesError.value = null
        viewModelScope.launch {
            try {
                _countries.value = radioRepository.countryGroups()
                    .map { CountryCard(it.code, it.displayName, it.flag, it.cnt) }
                countriesLoaded = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _countriesError.value = e.message ?: "加载失败"
            } finally {
                _countriesLoading.value = false
            }
        }
    }

    fun retryCountries() = loadCountries(force = true)

    fun openCountry(c: CountryCard) {
        _selectedCountry.value = c
        _selectedLeaf.value = null
        _leafList.value = emptyList()
        _secondLevel.value = emptyList()
        _secondLoading.value = true
        viewModelScope.launch {
            _secondLevel.value = if (c.code == "CN")
                runCatching { radioRepository.provinceGroups() }.getOrDefault(emptyList())
                    .map { GroupCard(it.key, it.label, it.cnt) }
            else
                runCatching { radioRepository.categoryGroups(c.code) }.getOrDefault(emptyList())
                    .map { GroupCard(it.key, it.label, it.cnt) }
            _secondLoading.value = false
        }
    }

    fun closeCountry() {
        _selectedCountry.value = null
        _secondLevel.value = emptyList()
        closeLeaf()
    }

    fun openLeaf(g: GroupCard) {
        val country = _selectedCountry.value ?: return
        _selectedLeaf.value = g
        _leafLoading.value = true
        viewModelScope.launch {
            _leafList.value = if (country.code == "CN")
                runCatching { radioRepository.provinceStations(g.key) }.getOrDefault(emptyList())
            else
                runCatching { radioRepository.categoryStations(country.code, g.key) }.getOrDefault(emptyList())
            _leafLoading.value = false
        }
    }

    fun closeLeaf() {
        _selectedLeaf.value = null
        _leafList.value = emptyList()
    }

    /** 分类页物理返回:逐层回退;顶层返回 false 交还系统(退电台页) */
    fun onCategoryBack(): Boolean = when {
        _selectedLeaf.value != null -> { closeLeaf(); true }
        _selectedCountry.value != null -> { closeCountry(); true }
        else -> false
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

    init {
        // 转圈根因修复:seed 导入必须在进入电台页时触发(v3.5.0 只挂在重试按钮上,永不执行)
        viewModelScope.launch { radioRepository.ensureSeeded() }
        // v3.7.1:分类默认 tab 首次进入自动加载(等 seed 就绪,避免在导入中的空表上算出空网格)
        viewModelScope.launch {
            radioRepository.seedState.first { it is RadioRepository.SeedState.Ready }
            loadCountries()
        }
        viewModelScope.launch {
            query.debounce(200).collect { kw ->
                _searchResults.value = if (kw.isBlank()) emptyList()
                else runCatching { radioRepository.searchStations(kw) }.getOrDefault(emptyList())
            }
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
