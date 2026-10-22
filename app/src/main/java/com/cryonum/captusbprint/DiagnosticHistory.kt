package com.cryonum.captusbprint

import android.util.AtomicFile
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves a bounded diagnostic JSON snapshot in app-private storage. Never opens
 * Android Sharesheet, contacts Telegram, or makes any network request.
 */
internal class DiagnosticHistory(private val destination: File) {
    fun save(json: String) {
        val atomic = AtomicFile(destination)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            if (output != null) atomic.failWrite(output)
            Log.w("CaptDiagnostic", "Could not save private JSON history", error)
        }
    }

    fun loadEvents(): List<Map<String, Any?>> = runCatching {
        val json = JSONObject(destination.readText(Charsets.UTF_8))
        val list = json.optJSONArray("commands") ?: return@runCatching emptyList()
        val result = mutableListOf<Map<String, Any?>>()
        for (index in maxOf(0, list.length() - 64) until list.length()) {
            val event = toNative(list.opt(index)) as? Map<*, *> ?: continue
            if (event.keys.all { it is String }) {
                @Suppress("UNCHECKED_CAST")
                result.add(event as Map<String, Any?>)
            }
        }
        result
    }.getOrElse {
        emptyList()
    }

    private fun toNative(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val result = linkedMapOf<String, Any?>()
            val names = value.keys()
            while (names.hasNext()) {
                val key = names.next()
                result[key] = toNative(value.opt(key))
            }
            result
        }
        is JSONArray -> (0 until value.length()).map { toNative(value.opt(it)) }
        is String, is Boolean, is Int, is Long, is Float, is Double -> value
        else -> null
    }
}
