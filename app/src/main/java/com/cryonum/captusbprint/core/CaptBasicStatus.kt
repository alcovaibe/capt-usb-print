package com.cryonum.captusbprint.core

/** Raw fields of a framed Basic reply; a short response's status meaning is unverified. */
data class CaptBasicStatus(
    val statusByte: Int,
    val secondaryByte: Int,
    /** A short frame omits the buffer fields; this does not prove status semantics. */
    val bufferLowByte: Int?,
    val bufferHighByte: Int?,
    val payloadBytes: Int
) {
    val responseForm: String get() = when {
        payloadBytes == 2 -> "SHORT_UNVERIFIED"
        payloadBytes >= 8 -> "FULL_DESCRIBED_LAYOUT"
        else -> "PARTIAL_UNVERIFIED"
    }
}

object CaptBasicStatusParser {
    const val GET_BASIC_STATUS = 0xE0A0

    fun parse(frame: ByteArray): CaptBasicStatus {
        val payload = CaptFrame.parseReply(GET_BASIC_STATUS, frame)
        // A valid six-byte CAPT frame may be a short command result. Preserve its
        // bytes without claiming the full BasicStatus layout/meaning is established.
        require(payload.size >= 2) { "GetBasicStatus payload too short" }
        val hasBufferLevel = payload.size >= 6
        return CaptBasicStatus(
            statusByte = payload[0].toInt() and 0xff,
            secondaryByte = payload[1].toInt() and 0xff,
            bufferLowByte = if (hasBufferLevel) payload[4].toInt() and 0xff else null,
            bufferHighByte = if (hasBufferLevel) payload[5].toInt() and 0xff else null,
            payloadBytes = payload.size
        )
    }
}
