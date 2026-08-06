package com.carmusic.ui.theme

import androidx.compose.ui.graphics.Color

/** 平台 id → 展示名（未知平台原样返回） */
fun platformDisplayName(platform: String): String = when (platform) {
    "netease" -> "网易云"
    "qq" -> "QQ音乐"
    "kugou" -> "酷狗"
    "kuwo" -> "酷我"
    "migu" -> "咪咕"
    "jamendo" -> "免费电台"
    "maoer" -> "猫耳FM"
    else -> platform
}

/** 平台品牌色（未知平台回退主题主色） */
fun platformColor(platform: String): Color = when (platform) {
    "netease" -> Color(0xFFE60026)
    "qq" -> Color(0xFF31C27C)
    "kugou" -> Color(0xFF2BA1E0)
    "kuwo" -> Color(0xFFFFD200)
    "migu" -> Color(0xFFEC4C8A)
    "jamendo" -> Color(0xFF9C4FCC)
    "maoer" -> Color(0xFFF5A623)
    else -> CarPrimary
}
