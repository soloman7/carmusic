package com.carmusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.carmusic.CarMusicApp
import com.carmusic.data.FavoriteEntity
import com.carmusic.data.HistoryEntity
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarSurfaceVariant
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.CarTextTertiary
import com.carmusic.ui.theme.PressableIconButton
import com.carmusic.ui.theme.rememberPressScale

@Composable
fun FavoriteScreen(
    navController: NavController,
    vm: FavoriteViewModel = viewModel(factory = CarMusicApp.instance.container.favoriteVMFactory)
) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    TrackListScreen(
        navController = navController,
        title = "收藏",
        emptyHint = "还没有收藏歌曲",
        items = favorites.map { it.toRowData() },
        onPlayAll = { vm.playAll() },
        onPlayAt = { vm.playAt(it) }
    )
}

@Composable
fun HistoryScreen(
    navController: NavController,
    vm: HistoryViewModel = viewModel(factory = CarMusicApp.instance.container.historyVMFactory)
) {
    val history by vm.history.collectAsStateWithLifecycle()
    TrackListScreen(
        navController = navController,
        title = "播放历史",
        emptyHint = "还没有播放记录",
        items = history.map { it.toRowData() },
        onPlayAll = { vm.playAll() },
        onPlayAt = { vm.playAt(it) }
    )
}

/** 收藏/历史共用的行数据（第二行固定为 "artist · album"） */
private data class TrackRowData(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val duration: Long
)

private fun FavoriteEntity.toRowData() =
    TrackRowData(trackId, title, "$artist · $album", coverUrl, duration)

private fun HistoryEntity.toRowData() =
    TrackRowData(trackId, title, "$artist · $album", coverUrl, duration)

@Composable
private fun TrackListScreen(
    navController: NavController,
    title: String,
    emptyHint: String,
    items: List<TrackRowData>,
    onPlayAll: () -> Unit,
    onPlayAt: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PressableIconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("(${items.size})", color = CarTextSecondary, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                PressableIconButton(onClick = {
                    onPlayAll()
                    navController.navigate("player") { popUpTo("player") { inclusive = true } }
                }) {
                    Icon(Icons.Default.PlayArrow, "播放全部", tint = CarPrimary, modifier = Modifier.size(28.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyHint, color = CarTextTertiary)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items, key = { _, it -> it.trackId }) { index, item ->
                    TrackRow(
                        title = item.title,
                        artist = item.artist,
                        coverUrl = item.coverUrl,
                        duration = item.duration,
                        onClick = {
                            onPlayAt(index)
                            navController.navigate("player") {
                                popUpTo("player") { inclusive = true }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    title: String,
    artist: String,
    coverUrl: String?,
    duration: Long,
    onClick: () -> Unit
) {
    val (rowInteraction, rowScale) = rememberPressScale(0.98f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
            .clip(RoundedCornerShape(12.dp))
            .background(CarSurfaceVariant)
            .clickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = onClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CarSurfaceVariant),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                artist,
                color = CarTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (duration > 0) {
            Text(
                formatDuration(duration),
                color = CarTextTertiary,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(Icons.Default.PlayArrow, "播放", tint = CarPrimary, modifier = Modifier.size(32.dp))
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%d:%02d", m, s)
}
