package com.cryonum.captusbprint.core

/**
 * Small deterministic JSON writer for anonymized diagnostic data.
 * Supports only explicitly modeled primitives, lists and string-keyed maps.
 * No implicit toString() fallback: accidental Java/USB object serialization
 * (including device paths or serial numbers) must fail closed.
 */
object DiagnosticJson {
    fun stringify(value: Any?): String = buildString { appendJson(value) }

    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> {
                require(value.isFinite()) { "Non-finite JSON number" }
                append(value.toString())
            }
            is Double -> {
                require(value.isFinite()) { "Non-finite JSON number" }
                append(value.toString())
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { i, entry ->
                    require(entry.key is String) { "JSON keys must be strings" }
                    if (i > 0) append(',')
                    appendQuoted(entry.key as String)
                    append(':')
                    appendJson(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) append(',')
                    appendJson(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("Unsupported diagnostic JSON value type")
        }
    }

    private fun StringBuilder.appendQuoted(input: String) {
        append('"')
        for (c in input) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
            }
        }
        append('"')
    }
}
