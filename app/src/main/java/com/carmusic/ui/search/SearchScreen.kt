package com.carmusic.ui.search

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.carmusic.CarMusicApp
import com.carmusic.source.model.Track
import com.carmusic.ui.theme.CarError
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarSurfaceVariant
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.CarTextTertiary
import com.carmusic.ui.theme.PressableIconButton
import com.carmusic.ui.theme.platformColor
import com.carmusic.ui.theme.platformDisplayName
import com.carmusic.ui.theme.rememberPressScale

@Composable
fun SearchScreen(
    navController: NavController,
    vm: SearchViewModel = viewModel(factory = CarMusicApp.instance.container.searchVMFactory)
) {
    val keyword by vm.keyword.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val isSearching by vm.isSearching.collectAsStateWithLifecycle()
    val isDriving by vm.isDriving.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(16.dp)
    ) {
        // 顶部搜索栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressableIconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { vm.setKeyword(it) },
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                placeholder = {
                    Text(
                        if (isDriving) "行驶中，请停车后搜索" else "搜索歌曲、歌手、专辑",
                        color = CarTextTertiary
                    )
                },
                singleLine = true,
                enabled = !isDriving,
                leadingIcon = {
                    Icon(Icons.Default.Search, null, tint = CarTextSecondary)
                },
                trailingIcon = {
                    if (keyword.isNotBlank()) {
                        PressableIconButton(onClick = { vm.setKeyword("") }) {
                            Icon(Icons.Default.Clear, "清空", tint = CarTextSecondary)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CarSurfaceVariant,
                    unfocusedContainerColor = CarSurfaceVariant,
                    disabledContainerColor = CarSurfaceVariant.copy(alpha = 0.5f),
                    focusedBorderColor = CarPrimary,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    cursorColor = CarPrimary,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = CarTextTertiary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            PressableIconButton(
                onClick = { vm.search() },
                enabled = !isDriving,
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (isDriving) CarSurfaceVariant else CarPrimary,
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Icon(
                    Icons.Default.Search, "搜索",
                    tint = if (isDriving) CarTextTertiary else Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isDriving -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "行驶中，搜索已禁用\n请停车后再操作",
                        color = CarTextTertiary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            isSearching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CarPrimary)
                }
            }
            error != null && results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "搜索失败", color = CarError)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { vm.search() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CarPrimary,
                                contentColor = Color.Black
                            )
                        ) { Text("重试") }
                    }
                }
            }
            results.isEmpty() && keyword.isNotBlank() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到相关歌曲", color = CarTextSecondary)
                }
            }
            results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "输入关键词开始搜索\n将同时查询启用中的音乐平台",
                        color = CarTextTertiary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.trackId }) { track ->
                        TrackRow(
                            track = track,
                            onClick = {
                                vm.play(track)
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
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
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
            model = track.coverUrl,
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
                text = track.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${track.artist} · ${track.album}",
                color = CarTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(
                    platformColor(track.platform).copy(alpha = 0.2f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = platformDisplayName(track.platform),
                color = platformColor(track.platform),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Default.PlayArrow,
            "播放",
            tint = CarPrimary,
            modifier = Modifier.size(32.dp)
        )
    }
}
