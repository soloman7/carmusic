package com.carmusic.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 电台错误恢复决策(v3.9 拆分回归):一次会话内本地重起一次,仍败=死台分流提示。
 */
class RadioErrorPolicyTest {

    @Test
    fun `first error restarts and second error is dead with state consumed`() {
        val p = RadioErrorPolicy()
        assertEquals(RadioErrorPolicy.Phase.RESTART, p.onError())
        assertEquals(RadioErrorPolicy.Phase.DEAD, p.onError())
        // DEAD 已消费 → 下一个错误是全新会话的重起
        assertEquals(RadioErrorPolicy.Phase.RESTART, p.onError())
    }

    @Test
    fun `playback ready and session start reset restart state`() {
        val p = RadioErrorPolicy()
        p.onError()   // restartUsed = true
        p.onPlaybackReady()
        assertEquals("READY 重置后仍是重起路径", RadioErrorPolicy.Phase.RESTART, p.onError())

        p.onError()   // restartUsed = true
        p.onSessionStart()
        assertEquals("新会话重置后仍是重起路径", RadioErrorPolicy.Phase.RESTART, p.onError())
    }

    @Test
    fun `dead messages match legacy wording per driving state`() {
        val p = RadioErrorPolicy()
        assertEquals("「测试台」中断，已切到下一收藏台", p.deadMessage("测试台", driving = true, hasNextFavorite = true))
        assertEquals("「测试台」中断，且没有可用的收藏台", p.deadMessage("测试台", driving = true, hasNextFavorite = false))
        assertEquals("「测试台」播放中断，该台可能已下线", p.deadMessage("测试台", driving = false, hasNextFavorite = true))
        assertEquals("信号中断，重新连接…", p.reconnectMessage())
    }
}
