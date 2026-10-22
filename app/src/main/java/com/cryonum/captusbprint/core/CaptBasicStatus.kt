package com.cryonum.captusbprint.core

/** Read-only status bytes parsed from a bounded CAPT GetBasicStatus reply. */
data class CaptBasicStatus(
    val statusByte: Int,
    val secondaryByte: Int,
    val bufferLowByte: Int,
    val bufferHighByte: Int
)

object CaptBasicStatusParser {
    const val GET_BASIC_STATUS = 0xE0A0

    fun parse(frame: ByteArray): CaptBasicStatus {
        val payload = CaptFrame.parseReply(GET_BASIC_STATUS, frame)
        require(payload.size >= 6) { "GetBasicStatus payload too short" }
        return CaptBasicStatus(
            statusByte = payload[0].toInt() and 0xff,
            secondaryByte = payload[1].toInt() and 0xff,
            bufferLowByte = payload[4].toInt() and 0xff,
            bufferHighByte = payload[5].toInt() and 0xff
        )
    }
}
