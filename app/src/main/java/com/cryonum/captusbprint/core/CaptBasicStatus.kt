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

/** Named interpretations of known GetBasicStatus bits; unknown bits remain visible. */
object CaptStatusFlagDecoder {
    fun basicFlags(value: Int): List<String> = decode(
        value, 0xDF, listOf(
            0x01 to "PRINTER_FREE",
            0x02 to "NOT_READY",
            0x04 to "COMMAND_BUSY",
            0x08 to "IM_DATA_BUSY",
            0x10 to "OFFLINE",
            0x40 to "UNIT_FREE",
            0x80 to "ERROR_BIT"
        )
    )

    fun secondaryFlags(value: Int): List<String> = decode(
        value, 0x03, listOf(
            0x01 to "EXTENDED_STATUS_CHANGED",
            0x02 to "NEED_INPUT_STATUS"
        )
    )

    private fun decode(value: Int, knownMask: Int, bits: List<Pair<Int, String>>): List<String> {
        val normalized = value and 0xff
        val result = bits.filter { (bit, _) -> normalized and bit != 0 }
            .map { it.second }.toMutableList()
        val unknown = normalized and knownMask.inv() and 0xff
        if (unknown != 0) result += "UNKNOWN_0x" + "%02X".format(unknown)
        return result.ifEmpty { listOf("NONE") }
    }
}

/** Bounded fields from the read-only GetExtendedStatus response. */
data class CaptExtendedStatus(
    val basicStatusByte: Int,
    val secondaryByte: Int,
    val bufferLowByte: Int,
    val bufferHighByte: Int,
    val auxiliaryByte: Int,
    val engineCommunicationByte: Int,
    val paperAvailabilityByte: Int,
    val engineFlags: Int
) {
    val bufferSlots: Int get() = bufferLowByte and 0x0f
}

object CaptExtendedStatusParser {
    const val GET_EXTENDED_STATUS = 0xA0A8

    fun parse(frame: ByteArray): CaptExtendedStatus {
        val payload = CaptFrame.parseReply(GET_EXTENDED_STATUS, frame)
        require(payload.size >= 14) { "GetExtendedStatus payload too short" }
        return CaptExtendedStatus(
            basicStatusByte = payload[0].toInt() and 0xff,
            secondaryByte = payload[1].toInt() and 0xff,
            bufferLowByte = payload[4].toInt() and 0xff,
            bufferHighByte = payload[5].toInt() and 0xff,
            auxiliaryByte = payload[8].toInt() and 0xff,
            engineCommunicationByte = payload[9].toInt() and 0xff,
            paperAvailabilityByte = payload[10].toInt() and 0xff,
            engineFlags = (payload[12].toInt() and 0xff) or
                ((payload[13].toInt() and 0xff) shl 8)
        )
    }
}
