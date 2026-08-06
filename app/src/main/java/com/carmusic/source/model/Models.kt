package com.carmusic.source.model

import java.io.Serializable

/**
 * 统一的音轨模型 - 跨 7 个平台
 */
data class Track(
    val platform: String,     // netease / kuwo / migu / kugou / qq / jamendo / maoer
    val id: String,           // 平台内唯一 id
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String? = null,
    val duration: Long = 0,   // 秒
    val extra: Map<String, String> = emptyMap()  // 平台特有字段
) : Serializable {
    /** 跨平台唯一 id */
    val trackId: String get() = "$platform:$id"
}

/**
 * 播放 URL + 过期时间
 */
data class MediaSource(
    val url: String,
    val expireAt: Long = 0,        // Unix 毫秒，0 表示不过期
    val quality: String = "128k",  // 128k / 320k / flac
    val headers: Map<String, String> = emptyMap()
) {
    fun isExpired(): Boolean {
        if (expireAt == 0L) return false
        return System.currentTimeMillis() > expireAt - 60_000  // 提前 1 分钟判定过期
    }
}

/**
 * 歌词
 */
data class LyricResult(
    val lrc: String,
    val tlyric: String? = null  // 翻译
)

/**
 * 推荐歌单 / 榜单
 */
data class Playlist(
    val platform: String,          // netease / kuwo / migu / kugou / qq / jamendo / maoer
    val id: String,                // netease 数字 id；榜单 "top:26" / "rank:8888"；Jamendo "chart:week"/"tag:xx"；咪咕 "pl:xx"；QQ "diss:xx"
    val name: String,
    val coverUrl: String? = null,  // 榜单为 null，UI 画占位封面
    val trackCount: Int = 0,
    val description: String = "",
    val isTopList: Boolean = false
) : Serializable {
    /** 跨平台唯一 id */
    val playlistId: String get() = "$platform:$id"
}
