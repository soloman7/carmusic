package com.carmusic.playback

/**
 * 播放模式
 * SEQUENCE    顺序播放：到队尾停住
 * REPEAT_ALL  列表循环：末曲回卷到第一首
 * REPEAT_ONE  单曲循环：只影响"自然播完"，手动切歌仍 ±1
 * SHUFFLE    随机播放：prev 沿 shuffleHistory 回退（可回到"上上首"）
 */
enum class PlayMode { SEQUENCE, REPEAT_ALL, REPEAT_ONE, SHUFFLE }

/**
 * 队列导航器：纯 Kotlin 无 Android 依赖，索引数学全部在这里，便于 JVM 单测。
 *
 * 语义约定（与网易云/QQ音乐车机版对齐）：
 * - count == 0 → null（无操作）
 * - count == 1 → 循环类模式返回 0（重播本曲），SEQUENCE 返回 null
 * - 手动切歌在 REPEAT_ONE 下仍按 ±1 走（单曲循环只管自然播完）
 * - SHUFFLE 的 prev 优先弹历史栈；栈空且 cur==0 时回卷到末曲
 */
class QueueNavigator {

    /** 随机模式回退栈：每次 next 把离开的位置压栈 */
    private val shuffleHistory = ArrayDeque<Int>()

    fun reset() = shuffleHistory.clear()

    /**
     * @param cur 当前索引（传 C.INDEX_UNSET 等负值时按 0 处理）
     * @param rand 随机数源 [0, count)，SHUFFLE 模式使用；默认调用方注入
     */
    fun nextIndex(cur: Int, count: Int, mode: PlayMode, rand: (Int) -> Int = { 0 }): Int? {
        if (count <= 0) return null
        val c = cur.coerceIn(0, count - 1)
        if (count == 1) return if (mode == PlayMode.SEQUENCE) null else 0
        return when (mode) {
            PlayMode.SEQUENCE -> if (c + 1 < count) c + 1 else null
            PlayMode.REPEAT_ALL, PlayMode.REPEAT_ONE -> (c + 1) % count
            PlayMode.SHUFFLE -> {
                shuffleHistory.addLast(c)
                // 从除当前外的索引里随机取一个
                val r = rand(count - 1)
                if (r >= c) r + 1 else r
            }
        }
    }

    fun prevIndex(cur: Int, count: Int, mode: PlayMode): Int? {
        if (count <= 0) return null
        val c = cur.coerceIn(0, count - 1)
        if (count == 1) return if (mode == PlayMode.SEQUENCE) null else 0
        if (mode == PlayMode.SHUFFLE && shuffleHistory.isNotEmpty()) {
            return shuffleHistory.removeLast()
        }
        return when (mode) {
            PlayMode.SEQUENCE -> if (c > 0) c - 1 else null
            PlayMode.REPEAT_ALL, PlayMode.REPEAT_ONE, PlayMode.SHUFFLE ->
                if (c > 0) c - 1 else count - 1
        }
    }
}
