package com.carmusic.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.carmusic.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 音效管理器：Equalizer + BassBoost + Virtualizer（android.media.audiofx，framework API）。
 *
 * 生命周期跟随 PlayerManager 的 audioSessionId：拿到非 0 id 才 attach（sessionId
 * 会随音频输出切换变化，变化时保存当前设置→重建→恢复）。
 * 设备不支持 audiofx（部分车机阉割）时 available=false，UI 隐藏入口，优雅降级。
 */
class EqManager(
    private val settingsRepository: SettingsRepository,
    private val playerManager: PlayerManager
) {

    data class BandInfo(val index: Int, val centerFreqHz: Int)

    data class EqSpec(
        val minLevel: Int,           // millibel，通常 -1500
        val maxLevel: Int,           // 通常 +1500
        val bands: List<BandInfo>,
        val presets: List<String>,   // 设备预设名（原始英文）
        val bassBoostSupported: Boolean,
        val virtualizerSupported: Boolean
    )

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "eq coroutine error", e)
        }
    )

    private var equalizer: Equalizer? = null
    private var bassBoostFx: BassBoost? = null
    private var virtualizerFx: Virtualizer? = null

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _spec = MutableStateFlow<EqSpec?>(null)
    val spec: StateFlow<EqSpec?> = _spec.asStateFlow()

    /** -1 = 自定义；否则为设备预设序号 */
    private val _presetIndex = MutableStateFlow(-1)
    val presetIndex: StateFlow<Int> = _presetIndex.asStateFlow()

    /** 当前各 band 电平（millibel） */
    private val _bandLevels = MutableStateFlow<List<Int>>(emptyList())
    val bandLevels: StateFlow<List<Int>> = _bandLevels.asStateFlow()

    private val _bass = MutableStateFlow(0)
    val bass: StateFlow<Int> = _bass.asStateFlow()

    private val _virtualizer = MutableStateFlow(0)
    val virtualizer: StateFlow<Int> = _virtualizer.asStateFlow()

    private var persistJob: Job? = null

    init {
        scope.launch {
            playerManager.audioSessionId.collect { id ->
                if (id != 0) attach(id) else detach()
            }
        }
    }

    private suspend fun attach(sessionId: Int) {
        detach()
        // 先读持久化设置（重建后恢复）
        val savedEnabled = settingsRepository.eqEnabled.first()
        val savedPreset = settingsRepository.eqPreset.first()
        val savedBands = settingsRepository.eqBands.first()
        val savedBass = settingsRepository.eqBass.first()
        val savedVirt = settingsRepository.eqVirtualizer.first()
        try {
            val eq = Equalizer(0, sessionId)
            val bb = runCatching { BassBoost(0, sessionId) }.getOrNull()
            val vt = runCatching { Virtualizer(0, sessionId) }.getOrNull()

            equalizer = eq
            bassBoostFx = bb
            virtualizerFx = vt

            val range = eq.bandLevelRange
            _spec.value = EqSpec(
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                bands = (0 until eq.numberOfBands).map { i ->
                    BandInfo(index = i, centerFreqHz = eq.getCenterFreq(i.toShort()) / 1000)
                },
                presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
                bassBoostSupported = bb != null,
                virtualizerSupported = vt != null
            )

            // 恢复预设/自定义
            if (savedPreset == "custom") {
                savedBands.split(",").mapNotNull { it.trim().toIntOrNull() }
                    .forEachIndexed { i, v ->
                        if (i < eq.numberOfBands) {
                            runCatching { eq.setBandLevel(i.toShort(), v.coerceIn(range[0].toInt(), range[1].toInt()).toShort()) }
                        }
                    }
                _presetIndex.value = -1
            } else if (eq.numberOfPresets > 0) {
                // numberOfPresets==0 时无预设可用（coerceIn(0,-1) 会抛异常误判 attach 失败）
                val idx = (savedPreset.toIntOrNull() ?: 0).coerceIn(0, eq.numberOfPresets - 1)
                runCatching { eq.usePreset(idx.toShort()) }
                _presetIndex.value = idx
            } else {
                _presetIndex.value = -1
            }
            _bandLevels.value = readLevels(eq)

            // 恢复低音/环绕（强度 0 时效果器本身关掉，省电省 DSP）
            _bass.value = savedBass
            _virtualizer.value = savedVirt
            bb?.let {
                runCatching { it.setStrength(savedBass.coerceIn(0, 1000).toShort()) }
                runCatching { it.enabled = savedBass > 0 }
            }
            vt?.let {
                runCatching { it.setStrength(savedVirt.coerceIn(0, 1000).toShort()) }
                runCatching { it.enabled = savedVirt > 0 }
            }

            runCatching { eq.enabled = savedEnabled }
            _enabled.value = savedEnabled
            _available.value = true
            Log.i(TAG, "attached session=$sessionId bands=${eq.numberOfBands} presets=${eq.numberOfPresets} bb=${bb != null} vt=${vt != null}")
        } catch (t: Throwable) {
            Log.w(TAG, "audiofx unsupported on this device: ${t.message}")
            detach()
            _available.value = false
        }
    }

    private fun detach() {
        runCatching { equalizer?.release() }
        runCatching { bassBoostFx?.release() }
        runCatching { virtualizerFx?.release() }
        equalizer = null
        bassBoostFx = null
        virtualizerFx = null
        // 同步重置状态：效果器已释放，旧 spec/电平不再有效
        _available.value = false
        _spec.value = null
        _enabled.value = false
        _bandLevels.value = emptyList()
        _presetIndex.value = -1
        _bass.value = 0
        _virtualizer.value = 0
    }

    private fun readLevels(eq: Equalizer): List<Int> =
        (0 until eq.numberOfBands).map { i ->
            runCatching { eq.getBandLevel(i.toShort()).toInt() }.getOrDefault(0)
        }

    /** 总开关 */
    fun setEnabled(b: Boolean) {
        _enabled.value = b
        runCatching { equalizer?.enabled = b }
        scope.launch { settingsRepository.setEqEnabled(b) }
    }

    /** 选预设（index 为设备预设序号） */
    fun usePreset(index: Int) {
        val eq = equalizer ?: return
        runCatching { eq.usePreset(index.toShort()) }
        _presetIndex.value = index
        _bandLevels.value = readLevels(eq)
        scope.launch { settingsRepository.setEqPreset(index.toString()) }
    }

    /** 手动调某段：自动切"自定义" */
    fun setBandLevel(band: Int, level: Int) {
        val eq = equalizer ?: return
        val spec = _spec.value ?: return
        val clamped = level.coerceIn(spec.minLevel, spec.maxLevel)
        runCatching { eq.setBandLevel(band.toShort(), clamped.toShort()) }
        _presetIndex.value = -1
        _bandLevels.value = _bandLevels.value.toMutableList().apply {
            if (band in indices) this[band] = clamped
        }
        debouncedPersistCustom()
    }

    fun setBass(value: Int) {
        val v = value.coerceIn(0, 1000)
        _bass.value = v
        bassBoostFx?.let {
            runCatching { it.setStrength(v.toShort()) }
            runCatching { it.enabled = v > 0 }
        }
        debouncedPersist { setEqBass(v) }
    }

    fun setVirtualizer(value: Int) {
        val v = value.coerceIn(0, 1000)
        _virtualizer.value = v
        virtualizerFx?.let {
            runCatching { it.setStrength(v.toShort()) }
            runCatching { it.enabled = v > 0 }
        }
        debouncedPersist { setEqVirtualizer(v) }
    }

    /** 滑条拖动高频触发，500ms 防抖落盘 */
    private fun debouncedPersist(block: suspend SettingsRepository.() -> Unit) {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            settingsRepository.block()
        }
    }

    private fun debouncedPersistCustom() {
        val csv = _bandLevels.value.joinToString(",")
        debouncedPersist {
            setEqPreset("custom")
            setEqBands(csv)
        }
    }

    companion object {
        private const val TAG = "EqManager"
    }
}
