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
     * Upstream normally obtains six bytes before finishing reply retrieval:
     * the 4-byte frame header plus two body bytes. Never infer device success
     * from a frame merely passing structural checks.
     *
     * Some upstream replies use a variant BCD length; that is intentionally
     * unsupported until a real device trace validates the variant.
     */
    fun parseReply(expectedCommand: Int, bytes: ByteArray): ByteArray {
        require(expectedCommand in 0..0xffff)
        require(bytes.size in 6..MAX_FRAME_BYTES) { "Reply size invalid" }
        val command = u8(bytes[0]) or (u8(bytes[1]) shl 8)
        val length = u8(bytes[2]) or (u8(bytes[3]) shl 8)
        require(command == expectedCommand) { "Unexpected reply command" }
        require(length == bytes.size) { "Incomplete or invalid CAPT reply" }
        // Includes the two as-yet-uninterpreted protocol response bytes.
        return bytes.copyOfRange(HEADER_BYTES, bytes.size)
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
