package com.carmusic.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.carmusic.CarMusicApp
import com.carmusic.playback.PlayMode
import com.carmusic.ui.playlist.PlaylistPanel
import com.carmusic.ui.playlist.PlaylistViewModel
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarSurfaceVariant
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.CarTextTertiary
import com.carmusic.ui.theme.PressableIconButton
import com.carmusic.ui.theme.platformDisplayName
import com.carmusic.ui.theme.rememberPressScale
import me.wcy.lrcview.LrcView

private enum class RightTab(val label: String) {
    PLAYLIST("歌单"),
    LYRIC("歌词")
}

@Composable
fun PlayerScreen(
    navController: NavController,
    vm: PlayerViewModel = viewModel(factory = CarMusicApp.instance.container.playerVMFactory),
    playlistVm: PlaylistViewModel = viewModel(factory = CarMusicApp.instance.container.playlistVMFactory),
    eqVm: com.carmusic.ui.eq.EqViewModel = viewModel(factory = CarMusicApp.instance.container.eqVMFactory)
) {
    val currentTrack by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val lrcText by vm.lyric.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle(initialValue = false)
    val error by vm.error.collectAsStateWithLifecycle()
    val playMode by vm.playMode.collectAsStateWithLifecycle()
    val eqAvailable by eqVm.available.collectAsStateWithLifecycle()
    val eqEnabled by eqVm.enabled.collectAsStateWithLifecycle()
    var showEqDialog by rememberSaveable { mutableStateOf(false) }

    var lrcViewRef by remember { mutableStateOf<LrcView?>(null) }

    // 右栏页签：无歌时恒为歌单；首次播放自动切歌词；手动切过后不再抢
    var rightTab by rememberSaveable { mutableStateOf(RightTab.PLAYLIST.name) }
    var userOverrode by rememberSaveable { mutableStateOf(false) }
    val effectiveTab = if (currentTrack == null) RightTab.PLAYLIST else RightTab.valueOf(rightTab)

    // 同步播放进度到歌词
    LaunchedEffect(position) {
        lrcViewRef?.updateTime(position)
    }

    // 首次开始播放 → 自动切到歌词
    LaunchedEffect(currentTrack) {
        if (currentTrack != null && !userOverrode && rightTab != RightTab.LYRIC.name) {
            rightTab = RightTab.LYRIC.name
        }
    }

    // 首次显示加载推荐歌单
    LaunchedEffect(Unit) {
        playlistVm.refresh()
    }

    // 错误提示 8 秒后自动消失
    LaunchedEffect(error) {
        if (error != null) {
            kotlinx.coroutines.delay(8_000)
            vm.clearError()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth > 800.dp
        val coverSize = (maxHeight * 0.35f).coerceIn(200.dp, 260.dp)

        // 模糊背景封面
        currentTrack?.coverUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.3f
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.9f)
                        )
                    )
                )
        )

        // 播放错误提示条（顶部悬浮，点击可关闭，8s 自动消失）
        error?.let { msg ->
            Text(
                text = msg,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFB00020))
                    .clickable { vm.clearError() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        val rightPanelContent: @Composable (Modifier) -> Unit = { panelModifier ->
            Column(modifier = panelModifier) {
                RightTabSwitcher(
                    selected = effectiveTab,
                    onSelect = { tab ->
                        rightTab = tab.name
                        userOverrode = true
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                when (effectiveTab) {
                    RightTab.PLAYLIST -> PlaylistPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        vm = playlistVm
                    )
                    RightTab.LYRIC -> LyricPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        lrcText = lrcText,
                        hasTrack = currentTrack != null,
                        onSeek = { vm.seekTo(it) },
                        onLrcViewCreated = { lrcViewRef = it }
                    )
                }
            }
        }

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                LeftPanel(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(24.dp),
                    currentTrack = currentTrack,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    isFavorite = isFavorite,
                    playMode = playMode,
                    coverSize = coverSize,
                    onFavoriteToggle = { vm.toggleFavorite() },
                    onCyclePlayMode = { vm.cyclePlayMode() },
                    onToggle = { vm.toggle() },
                    onPrev = { vm.prev() },
                    onNext = { vm.next() },
                    onSeek = { vm.seekTo(it) },
                    eqAvailable = eqAvailable,
                    eqEnabled = eqEnabled,
                    onEqClick = { showEqDialog = true },
                    navController = navController
                )
                rightPanelContent(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(24.dp)
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                LeftPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.55f)
                        .padding(16.dp),
                    currentTrack = currentTrack,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    isFavorite = isFavorite,
                    playMode = playMode,
                    coverSize = coverSize,
                    onFavoriteToggle = { vm.toggleFavorite() },
                    onCyclePlayMode = { vm.cyclePlayMode() },
                    onToggle = { vm.toggle() },
                    onPrev = { vm.prev() },
                    onNext = { vm.next() },
                    onSeek = { vm.seekTo(it) },
                    eqAvailable = eqAvailable,
                    eqEnabled = eqEnabled,
                    onEqClick = { showEqDialog = true },
                    navController = navController
                )
                rightPanelContent(
                    Modifier
                        .fillMaxWidth()
                        .weight(0.45f)
                        .padding(horizontal = 16.dp)
                )
            }
        }

        if (showEqDialog) {
            com.carmusic.ui.eq.EqDialog(vm = eqVm, onDismiss = { showEqDialog = false })
        }
    }
}

