package com.cryonum.captusbprint.core

import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/** Private, bounded wire evidence. Never passed to UI progress or Logcat.
 * Keeps the beginning AND tail so polling cannot erase setup or a final failure.
 * Callers provide only protocol metadata: never document, raster, serial or USB path.
 */
class CaptProtocolTrace(
    var phase: () -> String = { "OPENING" },
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    companion object {
        const val HEAD_EVENTS = 24
        const val TAIL_EVENTS = 72
        const val MAX_RAW_BYTES = 1024
        fun hex(bytes: ByteArray): String =
            bytes.joinToString(" ") { "%02X".format(Locale.ROOT, it.toInt() and 255) }
    }
    private val id = UUID.randomUUID().toString()
    private val head = ArrayList<Map<String, Any?>>()
    private val tail = ArrayDeque<Map<String, Any?>>()
    private var sequence = 0L
    private var exchange = 0L
    private var metadata: Map<String, Any?> = emptyMap()
    private var reservedJob: Int? = null
    private var endAcknowledged = false
    private var releaseAcknowledged = false
    private var confirmedPages: Int? = null

    fun context(values: Map<String, Any?>) { metadata = values.toMap() }

    fun event(name: String, values: Map<String, Any?> = emptyMap()) {
        val item = linkedMapOf<String, Any?>(
            "index" to ++sequence, "exchange" to exchange,
            "elapsedRealtimeMs" to now(), "phase" to phase(), "event" to name
        ).apply { putAll(values) }
        if (head.size < HEAD_EVENTS) head += item
        else {
            if (tail.size == TAIL_EVENTS) tail.removeFirst()
            tail.addLast(item)
        }
    }

    fun begin(command: Int) {
        exchange++
        event("QUERY", mapOf("command" to command))
    }

    fun transfer(direction: String, command: Int, offset: Int, requested: Int, actual: Int,
                 bytes: ByteArray? = null) {
        event("USB_TRANSFER", linkedMapOf<String, Any?>(
            "direction" to direction, "command" to command, "offset" to offset,
            "requestedBytes" to requested, "transferReturnCode" to actual
        ).apply {
            // Only control IN frames belong here; OUT video/job strings are never copied.
            if (direction == "IN" && bytes != null) {
                put("rawHex", hex(bytes.take(MAX_RAW_BYTES).toByteArray()))
                put("rawTruncated", bytes.size > MAX_RAW_BYTES)
            }
        })
    }

    /** Record the actual reply BEFORE validation so a rejected ACK remains available. */
    fun reply(command: Int, raw: ByteArray, payload: ByteArray = byteArrayOf()) {
        event("REPLY", mapOf("command" to command, "rawFrameHex" to hex(raw.take(MAX_RAW_BYTES).toByteArray()),
            "totalBytes" to raw.size, "rawTruncated" to (raw.size > MAX_RAW_BYTES)))
        try { CaptCommandAcks.requireAccepted(command, raw) }
        catch (failure: CaptCommandAckFailure) {
            event("COMMAND_REJECTED", mapOf("command" to command, "resultByte" to failure.resultByte))
            throw failure
        }
        when (command) {
            CaptJobProtocol.SET_JOB_INFO2 -> if (payload.size > 16 &&
                (payload[16].toInt() and 255) == CaptJobProtocol.END_FLAG) endAcknowledged = true
            CaptJobProtocol.RELEASE_UNIT -> releaseAcknowledged = true
        }
    }

    fun reserved(jobId: Int) {
        reservedJob = jobId
        event("RESERVATION_CONFIRMED", mapOf("jobId" to jobId))
    }

    fun pagesConfirmed(pages: Int) {
        confirmedPages = pages
        event("PAGE_COUNTER_CONFIRMED", mapOf("pages" to pages))
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 1, "sessionId" to id, "context" to metadata,
        "lastPhase" to phase(), "reservedJobId" to reservedJob,
        "endAcknowledged" to endAcknowledged, "releaseAcknowledged" to releaseAcknowledged,
        "printerConfirmedPages" to confirmedPages,
        "totalEvents" to sequence, "omittedEvents" to (sequence - head.size - tail.size),
        "events" to (head.toList() + tail.toList()),
        "documentDataIncluded" to false, "automaticRecovery" to false
    )
}
