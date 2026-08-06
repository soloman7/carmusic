package com.carmusic.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 统一按压反馈动画。
 *
 * 车载触屏场景下，缩放是最清晰的"按到了"反馈：
 * - IconButton 场景用 [PressableIconButton]（保留默认涟漪，叠加整体缩放）
 * - indication = null 的 clickable 行/卡片用 [rememberPressScale]（缩放是唯一反馈，必须加）
 */

/** 带按压缩放的 IconButton 直替版 */
@Composable
fun PressableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pressedScale: Float = 0.85f,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "pressScale")
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    ) { content() }
}

/**
 * 给 indication = null 的 clickable（行/卡片/大按钮）提供按压缩放。
 * 用法：
 * ```
 * val (interactionSource, scale) = rememberPressScale(0.97f)
 * Modifier
 *     .graphicsLayer { scaleX = scale; scaleY = scale }
 *     .clickable(interactionSource = interactionSource, indication = null, onClick = ...)
 * ```
 */
@Composable
fun rememberPressScale(pressedScale: Float = 0.97f): Pair<MutableInteractionSource, Float> {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "pressScale")
    return interactionSource to scale
}
