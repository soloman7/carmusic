package com.carmusic.maintenance

import com.carmusic.source.SourceUnavailableException
import com.carmusic.source.model.Playlist
import com.carmusic.source.model.Track

/**
 * ContentCleaner 的探测网关 seam：隔离 SourceManager 与播放器，使清理语义可 JVM 级单测。
 *
 * 三态证据契约（与 SourceManager.getMediaSourceNoFallback 对齐）：
 * - [probeTrack] true = 拿到可用播放地址；false = 平台**确认无源**；
 *   抛 [SourceUnavailableException] = 网络故障（证据不可信）。
 * - [playlistTracks] 空列表 = 平台确认歌单无曲目；抛 [SourceUnavailableException] = 网络故障。
 * 删除性决定只能由 false / 空列表驱动，异常一律"不动账本"。
 */
interface ProbeGateway {
    suspend fun ping(): Boolean

    /** @throws SourceUnavailableException 网络故障/超时（证据不可信） */
    suspend fun probeTrack(track: Track): Boolean

    /** @throws SourceUnavailableException 网络故障/超时（证据不可信） */
    suspend fun playlistTracks(playlist: Playlist): List<Track>

    /** 全量推荐歌单（含黑名单成员，供重新探测；平台失败只影响自身分区缺席） */
    suspend fun recommendedPlaylists(): List<Playlist>
}

/** 播放避让 seam：维护流量不与播放抢带宽 */
interface PlaybackYield {
    suspend fun awaitNotPlaying()
}
