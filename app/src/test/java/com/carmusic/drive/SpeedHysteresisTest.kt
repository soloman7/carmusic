package com.carmusic.drive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedHysteresisTest {

    private fun hysteresis() = SpeedHysteresis()

    @Test
    fun `enters driving only after 3 consecutive fast samples`() {
        val h = hysteresis()
        assertFalse(h.onSample(2.0f))   // 样本1：快
        assertFalse(h.onSample(2.0f))   // 样本2：快
        assertTrue(h.onSample(2.0f))    // 样本3：确认进入
    }

    @Test
    fun `isolated fast samples do not enter driving`() {
        val h = hysteresis()
        assertFalse(h.onSample(2.0f))
        assertFalse(h.onSample(0.0f))   // 断档，计数清零
        assertFalse(h.onSample(2.0f))
        assertFalse(h.onSample(2.0f))
        assertTrue(h.onSample(2.0f))    // 需要重新数满 3 个
    }

    @Test
    fun `traffic crawl below exit threshold for less than 10 samples keeps driving`() {
        val h = hysteresis()
        repeat(3) { h.onSample(2.0f) }
        assertTrue(h.isDriving())
        // 等红灯/蠕行 9 个慢样本：不退出
        repeat(9) { assertTrue(h.onSample(0.0f)) }
        assertTrue(h.isDriving())
        // 第 10 个慢样本才退出
        assertFalse(h.onSample(0.0f))
    }

    @Test
    fun `intermittent fast sample during crawl resets exit count`() {
        val h = hysteresis()
        repeat(3) { h.onSample(2.0f) }
        repeat(5) { h.onSample(0.0f) }
        h.onSample(1.5f)   // 挪了一脚，快于退出阈值
        repeat(9) { h.onSample(0.0f) }
        assertTrue(h.isDriving())   // 退出计数被打断重来
        assertFalse(h.onSample(0.0f))
    }

    @Test
    fun `hysteresis band keeps current state`() {
        val h = hysteresis()
        repeat(3) { h.onSample(2.0f) }
        // 1.0 m/s = 3.6 km/h，落在 3~5 km/h 滞回带：既不确认进入也不退出
        repeat(20) { assertTrue(h.onSample(1.0f)) }
    }

    @Test
    fun `no-speed samples count toward exit`() {
        val h = hysteresis()
        repeat(3) { h.onSample(2.0f) }
        repeat(10) { h.onSample(0f) }
        assertFalse(h.isDriving())
    }

    @Test
    fun `reset returns to idle`() {
        val h = hysteresis()
        repeat(3) { h.onSample(2.0f) }
        assertTrue(h.isDriving())
        h.reset()
        assertFalse(h.isDriving())
    }
}
