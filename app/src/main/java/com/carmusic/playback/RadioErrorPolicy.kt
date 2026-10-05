package com.carmusic.playback

/**
 * 电台错误恢复决策状态机（v3.9 从 PlayerManager 拆出，纯逻辑可单测）。
 *
 * 语义（D5 本地优先恢复，与拆分前逐字一致）：
 * - 一次收听会话内只本地重起流一次（1s 退避由调用方执行）；
 * - 重起仍败 = 该台确认挂账（调用方写 localDeadUntil），按驾驶态分流提示；
 * - STATE_READY / 新会话起点都重置重起标记。
 */
class RadioErrorPolicy {

    enum class Phase { RESTART, DEAD }

    private var restartUsed = false

    fun onSessionStart() {
        restartUsed = false
    }

    fun onPlaybackReady() {
        restartUsed = false
    }

    /**
     * 播放错误分流：RESTART = 先本地重起一次；DEAD = 重起已用过，按死台处置。
     * DEAD 分支消费后标记复位（下次收听是全新会话）。
     */
    fun onError(): Phase =
        if (restartUsed) {
            restartUsed = false
            Phase.DEAD
        } else {
            restartUsed = true
            Phase.RESTART
        }

    fun reconnectMessage() = "信号中断，重新连接…"

    fun deadMessage(name: String, driving: Boolean, hasNextFavorite: Boolean): String = when {
        driving && hasNextFavorite -> "「$name」中断，已切到下一收藏台"
        driving -> "「$name」中断，且没有可用的收藏台"
        else -> "「$name」播放中断，该台可能已下线"
    }
}
