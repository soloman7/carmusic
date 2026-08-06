package com.carmusic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CarColorScheme = darkColorScheme(
    primary = CarPrimary,
    onPrimary = CarOnPrimary,
    primaryContainer = CarPrimaryVariant,
    onPrimaryContainer = CarTextPrimary,
    secondary = CarSecondary,
    onSecondary = CarOnPrimary,
    background = CarBackground,
    onBackground = CarTextPrimary,
    surface = CarSurface,
    onSurface = CarTextPrimary,
    surfaceVariant = CarSurfaceVariant,
    onSurfaceVariant = CarTextSecondary,
    error = CarError,
    outline = CarTextTertiary
)

/** 全项目统一深色主题（车载夜间场景），不再支持主题切换 */
@Composable
fun CarMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CarColorScheme,
        typography = CarTypography,
        content = content
    )
}
