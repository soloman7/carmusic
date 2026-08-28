package com.carmusic.maintenance

/**
 * 死链挂账状态机（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 安全语义：单曲探测失败 ≠ 死链（可能是弱网/平台抖动），必须连续两个清理周期
 * 都失败才允许删除；首轮失败进挂账（pending），中间恢复可播自动出账。
 * 删除决定只能由 [onProbed] 返回 [Decision.DELETE] 给出。
 */
class DeadTrackLedger(initialPending: Set<String> = emptySet()) {

    enum class Decision { KEEP, PENDING, DELETE }

    private val pending = mutableSetOf<String>().apply { addAll(initialPending) }

    /** @return 本轮探测结束后的挂账集合（写回 DataStore 供下轮使用） */
    fun pendingAfterRun(): Set<String> = pending.toSet()

    /**
     * @param trackId 探测曲目
     * @param playable 本轮能否取到播放地址
     */
    fun onProbed(trackId: String, playable: Boolean): Decision {
        if (playable) {
            pending.remove(trackId)   // 恢复可播：出账
            return Decision.KEEP
        }
        return if (trackId in pending) {
            pending.remove(trackId)   // 连续两轮失败：确认死链，出账并删
            Decision.DELETE
        } else {
            pending.add(trackId)      // 首轮失败：挂账复核
            Decision.PENDING
        }
    }
}
