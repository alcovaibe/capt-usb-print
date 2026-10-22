package com.cryonum.captusbprint.core

data class CaptExtendedStatus(
    val basicStatusByte: Int,
    val secondaryByte: Int,
    val bufferLowByte: Int,
    val bufferHighByte: Int,
    val auxiliaryByte: Int,
    val engineCommunicationByte: Int,
    val paperAvailabilityByte: Int,
    val engineFlags: Int,
    val pageDecoding: Int?,
    val pagePrinting: Int?,
    val pageOut: Int?,
    val pageCompleted: Int?,
    val pageReceived: Int?
) {
    val bufferSlots: Int get() = bufferLowByte and 0x0f
}

object CaptExtendedStatusParser {
    const val GET_EXTENDED_STATUS = 0xA0A8

    fun parse(frame: ByteArray): CaptExtendedStatus {
        val payload = CaptFrame.parseReply(GET_EXTENDED_STATUS, frame)
        require(payload.size >= 14) { "GetExtendedStatus payload too short" }
        fun word(offset: Int): Int =
            (payload[offset].toInt() and 0xff) or
                ((payload[offset + 1].toInt() and 0xff) shl 8)
        val hasPageCounters = payload.size >= 22
        return CaptExtendedStatus(
            basicStatusByte = payload[0].toInt() and 0xff,
            secondaryByte = payload[1].toInt() and 0xff,
            bufferLowByte = payload[4].toInt() and 0xff,
            bufferHighByte = payload[5].toInt() and 0xff,
            auxiliaryByte = payload[8].toInt() and 0xff,
            engineCommunicationByte = payload[9].toInt() and 0xff,
            paperAvailabilityByte = payload[10].toInt() and 0xff,
            engineFlags = word(12),
            pageDecoding = if (hasPageCounters) word(14) else null,
            pagePrinting = if (hasPageCounters) word(16) else null,
            pageOut = if (hasPageCounters) word(18) else null,
            pageCompleted = if (hasPageCounters) word(20) else null,
            pageReceived = if (payload.size >= 36) word(34) else null
        )
    }
}
