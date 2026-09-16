package com.carmusic.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.carmusic.CarMusicApp
import com.carmusic.data.radio.RadioRepository
import com.carmusic.data.radio.RadioStationEntity
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarTextPrimary
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.PressableIconButton

/** 电台三屏(收藏/本省/搜索)。local-first:全部本地查询;底部常驻收听条。 */
@Composable
fun RadioScreen(
    navController: NavController,
    vm: RadioViewModel = viewModel(factory = CarMusicApp.instance.container.radioVMFactory)
) {
    val seedState by vm.seedState.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val currentStation by vm.currentStation.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(16.dp)
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressableIconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("电台", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        when (seedState) {
            is RadioRepository.SeedState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val progress by vm.seedProgress.collectAsStateWithLifecycle()
                    CircularProgressIndicator(color = CarPrimary)
                    Spacer(Modifier.height(12.dp))
                    if (progress != null && progress!! > 0) {
                        Text("正在导入电台库 $progress%", color = CarTextSecondary)
                    } else {
                        Text("正在准备电台库…", color = CarTextSecondary)
                    }
                }
            }
            is RadioRepository.SeedState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("电台库导入失败:${(seedState as RadioRepository.SeedState.Error).message}",
                        color = CarTextSecondary)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { vm.retrySeed() }) { Text("重试", color = CarPrimary) }
                }
            }
            RadioRepository.SeedState.Ready -> {
                // 三屏 Tab
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        RadioViewModel.Tab.FAVORITES to "收藏",
                        RadioViewModel.Tab.PROVINCE to "本省",
                        RadioViewModel.Tab.SEARCH to "搜索"
                    ).forEach { (t, label) ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { vm.selectTab(t) },
                            label = { Text(label, color = if (tab == t) Color.Black else CarTextPrimary) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                Box(Modifier.weight(1f)) {
                    when (tab) {
                        RadioViewModel.Tab.FAVORITES -> FavoritesTab(vm)
                        RadioViewModel.Tab.PROVINCE -> ProvinceTab(vm)
                        RadioViewModel.Tab.SEARCH -> SearchTab(vm)
                    }
                }

                // 底部收听条(local-first 之外唯一的播放态)
                currentStation?.let { now ->
                    NowPlayingBar(
                        name = now.name,
                        subtitle = now.displayBitrate +
                            (now.mbPerHour?.let { "  ·  ≈%.1fMB/小时".format(it) } ?: ""),
                        onStop = { vm.stop() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoritesTab(vm: RadioViewModel) {
    val favorites by vm.favoritesWithStatus.collectAsStateWithLifecycle()
    if (favorites.isEmpty()) {
        EmptyHint("还没有收藏电台。去「本省」或「搜索」找一个喜欢的台,点 ☆ 收藏。")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(favorites, key = { it.favorite.stationUuid }) { item ->
            val badge = when (item.status) {
                RadioRepository.StationStatus.LOCAL_DEAD -> "最近失败 · 点击重试" to CarPrimary
                RadioRepository.StationStatus.SERVER_DOWN, RadioRepository.StationStatus.DELETED -> "已失效" to Color.Red
                RadioRepository.StationStatus.OK -> null
            }
            StationRow(
                name = item.favorite.name,
                subtitle = item.favorite.displayBitrate,
                favicon = item.favorite.favicon,
                isFavorite = true,
                badgeText = badge?.first,
                badgeColor = badge?.second,
                onPlay = {
                    if (item.status == RadioRepository.StationStatus.LOCAL_DEAD) vm.play(item.favorite)
                    else vm.play(item.favorite)
                },
                onToggleFavorite = { vm.toggleFavorite(item.favorite.stationUuid) }
            )
        }
    }
}

@Composable
private fun ProvinceTab(vm: RadioViewModel) {
    val province by vm.province.collectAsStateWithLifecycle()
    val list by vm.provinceList.collectAsStateWithLifecycle()
    val gpsSuggestion by vm.gpsSuggestion.collectAsStateWithLifecycle()
    val favs by vm.favoritesWithStatus.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "省份:${province.ifBlank { "未设置" }}${if (!gpsSuggestion.isNullOrBlank() && province.isNotBlank()) "(GPS 推荐)" else ""}",
                color = CarTextSecondary
            )
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { showPicker = true }) { Text("切换省份", color = CarPrimary) }
        }
        Spacer(Modifier.height(4.dp))
        if (province.isBlank() && gpsSuggestion == null) {
            EmptyHint("等待 GPS 定位推荐省份,或手动「切换省份」。")
        } else if (list.isEmpty()) {
            EmptyHint("「$province」暂无收录电台,试试搜索其他城市名。")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.stationUuid }) { station ->
                    val isFav = favs.any { it.favorite.stationUuid == station.stationUuid }
                    StationRow(
                        name = station.name.ifBlank { "未命名电台" },
                        subtitle = station.displayBitrate,
                        favicon = station.favicon,
                        isFavorite = isFav,
                        badgeText = null,
                        badgeColor = null,
                        onPlay = { vm.play(station) },
                        onToggleFavorite = { vm.toggleFavorite(station.stationUuid) }
                    )
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } },
            title = { Text("选择省份") },
            text = {
                LazyColumn {
                    items(RadioRepository.PROVINCES.map { it.first }) { p ->
                        Text(
                            p,
                            color = if (p == province) CarPrimary else CarTextPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setProvince(p)
                                    showPicker = false
                                }
                                .padding(12.dp)
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun SearchTab(vm: RadioViewModel) {
    val isDriving by vm.isDriving.collectAsStateWithLifecycle()
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val hot by vm.hotList.collectAsStateWithLifecycle()
    val favs by vm.favoritesWithStatus.collectAsStateWithLifecycle()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.weight(1f).height(64.dp),
                placeholder = { Text(if (isDriving) "行驶中,搜索已停用" else "搜索台名", color = Color(0x61FFFFFF)) },
                singleLine = true,
                enabled = !isDriving
            )
            if (query.isNotBlank()) {
                PressableIconButton(onClick = { vm.query.value = "" }) {
                    Icon(Icons.Default.Close, "清空", tint = CarTextSecondary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("全球热门:", color = CarTextSecondary)
            TextButton(onClick = { vm.loadHot() }) { Text("看看热门台", color = CarPrimary) }
        }
        Spacer(Modifier.height(4.dp))
        val display = if (query.isNotBlank()) results else hot
        if (display.isEmpty()) {
            EmptyHint(if (query.isNotBlank()) "没有匹配「${query.trim()}」的电台" else "输入台名搜索,或点「看看热门台」")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(display, key = { it.stationUuid }) { station ->
                    val isFav = favs.any { it.favorite.stationUuid == station.stationUuid }
                    StationRow(
                        name = station.name.ifBlank { "未命名电台" },
                        subtitle = station.displayBitrate,
                        favicon = station.favicon,
                        isFavorite = isFav,
                        badgeText = null,
                        badgeColor = null,
                        onPlay = { vm.play(station) },
                        onToggleFavorite = { vm.toggleFavorite(station.stationUuid) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StationRow(
    name: String,
    subtitle: String,
    favicon: String,
    isFavorite: Boolean,
    badgeText: String?,
    badgeColor: Color?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF121212))
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center
        ) {
            if (favicon.isNotBlank()) {
                AsyncImage(
                    model = favicon, contentDescription = null,
                    modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Default.Radio, null, tint = CarTextSecondary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = CarTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subtitle, color = CarTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                badgeText?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it, color = badgeColor ?: CarTextSecondary,
                        modifier = Modifier.clickable(onClick = onPlay),
                        maxLines = 1
                    )
                }
            }
        }
        PressableIconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
            Icon(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                "收藏",
                tint = if (isFavorite) Color(0xFFFF6B35) else CarTextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun NowPlayingBar(name: String, subtitle: String, onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color.Red)
        )
        Spacer(Modifier.width(8.dp))
        Text("LIVE", color = Color.Red)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = CarTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = CarTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PressableIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Stop, "停止收听", tint = CarTextPrimary, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = CarTextSecondary)
    }
}
