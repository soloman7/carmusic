package com.carmusic.drive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPS 驾驶检测，滞回 + 连续样本确认（防抖，状态机见 [SpeedHysteresis]）：
 * - 进入：速度连续 3 个样本 > 5 km/h
 * - 退出：速度连续 10 个样本 < 3 km/h（堵车蠕行、等红灯不误退出）
 * 30s 无定位信号自动恢复为非驾驶状态（防止隧道/地库丢星后状态卡住）。
 *
 * v3.4.3 修正两个实测洞：
 * - 无速度字段的定位（NETWORK provider 普遍不带 speed）不再按 0 m/s 计样本——
 *   否则 GPS 弱信号时被网络定位的 10 个假"停驻"样本误判停车、退出驾驶模式，
 *   且退出条件永远凑不齐无法再进入。不确定的样本直接跳过：维持原状态
 *   （不确定时倾向"继续驾驶模式"是安全方向，误锁搜索好过行驶中解锁）。
 * - 30s 超时强制非驾驶时同步 reset 滞回状态机——否则恢复定位后单个旧样本
 *   就把状态弹回驾驶态，UI 反复横跳。
 *
 * start() 幂等且安全：未授权（ACCESS_FINE/COARSE_LOCATION）时不置 running、
 * 不注册监听；权限到位后再次调用即可成功启动。
 */
class DrivingDetector(private val context: Context) {

    companion object {
        private const val TAG = "DrivingDetector"
        private const val TIMEOUT_MS = 30_000L

        /** 纯函数便于单测：无速度字段的样本返回 null（跳过，不计入滞回） */
        fun effectiveSpeed(loc: Location): Float? = if (loc.hasSpeed()) loc.speed else null
    }

    private val _isDriving = MutableStateFlow(false)
    val isDriving = _isDriving.asStateFlow()

    /** 最近一次定位(含无速度字段的样本)——电台"本省"页 GPS 省份推荐用 */
    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastKnownLocation = _lastLocation.asStateFlow()

    /** 定位是否注册成功（false = 无权限或设备无可用 provider） */
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    private val lm = context.getSystemService(LocationManager::class.java)
    private var lastLocationAt = 0L
    private var running = false

    private val hysteresis = SpeedHysteresis()

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastLocationAt = SystemClock.elapsedRealtime()
            _lastLocation.value = loc
            val speed = effectiveSpeed(loc) ?: return   // 无速度字段：跳过，不向任何方向累计
            _isDriving.value = hysteresis.onSample(speed)
        }

        @Deprecated("deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            hysteresis.reset()
            _isDriving.value = false
        }
    }

    private val timeoutChecker = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastLocationAt > TIMEOUT_MS) {
                // 强制非驾驶必须同时清滞回状态机，否则恢复定位后单个旧样本即弹回驾驶态
                hysteresis.reset()
                _isDriving.value = false
            }
            handler.postDelayed(this, 5_000)
        }
    }

    fun start() {
        if (running) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "start() ignored: location permission not granted")
            _active.value = false
            return
        }
        if (lm == null) {
            Log.w(TAG, "start() ignored: LocationManager unavailable")
            _active.value = false
            return
        }
        running = true
        hysteresis.reset()
        lastLocationAt = SystemClock.elapsedRealtime()
        @SuppressLint("MissingPermission") // 上方已显式检查权限
        val registered = tryRegister(LocationManager.GPS_PROVIDER) ||
            tryRegister(LocationManager.NETWORK_PROVIDER)
        if (!registered) {
            Log.w(TAG, "no usable location provider (gps/network)")
            running = false
            _active.value = false
            return
        }
        _active.value = true
        handler.removeCallbacks(timeoutChecker)
        handler.postDelayed(timeoutChecker, 5_000)
    }

    @SuppressLint("MissingPermission")
    private fun tryRegister(provider: String): Boolean {
        val locationManager = lm ?: return false
        if (!locationManager.allProviders.contains(provider)) return false
        return try {
            locationManager.requestLocationUpdates(provider, 2000, 5f, listener)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "requestLocationUpdates($provider) failed: ${t.message}")
            false
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun stop() {
        running = false
        _active.value = false
        hysteresis.reset()
        _isDriving.value = false
        handler.removeCallbacks(timeoutChecker)
        runCatching { lm?.removeUpdates(listener) }
    }
}
