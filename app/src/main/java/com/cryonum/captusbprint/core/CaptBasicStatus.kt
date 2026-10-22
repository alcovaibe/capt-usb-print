package com.cryonum.captusbprint.core

/** Read-only status bytes parsed from a bounded CAPT GetBasicStatus reply. */
data class CaptBasicStatus(
    val statusByte: Int,
    val secondaryByte: Int,
    /** Older/minimal valid replies may omit the two buffer-level bytes. */
    val bufferLowByte: Int?,
    val bufferHighByte: Int?
)

object CaptBasicStatusParser {
    const val GET_BASIC_STATUS = 0xE0A0

    fun parse(frame: ByteArray): CaptBasicStatus {
        val payload = CaptFrame.parseReply(GET_BASIC_STATUS, frame)
        // Upstream CAPT framing accepts a 6-byte total reply: header plus two
        // basic status bytes. Buffer level is available only in longer replies.
        require(payload.size >= 2) { "GetBasicStatus payload too short" }
        val hasBufferLevel = payload.size >= 6
        return CaptBasicStatus(
            statusByte = payload[0].toInt() and 0xff,
            secondaryByte = payload[1].toInt() and 0xff,
            bufferLowByte = if (hasBufferLevel) payload[4].toInt() and 0xff else null,
            bufferHighByte = if (hasBufferLevel) payload[5].toInt() and 0xff else null
        )
    }
}
