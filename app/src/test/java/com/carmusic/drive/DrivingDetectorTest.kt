package com.carmusic.drive

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3.4.3 回归测试：无速度字段的定位(NETWORK provider 普遍不带 speed)
 * 不得按 0 m/s 计样本——否则行驶中被网络定位样本误判停车退出驾驶模式,
 * 且退出条件永远凑不齐无法再进入。不确定样本必须跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrivingDetectorTest {

    private fun loc(hasSpeed: Boolean, speed: Float = 0f): Location =
        Location("test").apply {
            this.speed = speed
            if (!hasSpeed) removeSpeed()
        }

    @Test
    fun `sample with speed field yields its speed`() {
        assertEquals(5.0f, DrivingDetector.effectiveSpeed(loc(true, 5.0f))!!)
    }

    @Test
    fun `sample without speed field is skipped not treated as zero`() {
        assertNull("无速度字段必须跳过(返回 null),绝不能按 0 喂给滞回", DrivingDetector.effectiveSpeed(loc(false)))
    }

    @Test
    fun `skipped samples never advance the hysteresis toward exit`() {
        // 场景回归:GPS 弱信号时网络定位无 speed,旧行为喂 0 → 10 个样本退出驾驶态
        val h = SpeedHysteresis()
        // 先进入驾驶
        repeat(3) { h.onSample(5.0f) }
        // 混入任意数量无速度样本 → 不影响滞回
        assertNull(DrivingDetector.effectiveSpeed(loc(false)))
        assertNull(DrivingDetector.effectiveSpeed(loc(false)))
        // 之后正常慢样本才向退出累计(行为不变)
        assertEquals(true, h.onSample(5.0f))
    }
}
