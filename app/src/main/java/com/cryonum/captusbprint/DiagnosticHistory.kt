package com.cryonum.captusbprint

import android.util.AtomicFile
import android.util.Log
import java.io.File
import com.cryonum.captusbprint.core.DiagnosticEventRetention
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves a bounded diagnostic JSON snapshot in app-private storage. Never opens
 * Android Sharesheet, contacts Telegram, or makes any network request.
 */
internal class DiagnosticHistory(private val destination: File) {
    companion object {
        private val lock = Any()
        const val MAX_FILE_BYTES = 1024 * 1024L
    }

    fun save(json: String): Boolean = synchronized(lock) {
        val atomic = AtomicFile(destination)
        var output: java.io.FileOutputStream? = null
        try {
            val incoming = JSONObject(json)
            // Multiple Activity instances may finish around a recreation. Merge complete
            // events under one process-wide lock rather than overwriting newer sessions.
            val combined = (readEvents() + events(incoming)).distinctBy { event ->
                event["eventId"] ?: listOf(event["timeUtc"], event["action"], event["summary"], event["rawResponseFrameHex"])
            }.sortedBy { event -> runCatching {
                java.time.Instant.parse(event["timeUtc"] as String).toEpochMilli()
            }.getOrDefault(0L) }
            val retained = DiagnosticEventRetention.retain(combined)
            incoming.put("commands", JSONArray(retained))
            incoming.put("historyPolicy", JSONObject().put("maxEvents", DiagnosticEventRetention.MAX_EVENTS)
                .put("maxEventBytesTotal", DiagnosticEventRetention.MAX_EVENTS_BYTES)
                .put("wholeSessionEviction", true))
            val bytes = (incoming.toString() + "\n").toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_FILE_BYTES) { "Diagnostic snapshot exceeds size limit" }
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
            true
        } catch (error: Exception) {
            if (output != null) atomic.failWrite(output)
            Log.w("CaptDiagnostic", "Could not save private JSON history", error)
            false
        }
    }

    fun loadEvents(): List<Map<String, Any?>> = synchronized(lock) { readEvents() }

    private fun readEvents(): List<Map<String, Any?>> = runCatching {
        val atomic = AtomicFile(destination)
        val text = atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                check(output.size() + count <= MAX_FILE_BYTES)
                output.write(block, 0, count)
            }
            output.toString("UTF-8")
        }
        DiagnosticEventRetention.retain(events(JSONObject(text)))
    }.getOrElse { emptyList() }

    private fun events(json: JSONObject): List<Map<String, Any?>> {
        val list = json.optJSONArray("commands") ?: return emptyList()
        val result = mutableListOf<Map<String, Any?>>()
        for (index in maxOf(0, list.length() - DiagnosticEventRetention.MAX_EVENTS) until list.length()) {
            val event = toNative(list.opt(index)) as? Map<*, *> ?: continue
            if (event.keys.all { it is String }) {
                @Suppress("UNCHECKED_CAST")
                result.add(event as Map<String, Any?>)
            }
        }
        return result
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
