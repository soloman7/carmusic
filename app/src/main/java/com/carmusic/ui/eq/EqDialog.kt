package com.carmusic.ui.eq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carmusic.ui.theme.CarPrimary
import com.carmusic.ui.theme.CarSurfaceVariant
import com.carmusic.ui.theme.CarTextSecondary
import com.carmusic.ui.theme.CarTextTertiary

/**
 * 音效调节弹窗（车机友好大尺寸）：
 * 总开关 → 预设 chips（含"自定义"）→ N 段均衡器 → 低音/环绕
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqDialog(
    vm: EqViewModel,
    onDismiss: () -> Unit
) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val spec by vm.spec.collectAsStateWithLifecycle()
    val presetIndex by vm.presetIndex.collectAsStateWithLifecycle()
    val bandLevels by vm.bandLevels.collectAsStateWithLifecycle()
    val bass by vm.bass.collectAsStateWithLifecycle()
    val virtualizer by vm.virtualizer.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = CarPrimary)
            }
        },
        containerColor = Color(0xFF1E1E1E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("音效调节", style = MaterialTheme.typography.titleLarge)
                Switch(
                    checked = enabled,
                    onCheckedChange = { vm.setEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = CarPrimary)
                )
            }
        },
        text = {
            val s = spec
            if (s == null) {
                Text("音效引擎尚未就绪（开始播放后可用）", color = CarTextSecondary)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // 预设（FlowRow 自动换行，不截断；"自定义"不可点，拖 band 自动进入）
                    Text("预设", color = CarTextSecondary, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = presetIndex == -1,
                            onClick = { },
                            enabled = false,
                            label = { Text("自定义") },
                            colors = FilterChipDefaults.filterChipColors(
                                // 纯标签（恒 disabled）：按选中态直接给 disabled 配色，
                                // material3 无 disabledSelectedLabelColor 参数
                                disabledContainerColor = if (presetIndex == -1) CarPrimary else CarSurfaceVariant,
                                disabledLabelColor = if (presetIndex == -1) Color.Black else Color.White
                            )
                        )
                        s.presets.forEachIndexed { idx, name ->
                            FilterChip(
                                selected = presetIndex == idx,
                                onClick = { vm.usePreset(idx) },
                                label = { Text(presetLabel(name)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = CarSurfaceVariant,
                                    selectedContainerColor = CarPrimary,
                                    selectedLabelColor = Color.Black,
                                    labelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // N 段均衡器（拖动时只更新本地态，松手才下发）
                    Text("均衡器", color = CarTextSecondary, style = MaterialTheme.typography.titleSmall)
                    s.bands.forEach { band ->
                        val level = bandLevels.getOrNull(band.index) ?: 0
                        var dragLevel by remember(band.index) { mutableStateOf<Float?>(null) }
                        val displayLevel = dragLevel?.toInt() ?: level
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                freqLabel(band.centerFreqHz),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(56.dp)
                            )
                            Slider(
                                value = dragLevel ?: level.toFloat(),
                                onValueChange = { dragLevel = it },
                                onValueChangeFinished = {
                                    dragLevel?.let { vm.setBandLevel(band.index, it.toInt()) }
                                    dragLevel = null
                                },
                                valueRange = s.minLevel.toFloat()..s.maxLevel.toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = CarPrimary,
                                    activeTrackColor = CarPrimary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                            Text(
                                "%+.1f".format(displayLevel / 100f),
                                color = CarTextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(48.dp)
                            )
                        }
                    }

                    // 低音增强
                    if (s.bassBoostSupported) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("低音增强", color = CarTextSecondary, style = MaterialTheme.typography.titleSmall)
                        var dragBass by remember { mutableStateOf<Float?>(null) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Slider(
                                value = dragBass ?: bass.toFloat(),
                                onValueChange = { dragBass = it },
                                onValueChangeFinished = {
                                    dragBass?.let { vm.setBass(it.toInt()) }
                                    dragBass = null
                                },
                                valueRange = 0f..1000f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = CarPrimary,
                                    activeTrackColor = CarPrimary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                            Text(
                                "${(dragBass?.toInt() ?: bass) / 10}%",
                                color = CarTextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(48.dp)
                            )
                        }
                    }

                    // 环绕声
                    if (s.virtualizerSupported) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("环绕声", color = CarTextSecondary, style = MaterialTheme.typography.titleSmall)
                        var dragVirt by remember { mutableStateOf<Float?>(null) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Slider(
                                value = dragVirt ?: virtualizer.toFloat(),
                                onValueChange = { dragVirt = it },
                                onValueChangeFinished = {
                                    dragVirt?.let { vm.setVirtualizer(it.toInt()) }
                                    dragVirt = null
                                },
                                valueRange = 0f..1000f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = CarPrimary,
                                    activeTrackColor = CarPrimary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )
                            Text(
                                "${(dragVirt?.toInt() ?: virtualizer) / 10}%",
                                color = CarTextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(48.dp)
                            )
                        }
                    }
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    )
}
