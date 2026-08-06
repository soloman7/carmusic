package com.carmusic.ui.eq

import androidx.lifecycle.ViewModel
import com.carmusic.playback.EqManager

/**
 * 音效弹窗 ViewModel：薄封装 EqManager（状态与操作都直转）
 */
class EqViewModel(val eqManager: EqManager) : ViewModel() {

    val available = eqManager.available
    val enabled = eqManager.enabled
    val spec = eqManager.spec
    val presetIndex = eqManager.presetIndex
    val bandLevels = eqManager.bandLevels
    val bass = eqManager.bass
    val virtualizer = eqManager.virtualizer

    fun setEnabled(b: Boolean) = eqManager.setEnabled(b)
    fun usePreset(index: Int) = eqManager.usePreset(index)
    fun setBandLevel(band: Int, level: Int) = eqManager.setBandLevel(band, level)
    fun setBass(v: Int) = eqManager.setBass(v)
    fun setVirtualizer(v: Int) = eqManager.setVirtualizer(v)
}

/** 设备预设英文名 → 中文（未知名原样显示） */
fun presetLabel(name: String): String = when (name.lowercase()) {
    "normal" -> "正常"
    "rock" -> "摇滚"
    "pop" -> "流行"
    "jazz" -> "爵士"
    "classical" -> "古典"
    "dance" -> "舞曲"
    "heavy metal" -> "重金属"
    "hip hop" -> "嘻哈"
    "flat" -> "平坦"
    "folk" -> "民谣"
    else -> name
}

/** 中心频率（Hz）→ 显示文本 */
fun freqLabel(hz: Int): String =
    if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"
