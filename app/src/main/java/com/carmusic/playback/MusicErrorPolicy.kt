package com.carmusic.playback

/**
 * 音乐播放错误决策状态机（v3.9 从 PlayerManager 拆出，纯逻辑可单测）。
 *
 * 语义（车机场景，与拆分前逐字一致）：
 * - 同一曲最多重试 [maxRetry] 次（线性退避由调用方执行）；
 * - 重试耗尽且队列 > 1 → 自动跳下一首（连续跳过数不超过队列长度，防无限跳歌）；
 * - 队列只有一首或跳满一轮 → 放弃并给出最终错误文案；
 * - 换歌即重置该曲重试计数；任意一次成功出声即清零跳歌保护。
 */
class MusicErrorPolicy(private val maxRetry: Int = 2) {

    sealed interface Decision {
        /** 第 attempt 次重试（1 起） */
        data class Retry(val attempt: Int, val message: String) : Decision
        /** 重试耗尽，自动跳下一首 */
        data class SkipNext(val message: String) : Decision
        /** 无路可走，放弃 */
        data class GiveUp(val message: String) : Decision
    }

    private var retryCount = 0
    private var consecutiveFailTrack: String? = null
    private var consecutiveSkips = 0

    /** 任意一曲成功出声（isPlaying=true）：重置跳歌保护 */
    fun onPlaybackSuccess() {
        consecutiveSkips = 0
    }

    /** 新播放会话（play/playAll/电台切入）：全部归零 */
    fun onSessionChanged() {
        retryCount = 0
        consecutiveFailTrack = null
    }

    /** 错误处理入口：换曲则重置该曲重试计数（与拆分前顺序一致，先于失败判定） */
    fun onTrackSwitched(trackId: String?) {
        if (trackId != consecutiveFailTrack) {
            retryCount = 0
            consecutiveFailTrack = trackId
        }
    }

    fun onFailure(trackId: String?, title: String?, queueSize: Int, cause: String?): Decision {
        if (trackId == null || retryCount >= maxRetry) {
            return if (queueSize > 1 && consecutiveSkips < queueSize) {
                consecutiveSkips++
                retryCount = 0
                consecutiveFailTrack = null
                Decision.SkipNext("「${title ?: "当前歌曲"}」播放失败，自动跳到下一首")
            } else {
                Decision.GiveUp(
                    if (queueSize > 1) "列表歌曲均播放失败，请检查网络后重试"
                    else "播放失败（已重试 $maxRetry 次）：${cause ?: ""}"
                )
            }
        }
        retryCount++
        return Decision.Retry(retryCount, retryMessage(retryCount))
    }

    fun retryMessage(attempt: Int) = "加载失败，第 $attempt/$maxRetry 次重试…"
}
