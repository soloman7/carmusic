package com.carmusic.playback

import com.carmusic.source.model.Track

/**
 * 播放目标显式类型(v5-D2,M1a 地基):音乐与电台在 PlayerManager 内的一切副作用分流
 * 以此为唯一判定来源,禁止在业务代码散落 mediaId.startsWith("radio:")。
 *
 * 命名空间不变量:音乐 trackId = "$platform:$id",七个音乐平台名均不等于 "radio",
 * 因此 "radio:" 前缀与音乐 mediaId 天然无碰撞(PlaybackTargetTest 锁定该不变量)。
 */
sealed class PlaybackTarget {

    /** 歌曲目标:参与完整音乐语义(历史/预载/重签链/队列持久化/死链出账) */
    data class Music(val track: Track) : PlaybackTarget()

    /** 电台目标:直播流语义——不写历史、不预载、不走音乐重签链、无 seek(各 fence 见 PlayerManager) */
    data class Radio(val uuid: String) : PlaybackTarget()

    companion object {
        const val RADIO_MEDIA_ID_PREFIX = "radio:"

        /** 唯一的电台 mediaId 判定点 */
        fun isRadioMediaId(mediaId: String?): Boolean =
            mediaId != null && mediaId.startsWith(RADIO_MEDIA_ID_PREFIX)
    }
}
