package com.cryonum.captusbprint.core

/**
 * Read-only GetPrinterInfo fields used to plan bounded transfers.
 *
 * Field offsets follow the LBP2900 path in ValdikSS/captdriver at
 * 892f320c019974a5b737e000fcc2c8582264a131. This parser independently checks
 * the actual payload length and does not assume the upstream helper's reported
 * reply size is a payload length.
 */
data class CaptPrinterInfo(
    val blockSize: Int,
    val bufferSize: Int,
    val payloadBytes: Int
) {
    /** Conservative USB payload cap used by the upstream transfer path. */
    val safeChunkBytes: Int?
        get() = if (blockSize in 1..0xffff) minOf(blockSize, MAX_USB_CHUNK_BYTES) else null

    val transferParametersUsable: Boolean
        get() = safeChunkBytes != null && bufferSize in 1..0xffff

    companion object {
        const val MAX_USB_CHUNK_BYTES = 0xFF00
    }
}

object CaptPrinterInfoParser {
    const val GET_PRINTER_INFO = 0xA1A1
    const val MIN_PAYLOAD_BYTES = 10

    /**
     * LBP2900 offsets: Blk = payload[6..7] LE; Buf = payload[8..9] LE.
     * Keep values raw if unexpected; callers must not use them for transfer
     * sizing unless transferParametersUsable is true.
     */
    fun parse(frame: ByteArray): CaptPrinterInfo {
        val payload = CaptFrame.parseReply(GET_PRINTER_INFO, frame)
        require(payload.size >= MIN_PAYLOAD_BYTES) { "GetPrinterInfo payload too short" }
        return CaptPrinterInfo(
            blockSize = readLe16(payload, 6),
            bufferSize = readLe16(payload, 8),
            payloadBytes = payload.size
        )
    }

    private fun readLe16(payload: ByteArray, offset: Int): Int =
        (payload[offset].toInt() and 0xff) or
            ((payload[offset + 1].toInt() and 0xff) shl 8)
}
