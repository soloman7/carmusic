package com.carmusic.source.providers

fun String.escapeJson(): String = buildString(length + 16) {
    for (c in this@escapeJson) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")   // form feed; Kotlin 无 \f 转义, 必须写 Unicode (字面 0x0C 会被格式化工具悄悄删掉)
        else -> if (c.code < 0x20) append(String.format("\\u%04x", c.code)) else append(c)
    }
}
