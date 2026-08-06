package com.carmusic.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.carmusic.CarMusicApp
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.PressableIconButton

/**
 * 驾驶极简模式：封面 + 歌名 + 3 个超大按钮
 * 按钮最小 96dp，便于行驶中盲按
 */
@Composable
fun DriveModeScreen(
    navController: NavController,
    vm: DriveViewModel = viewModel(factory = CarMusicApp.instance.container.driveVMFactory)
) {
    val currentTrack by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val isFavorite by vm.isFavorite.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景：封面图 + 半透明黑色遮罩（低端车机 GPU 扛不住全屏 blur）
        currentTrack?.coverUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.4f
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
        )

        // 退出按钮（左上角）
        PressableIconButton(
            onClick = { navController.popBackStack() },
            pressedScale = 0.9f,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .size(64.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(50))
        ) {
            Icon(Icons.Default.Close, "退出", tint = Color.White, modifier = Modifier.size(32.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // 大封面
            AsyncImage(
                model = currentTrack?.coverUrl,
                contentDescription = "封面",
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1E1E1E)),
                contentScale = ContentScale.Crop
            )

            // 歌名 + 艺术家
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = currentTrack?.title ?: "未在播放",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = currentTrack?.artist ?: "",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CarTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // 4 个超大按钮：收藏 | 上一曲 | 播放 | 下一曲（大按钮缩小反馈要更明显）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // isFavorite 为 null 表示加载中（切歌瞬间），不渲染图标态避免闪"未收藏"
                PressableIconButton(onClick = { vm.toggleFavorite() }, pressedScale = 0.88f, modifier = Modifier.size(96.dp)) {
                    isFavorite?.let { fav ->
                        Icon(
                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "收藏",
                            tint = if (fav) Color(0xFFFF6B9D) else Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }

                PressableIconButton(onClick = { vm.prev() }, pressedScale = 0.88f, modifier = Modifier.size(96.dp)) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        "上一曲",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }

                PressableIconButton(
                    onClick = { vm.toggle() },
                    pressedScale = 0.9f,
                    modifier = Modifier
                        .size(120.dp)
                        .background(CarPrimary, RoundedCornerShape(60))
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "暂停" else "播放",
                        tint = Color.Black,
                        modifier = Modifier.size(72.dp)
                    )
                }

                PressableIconButton(onClick = { vm.next() }, pressedScale = 0.88f, modifier = Modifier.size(96.dp)) {
                    Icon(
                        Icons.Default.SkipNext,
                        "下一曲",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
