package com.carmusic.source.providers

fun String.escapeJson(): String = buildString(length + 16) {
    for (c in this@escapeJson) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '' -> append("\\f")
        else -> if (c.code < 0x20) append(String.format("\\u%04x", c.code)) else append(c)
    }
}
