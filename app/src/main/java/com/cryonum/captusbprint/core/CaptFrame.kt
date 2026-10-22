package com.cryonum.captusbprint.core

/**
 * Pure CAPT wire framing, with no USB access or device side effects.
 *
 * Format verified against ValdikSS/captdriver src/capt-command.c and
 * src/capt-command.h at commit 892f320c019974a5b737e000fcc2c8582264a131.
 * This is a separately written bounded implementation of the documented
 * framing format, not a port of its C source or a complete CAPT driver.
 */
object CaptFrame {
    const val MAX_FRAME_BYTES = 65535
    const val HEADER_BYTES = 4
    const val MAX_PAYLOAD_BYTES = MAX_FRAME_BYTES - HEADER_BYTES
    const val MULTI_COMMAND = 0xD0A9

    fun encode(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(command in 0..0xffff) { "Invalid command" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "CAPT frame too large" }
        val size = HEADER_BYTES + payload.size
        return ByteArray(size).also {
            it[0] = command.toByte()
            it[1] = (command ushr 8).toByte()
            it[2] = size.toByte()
            it[3] = (size ushr 8).toByte()
            payload.copyInto(it, HEADER_BYTES)
        }
    }

    /**
     * Parse a complete bounded reply. Some CAPT responses encode the length
     * as four decimal BCD nibbles instead of a little-endian integer.
     * Accept either only when it matches the exact received byte count.
     */
    fun parseReply(expectedCommand: Int, bytes: ByteArray): ByteArray {
        require(expectedCommand in 0..0xffff)
        require(bytes.size in 6..MAX_FRAME_BYTES) { "Reply size invalid" }
        val command = u8(bytes[0]) or (u8(bytes[1]) shl 8)
        require(command == expectedCommand) { "Unexpected reply command" }
        require(headerLengthMatches(bytes, bytes.size)) { "Incomplete or invalid CAPT reply" }
        // Includes the two as-yet-uninterpreted protocol response bytes.
        return bytes.copyOfRange(HEADER_BYTES, bytes.size)
    }

    /** Plausible bounded total lengths from the LE and BCD header interpretations. */
    fun lengthCandidates(header: ByteArray, maxSize: Int = MAX_FRAME_BYTES): List<Int> {
        require(header.size >= HEADER_BYTES) { "CAPT header too short" }
        require(maxSize in 6..MAX_FRAME_BYTES) { "Invalid maximum reply size" }
        val lo = u8(header[2])
        val hi = u8(header[3])
        val littleEndian = lo or (hi shl 8)
        val bcd = decodeBcdLength(lo, hi)
        return listOfNotNull(littleEndian, bcd)
            .distinct()
            .filter { it in 6..maxSize }
    }

    fun headerLengthMatches(header: ByteArray, actualSize: Int): Boolean {
        if (header.size < HEADER_BYTES || actualSize < HEADER_BYTES) return false
        val lo = u8(header[2])
        val hi = u8(header[3])
        val littleEndian = lo or (hi shl 8)
        return littleEndian == actualSize || decodeBcdLength(lo, hi) == actualSize
    }

    private fun decodeBcdLength(lo: Int, hi: Int): Int? {
        val a = (hi ushr 4) and 0x0f
        val b = hi and 0x0f
        val c = (lo ushr 4) and 0x0f
        val d = lo and 0x0f
        if (a > 9 || b > 9 || c > 9 || d > 9) return null
        return a * 1000 + b * 100 + c * 10 + d
    }

    fun encodeMulti(children: List<Pair<Int, ByteArray>>): ByteArray {
        require(children.isNotEmpty()) { "Empty multi-command" }
        val encoded = children.map { encode(it.first, it.second) }
        val total = encoded.fold(0L) { n, child -> n + child.size }
        require(total <= MAX_PAYLOAD_BYTES) { "CAPT multi-command too large" }
        val body = ByteArray(total.toInt())
        var position = 0
        encoded.forEach {
            it.copyInto(body, position)
            position += it.size
        }
        return encode(MULTI_COMMAND, body)
    }

    private fun u8(value: Byte) = value.toInt() and 0xff
}
