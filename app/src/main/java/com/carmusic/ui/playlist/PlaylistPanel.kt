package com.carmusic.ui.playlist

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarSurfaceVariant
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.CarTextTertiary
import com.carmusic.ui.theme.PressableIconButton
import com.carmusic.ui.theme.platformColor
import com.carmusic.ui.theme.rememberPressScale

/**
 * 推荐歌单面板：网格层 ⇄ 曲目层（面板内导航，不走 NavController）
 */
@Composable
fun PlaylistPanel(
    modifier: Modifier = Modifier,
    vm: PlaylistViewModel
) {
    val selected by vm.selected.collectAsStateWithLifecycle()

    Crossfade(targetState = selected, modifier = modifier, label = "playlistNav") { sel ->
        if (sel == null) {
            GridLevel(vm)
        } else {
            TrackLevel(vm, sel)
        }
    }
}

@Composable
private fun GridLevel(vm: PlaylistViewModel) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val startingId by vm.startingPlaylist.collectAsStateWithLifecycle()
    val squarePlatform by vm.squarePlatform.collectAsStateWithLifecycle()
    val squareList by vm.squareList.collectAsStateWithLifecycle()
    val squareLoading by vm.squareLoading.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "推荐歌单",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            val refreshTransition = rememberInfiniteTransition(label = "refreshSpin")
            val spinRotation by refreshTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "refreshSpin"
            )
            PressableIconButton(onClick = { vm.refresh() }, enabled = !loading) {
                Icon(
                    Icons.Default.Refresh,
                    "刷新",
                    tint = if (loading) CarPrimary else CarTextSecondary,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(if (loading) spinRotation else 0f)
                )
            }
        }

        // 平台筛选：推荐 = 聚合视图；其余 = 该平台歌单广场（分页）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PLATFORM_FILTERS.forEach { (key, label) ->
                val selected = squarePlatform == key
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (selected) CarPrimary else CarSurfaceVariant)
                        .clickable { vm.selectPlatform(key) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (selected) Color.Black else CarTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val isSquare = squarePlatform != null
        val displayList = if (isSquare) squareList else playlists

        when {
            !isSquare && loading && playlists.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CarPrimary)
            }
            !isSquare && playlists.isEmpty() && error != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "加载失败", color = CarTextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { vm.refresh() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CarPrimary,
                            contentColor = Color.Black
                        )
                    ) { Text("重试") }
                }
            }
            displayList.isEmpty() && isSquare && squareLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CarPrimary)
            }
            displayList.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (squarePlatform == "jamendo") "免费电台需先在设置页填入 Jamendo client_id"
                    else "暂无推荐歌单",
                    color = CarTextTertiary
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(displayList, key = { it.playlistId }) { playlist ->
                    PlaylistCell(
                        playlist = playlist,
                        starting = startingId == playlist.playlistId,
                        onClick = { vm.select(playlist) },
                        onPlayClick = { vm.playPlaylist(playlist) }
                    )
                }
                if (isSquare && hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val (moreInteraction, moreScale) = rememberPressScale(0.97f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .graphicsLayer { scaleX = moreScale; scaleY = moreScale }
                                .clip(RoundedCornerShape(16.dp))
                                .background(CarSurfaceVariant)
                                .clickable(
                                    interactionSource = moreInteraction,
                                    indication = null,
                                    enabled = !squareLoading
                                ) { vm.loadMore() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (squareLoading) {
                                CircularProgressIndicator(
                                    color = CarPrimary,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text("加载更多", color = CarPrimary, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCell(
    playlist: Playlist,
    starting: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val (interactionSource, scale) = rememberPressScale(0.96f)

    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        // 封面 + 右下角悬浮一键播放钮（内层点击优先，不触发进入曲目层）
        Box {
            if (playlist.coverUrl != null) {
                AsyncImage(
                    model = playlist.coverUrl,
                    contentDescription = playlist.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CarSurfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                // 榜单占位封面：平台色底 + 榜单图标
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(platformColor(playlist.platform).copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Leaderboard,
                        null,
                        tint = platformColor(playlist.platform),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            val (playInteraction, playScale) = rememberPressScale(0.88f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .graphicsLayer { scaleX = playScale; scaleY = playScale }
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CarPrimary)
                    .clickable(
                        interactionSource = playInteraction,
                        indication = null,
                        onClick = onPlayClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        "一键播放",
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            if (playlist.trackCount > 0) "${playlist.trackCount} 首" else playlist.description,
            style = MaterialTheme.typography.labelSmall,
            color = CarTextTertiary
        )
    }
}

@Composable
private fun TrackLevel(vm: PlaylistViewModel, playlist: Playlist) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val tracksLoading by vm.tracksLoading.collectAsStateWithLifecycle()
    val currentTrack by vm.currentTrack.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部：返回 + 歌单名 + 播放全部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressableIconButton(onClick = { vm.back() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "返回",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (tracks.isNotEmpty()) {
                val (playAllInteraction, playAllScale) = rememberPressScale(0.93f)
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .graphicsLayer { scaleX = playAllScale; scaleY = playAllScale }
                        .clip(RoundedCornerShape(22.dp))
                        .background(CarPrimary)
                        .clickable(
                            interactionSource = playAllInteraction,
                            indication = null
                        ) { vm.playAll() }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("播放全部", color = Color.Black, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when {
            tracksLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CarPrimary)
            }
            tracks.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "该榜单暂无歌曲\n（该平台接口可能暂时不可用）",
                    color = CarTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(tracks, key = { _, t -> t.trackId }) { index, track ->
                    TrackRow(
                        index = index,
                        track = track,
                        playing = track.trackId == currentTrack?.trackId,
                        onClick = { vm.playTrack(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(index: Int, track: Track, playing: Boolean, onClick: () -> Unit) {
    val isGrey = track.extra["grey"] == "1"   // 无版权灰歌（网易 privileges st<0 预标记）
    val (rowInteraction, rowScale) = rememberPressScale(0.98f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) CarSurfaceVariant else Color.Transparent)
            .clickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = onClick
            )
            .alpha(if (isGrey) 0.4f else 1f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (playing) CarPrimary else CarTextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (playing) CarPrimary else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = CarTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isGrey) {
            Text(
                "无版权",
                style = MaterialTheme.typography.bodySmall,
                color = CarTextTertiary
            )
        } else if (track.duration > 0) {
            Text(
                formatDuration(track.duration),
                style = MaterialTheme.typography.bodySmall,
                color = CarTextTertiary
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%d:%02d", m, s)
}

/** 平台筛选 chip：推荐 = 聚合视图；其余 = 该平台歌单广场（分页） */
private val PLATFORM_FILTERS = listOf<Pair<String?, String>>(
    null to "推荐",
    "netease" to "网易",
    "qq" to "QQ音乐",
    "migu" to "咪咕",
    "jamendo" to "免费电台"
)
