package com.carmusic.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音乐错误决策状态机(v3.9 拆分回归):与 PlayerManager 内联旧实现逐字对齐的语义锁。
 */
class MusicErrorPolicyTest {

    private fun failure(policy: MusicErrorPolicy, trackId: String?, queueSize: Int = 3) =
        policy.onFailure(trackId, title = trackId?.let { "歌$it" }, queueSize = queueSize, cause = "boom")

    @Test
    fun `retry escalates then auto skips within queue length`() {
        val p = MusicErrorPolicy(maxRetry = 2)
        p.onTrackSwitched("a")
        assertTrue(failure(p, "a") is MusicErrorPolicy.Decision.Retry)
        assertTrue(failure(p, "a") is MusicErrorPolicy.Decision.Retry)
        // 第 3 次失败:重试耗尽(2)且队列 3>1 → 自动跳下一首
        val d3 = failure(p, "a")
        assertTrue(d3 is MusicErrorPolicy.Decision.SkipNext)
    }

    @Test
    fun `skip protection gives up after full queue round`() {
        val p = MusicErrorPolicy(maxRetry = 2)
        // 队列 2 首:null-trackId 直接触发跳歌路径;consecutiveSkips 0→1→2,第 3 次到上限 → 放弃
        p.onTrackSwitched(null)
        val s1 = failure(p, null, queueSize = 2)
        val s2 = failure(p, null, queueSize = 2)
        val s3 = failure(p, null, queueSize = 2)
        assertTrue(s1 is MusicErrorPolicy.Decision.SkipNext)
        assertTrue(s2 is MusicErrorPolicy.Decision.SkipNext)
        assertTrue(s3 is MusicErrorPolicy.Decision.GiveUp)
        assertTrue((s3 as MusicErrorPolicy.Decision.GiveUp).message.contains("列表歌曲均播放失败"))
    }

    @Test
    fun `single item queue gives up without skip after retries`() {
        val p = MusicErrorPolicy(maxRetry = 2)
        p.onTrackSwitched("solo")
        failure(p, "solo")
        val d = failure(p, "solo", queueSize = 1)
        // 第 2 次失败:retryCount(1) < 2 → Retry;第 3 次 → GiveUp(单曲无跳歌)
        assertTrue(d is MusicErrorPolicy.Decision.Retry)
        val d2 = failure(p, "solo", queueSize = 1)
        assertTrue(d2 is MusicErrorPolicy.Decision.GiveUp)
        assertTrue((d2 as MusicErrorPolicy.Decision.GiveUp).message.contains("已重试 2 次"))
    }

    @Test
    fun `track switch resets retry count for the new track`() {
        val p = MusicErrorPolicy(maxRetry = 2)
        p.onTrackSwitched("a")
        failure(p, "a"); failure(p, "a")   // a 已到重试上限
        p.onTrackSwitched("b")             // 换歌
        val d = failure(p, "b")
        assertTrue("换歌后重试计数必须从 1 重新开始", (d as MusicErrorPolicy.Decision.Retry).attempt == 1)
    }

    @Test
    fun `playback success clears skip protection`() {
        val p = MusicErrorPolicy(maxRetry = 2)
        p.onTrackSwitched(null)
        failure(p, null); failure(p, null)   // consecutiveSkips = 2(队列 3 内)
        p.onPlaybackSuccess()
        val d = failure(p, null)
        assertTrue("成功出声后跳歌保护清零,可继续跳", d is MusicErrorPolicy.Decision.SkipNext)
    }

    @Test
    fun `retry message matches legacy wording`() {
        val p = MusicErrorPolicy(maxRetry = 2)
        assertEquals("加载失败，第 1/2 次重试…", p.retryMessage(1))
    }
}
