package com.carmusic.drive

/**
 * 速度滞回状态机（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * - 进入：连续 [enterSamples] 个样本 > enterSpeed（5 km/h，快速确认"在开车"）
 * - 退出：连续 [exitSamples] 个样本 < exitSpeed（3 km/h，堵车蠕行/等红灯不误退）
 * - 两阈值之间为滞回带：维持原状态
 */
class SpeedHysteresis(
    private val enterSpeed: Float = ENTER_SPEED_DEFAULT,
    private val exitSpeed: Float = EXIT_SPEED_DEFAULT,
    private val enterSamples: Int = ENTER_SAMPLES_DEFAULT,
    private val exitSamples: Int = EXIT_SAMPLES_DEFAULT
) {
    companion object {
        const val ENTER_SPEED_DEFAULT = 1.4f   // 5 km/h
        const val EXIT_SPEED_DEFAULT = 0.83f   // 3 km/h
        const val ENTER_SAMPLES_DEFAULT = 3
        const val EXIT_SAMPLES_DEFAULT = 10
    }

    private var driving = false
    private var fastStreak = 0
    private var slowStreak = 0

    fun isDriving(): Boolean = driving

    /** 喂入一个速度样本（m/s），返回滞回后的驾驶状态 */
    fun onSample(speed: Float): Boolean {
        if (!driving) {
            fastStreak = if (speed > enterSpeed) fastStreak + 1 else 0
            if (fastStreak >= enterSamples) {
                driving = true
                slowStreak = 0
            }
        } else {
            slowStreak = if (speed < exitSpeed) slowStreak + 1 else 0
            if (slowStreak >= exitSamples) {
                driving = false
                fastStreak = 0
            }
        }
        return driving
    }

    fun reset() {
        driving = false
        fastStreak = 0
        slowStreak = 0
    }
}
