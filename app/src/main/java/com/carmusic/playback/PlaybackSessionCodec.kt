package com.carmusic.playback

import com.carmusic.source.model.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 播放会话快照的队列编解码（纯 Kotlin，便于 JVM 单测）。
 * playback_state 表里 queueJson 的唯一读写入口，避免 Gson 用法散落。
 */
object PlaybackSessionCodec {
    private val gson = Gson()
    private val listType = object : TypeToken<List<Track>>() {}.type

    fun encode(queue: List<Track>): String = gson.toJson(queue)

    /** 损坏/不兼容的 JSON 一律返回 null，调用方据此放弃恢复。 */
    fun decode(json: String): List<Track>? = runCatching {
        val list: List<Track> = gson.fromJson(json, listType)
        // Gson 宽解析会把 number 强转 string、给非空字段塞 null——关键缺字段的条目直接整体放弃
        list.takeIf { l -> l.all { it.platform?.isNotBlank() == true && it.id?.isNotBlank() == true } }
    }.getOrNull()
}
