package com.carmusic.source.providers

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 宽松 Gson 解析：字段缺失、JsonNull 或类型不符（对象/数组/非数字字符串）时返回 null，不抛异常
 * 用于榜单/歌单等结构容易漂移的响应
 */
fun JsonObject.optStr(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

fun JsonObject.optLong(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

fun JsonObject.optObj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.optArr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
