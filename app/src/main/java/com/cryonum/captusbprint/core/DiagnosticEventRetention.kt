package com.cryonum.captusbprint.core

/** Evicts whole oldest events/sessions; never truncates a session's steps. */
object DiagnosticEventRetention {
    const val MAX_EVENTS = 64
    const val MAX_EVENTS_BYTES = 640 * 1024

    fun retain(events: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val kept = events.takeLast(MAX_EVENTS).toMutableList()
        var size = kept.sumOf { bytes(it) }
        while (kept.isNotEmpty() && size > MAX_EVENTS_BYTES) size -= bytes(kept.removeAt(0))
        return kept
    }
    private fun bytes(event: Map<String, Any?>) = DiagnosticJson.stringify(event).toByteArray(Charsets.UTF_8).size + 1
}
