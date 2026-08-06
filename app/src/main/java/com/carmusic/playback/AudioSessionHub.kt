package com.carmusic.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 音频会话 ID 共享枢纽（同进程单例）。
 * MediaController 没有 getAudioSessionId() 的转发（Player 接口只有 onAudioSessionIdChanged 回调），
 * 所以由 PlaybackService 直接从 ExoPlayer 读取并推到这里，EqManager 消费。
 */
object AudioSessionHub {
    val audioSessionId = MutableStateFlow(0)
}