/** 右栏页签切换：歌单 | 歌词 */
@Composable
private fun RightTabSwitcher(
    selected: RightTab,
    onSelect: (RightTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(CarSurfaceVariant)
            .padding(4.dp)
    ) {
        RightTab.entries.forEach { tab ->
            TabSegment(
                text = tab.label,
                selected = tab == selected,
                onClick = { onSelect(tab) }
            )
        }
    }
}

@Composable
private fun RowScope.TabSegment(text: String, selected: Boolean, onClick: () -> Unit) {
    val (tabInteraction, tabScale) = rememberPressScale(0.95f)
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color(0xFF2A2A2A) else Color.Transparent)
            .graphicsLayer { scaleX = tabScale; scaleY = tabScale }
            .clickable(
                interactionSource = tabInteraction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) CarPrimary else CarTextSecondary,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LeftPanel(
    modifier: Modifier,
    currentTrack: com.carmusic.source.model.Track?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    isFavorite: Boolean,
    playMode: PlayMode,
    coverSize: Dp,
    onFavoriteToggle: () -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    eqAvailable: Boolean,
    eqEnabled: Boolean,
    onEqClick: () -> Unit,
    navController: NavController
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部导航：辅助功能降格为次要色，内容层级让位给封面和标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row {
                PressableIconButton(onClick = { navController.navigate("search") }) {
                    Icon(Icons.Default.Search, "搜索", tint = CarTextSecondary, modifier = Modifier.size(24.dp))
                }
                PressableIconButton(onClick = { navController.navigate("favorite") }) {
                    Icon(Icons.Default.Favorite, "收藏", tint = CarTextSecondary, modifier = Modifier.size(24.dp))
                }
                PressableIconButton(onClick = { navController.navigate("history") }) {
                    Icon(Icons.Default.History, "历史", tint = CarTextSecondary, modifier = Modifier.size(24.dp))
                }
                PressableIconButton(onClick = { navController.navigate("settings") }) {
                    Icon(Icons.Default.Settings, "设置", tint = CarTextSecondary, modifier = Modifier.size(24.dp))
                }
            }
            PressableIconButton(onClick = { navController.navigate("drive") }) {
                Icon(Icons.Default.DirectionsCar, "驾驶模式", tint = CarPrimary, modifier = Modifier.size(28.dp))
            }
        }

        // 封面：发光阴影 + 大圆角
        AsyncImage(
            model = currentTrack?.coverUrl,
            contentDescription = "封面",
            modifier = Modifier
                .size(coverSize)
                .aspectRatio(1f)
                .shadow(
                    elevation = 32.dp,
                    shape = RoundedCornerShape(20.dp),
                    clip = false,
                    ambientColor = CarPrimary.copy(alpha = 0.25f),
                    spotColor = CarPrimary.copy(alpha = 0.35f)
                )
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1E1E1E)),
            contentScale = ContentScale.Crop
        )

        // 标题区：歌名大字重对比 + 平台胶囊
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = currentTrack?.title ?: "未在播放",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = currentTrack?.artist ?: "从右侧歌单或搜索选一首歌",
                fontSize = 17.sp,
                color = CarTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (currentTrack != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = platformDisplayName(currentTrack.platform),
                    fontSize = 11.sp,
                    color = CarTextTertiary,
                    modifier = Modifier
                        .background(CarSurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // 进度条：加大 thumb 便于触屏；拖动时只更新本地态，松手才 seek
        Column(modifier = Modifier.fillMaxWidth()) {
            var dragRatio by remember { mutableStateOf<Float?>(null) }
            val effectiveRatio = dragRatio ?: if (duration > 0) position.toFloat() / duration else 0f
            val displayPosition = dragRatio?.let { (it * duration).toLong() } ?: position
            Slider(
                value = effectiveRatio,
                onValueChange = { ratio ->
                    if (duration > 0) dragRatio = ratio
                },
                onValueChangeFinished = {
                    dragRatio?.let { ratio ->
                        if (duration > 0) onSeek((ratio * duration).toLong())
                    }
                    dragRatio = null
                },
                modifier = Modifier.fillMaxWidth(),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(CarPrimary, CircleShape)
                            .border(6.dp, CarPrimary.copy(alpha = 0.25f), CircleShape)
                    )
                },
                colors = SliderDefaults.colors(
                    thumbColor = CarPrimary,
                    activeTrackColor = CarPrimary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(displayPosition), color = CarTextSecondary, fontSize = 13.sp)
                Text(formatTime(duration), color = CarTextSecondary, fontSize = 13.sp)
            }
        }

        // 播放控制（大按钮 + 按压反馈，车载场景）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressableIconButton(onClick = onFavoriteToggle, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "收藏",
                    tint = if (isFavorite) Color(0xFFFF6B9D) else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            PressableIconButton(onClick = onPrev, modifier = Modifier.size(72.dp)) {
                Icon(
                    Icons.Default.SkipPrevious,
                    "上一曲",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            val (playInteraction, playScale) = rememberPressScale(0.92f)
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .graphicsLayer { scaleX = playScale; scaleY = playScale }
                    .clip(CircleShape)
                    .background(CarPrimary)
                    .clickable(
                        interactionSource = playInteraction,
                        indication = null,
                        onClick = onToggle
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "暂停" else "播放",
                    tint = Color.Black,
                    modifier = Modifier.size(56.dp)
                )
            }

            PressableIconButton(onClick = onNext, modifier = Modifier.size(72.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    "下一曲",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            // 播放模式：顺序 → 列表循环 → 单曲 → 随机，循环切换
            val (modeIcon, modeTint, modeDesc) = when (playMode) {
                PlayMode.SEQUENCE -> Triple(Icons.Default.Repeat, CarTextSecondary, "顺序播放")
                PlayMode.REPEAT_ALL -> Triple(Icons.Default.Repeat, CarPrimary, "列表循环")
                PlayMode.REPEAT_ONE -> Triple(Icons.Default.RepeatOne, CarPrimary, "单曲循环")
                PlayMode.SHUFFLE -> Triple(Icons.Default.Shuffle, CarPrimary, "随机播放")
            }
            PressableIconButton(onClick = onCyclePlayMode, modifier = Modifier.size(56.dp)) {
                Icon(modeIcon, modeDesc, tint = modeTint, modifier = Modifier.size(26.dp))
            }

            // 音效调节：设备阉割 audiofx 时隐藏（EqManager 探测）
            if (eqAvailable) {
                PressableIconButton(onClick = onEqClick, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Default.Equalizer,
                        "音效",
                        tint = if (eqEnabled) CarPrimary else CarTextSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricPanel(
    modifier: Modifier,
    lrcText: String?,
    hasTrack: Boolean,
    onSeek: (Long) -> Unit,
    onLrcViewCreated: (LrcView?) -> Unit
) {
    var lastLoadedLrc by remember { mutableStateOf<String?>(null) }

    // 面板切走时清空引用，避免向已 detach 的 View 同步进度
    DisposableEffect(Unit) {
        onDispose { onLrcViewCreated(null) }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when {
            // 有歌但歌词还在加载 → 转圈
            lrcText == null && hasTrack -> CircularProgressIndicator(color = CarPrimary)
            // 没有播放内容 → 引导提示，不再永久转圈
            lrcText.isNullOrBlank() && !hasTrack -> Text(
                "选一首歌开始播放",
                color = CarTextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            lrcText.isNullOrBlank() -> Text(
                "暂无歌词",
                color = CarTextSecondary,
                style = MaterialTheme.typography.bodyLarge
            )
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    LrcView(context).apply {
                        setNormalColor(android.graphics.Color.parseColor("#B3FFFFFF"))
                        setCurrentColor(android.graphics.Color.parseColor("#00D9FF"))
                        setNormalTextSize(40f)
                        setCurrentTextSize(64f)
                        setDraggable(true, object : LrcView.OnPlayClickListener {
                            override fun onPlayClick(view: LrcView, time: Long): Boolean {
                                onSeek(time)
                                return true
                            }
                        })
                        // 第三方解析器对非规范歌词（如咪咕原文）可能抛异常，必须防护
                        runCatching { loadLrc(lrcText) }
                        lastLoadedLrc = lrcText
                        onLrcViewCreated(this)
                    }
                },
                update = { view ->
                    val text = lrcText
                    if (!text.isNullOrBlank() && text != lastLoadedLrc) {
                        runCatching {
                            view.loadLrc(text)
                            view.updateTime(0)
                        }
                        lastLoadedLrc = text
                    }
                }
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format("%02d:%02d", m, s)
}
