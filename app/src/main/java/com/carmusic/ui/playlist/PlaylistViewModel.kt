package com.carmusic.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carmusic.playback.PlayerManager
import com.carmusic.source.SourceManager
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 推荐歌单面板：网格层（歌单列表）→ 曲目层（歌单内歌曲）
 * 点歌曲 = 整队播放（playAll 从该 index 起）
 */
class PlaylistViewModel(
    private val sourceManager: SourceManager,
    val playerManager: PlayerManager
) : ViewModel() {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    private val _selected = MutableStateFlow<Playlist?>(null)
    val selected = _selected.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _tracksLoading = MutableStateFlow(false)
    val tracksLoading = _tracksLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** 曲目层加载 job：快速连点歌单时先取消旧请求，避免慢响应覆盖新结果 */
    private var selectJob: Job? = null

    init {
        refresh()   // 推荐页首载自动拉取
    }

    /** 一键播放中的歌单 id（网格层封面播放钮转圈用） */
    private val _startingPlaylist = MutableStateFlow<String?>(null)
    val startingPlaylist = _startingPlaylist.asStateFlow()

    // ---- 歌单广场（平台筛选 + 分页加载更多）----

    /** null = 推荐视图；否则为平台 id（netease/qq/migu） */
    private val _squarePlatform = MutableStateFlow<String?>(null)
    val squarePlatform = _squarePlatform.asStateFlow()

    private val _squareList = MutableStateFlow<List<Playlist>>(emptyList())
    val squareList = _squareList.asStateFlow()

    private val _squareLoading = MutableStateFlow(false)
    val squareLoading = _squareLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore = _hasMore.asStateFlow()

    /** 在途分页请求句柄：切平台必须取消，否则旧平台响应会拼进新平台列表并卡死 hasMore */
    private var squareJob: kotlinx.coroutines.Job? = null

    fun selectPlatform(platform: String?) {
        if (_squarePlatform.value == platform) return
        squareJob?.cancel()
        squareJob = null
        _squarePlatform.value = platform
        _squareList.value = emptyList()
        _hasMore.value = true
        _squareLoading.value = false   // 旧请求取消后必须复位,否则新平台第一页被 loading 挡掉
        if (platform != null) loadMore()
    }

    fun loadMore() {
        val p = _squarePlatform.value ?: return
        if (_squareLoading.value || !_hasMore.value) return
        _squareLoading.value = true
        squareJob = viewModelScope.launch {
            try {
                val page = sourceManager.getPlaylistSquare(p, _squareList.value.size)
                // 平台守卫：请求在途时用户可能已切走，过期响应绝不能再进列表
                if (_squarePlatform.value != p) return@launch
                if (page.isEmpty()) {
                    _hasMore.value = false
                } else {
                    _squareList.value = (_squareList.value + page).distinctBy { it.playlistId }
                    if (page.size < 30) _hasMore.value = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // 切平台取消属正常流程
            } catch (e: Exception) {
                if (_squarePlatform.value == p) _error.value = "歌单广场加载失败"
            } finally {
                _squareLoading.value = false
            }
        }
    }

    /** 转发播放状态，用于曲目行高亮 */
    val currentTrack = playerManager.currentTrack

    fun refresh() {
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                _playlists.value = sourceManager.getRecommendedPlaylists()
            } catch (e: Exception) {
                _error.value = "歌单加载失败，请检查网络"
            } finally {
                _loading.value = false
            }
        }
    }

    fun select(playlist: Playlist) {
        selectJob?.cancel()
        _selected.value = playlist
        _tracks.value = emptyList()
        _tracksLoading.value = true
        selectJob = viewModelScope.launch {
            try {
                _tracks.value = sourceManager.getPlaylistTracks(playlist)
            } catch (e: Exception) {
                _error.value = "歌曲加载失败"
            } finally {
                _tracksLoading.value = false
            }
        }
    }

    fun back() {
        _selected.value = null
        _tracks.value = emptyList()
    }

    fun playTrack(index: Int) = playerManager.playAll(_tracks.value, index)

    fun playAll() = playerManager.playAll(_tracks.value, 0)

    /** 网格层一键播放：不进曲目层，拉曲直接整单开播（有 30min 缓存，进过的歌单秒播） */
    fun playPlaylist(playlist: Playlist) {
        if (_startingPlaylist.value != null) return   // 防连点
        viewModelScope.launch {
            _startingPlaylist.value = playlist.playlistId
            try {
                val tracks = sourceManager.getPlaylistTracks(playlist)
                if (tracks.isEmpty()) {
                    _error.value = "「${playlist.name}」暂无歌曲"
                } else {
                    playerManager.playAll(tracks, 0)
                }
            } catch (e: Exception) {
                _error.value = "「${playlist.name}」加载失败"
            } finally {
                _startingPlaylist.value = null
            }
        }
    }
}
