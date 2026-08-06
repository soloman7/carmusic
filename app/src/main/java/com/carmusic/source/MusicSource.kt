package com.carmusic.source

import com.carmusic.source.model.LyricResult
import com.carmusic.source.model.MediaSource
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track

/**
 * MusicFree 协议风格的统一音源接口
 * 每个平台一个实现
 */
interface MusicSource {
    /** 平台标识：netease / kuwo / migu / kugou / qq / jamendo / maoer */
    val platform: String

    /** 平台显示名 */
    val displayName: String

    /** 搜索音乐，返回最多 limit 条 */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 20): List<Track>

    /** 获取播放 URL */
    suspend fun getMediaSource(track: Track, quality: String = "128k"): MediaSource?

    /** 获取歌词（LRC 原文） */
    suspend fun getLyric(track: Track): LyricResult?

    /** 推荐歌单 / 榜单（默认不支持） */
    suspend fun getRecommendedPlaylists(): List<Playlist> = emptyList()

    /** 歌单曲目（默认不支持） */
    suspend fun getPlaylistTracks(playlist: Playlist): List<Track> = emptyList()

    /** 歌单广场：offset=已加载条数，返回下一页（空列表=没有更多） */
    suspend fun getPlaylistSquare(offset: Int): List<Playlist> = emptyList()
}
