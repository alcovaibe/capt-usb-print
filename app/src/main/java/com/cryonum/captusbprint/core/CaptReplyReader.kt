package com.cryonum.captusbprint.core

/** Exact CAPT framing over an already owned channel. Preserves the existing 6+remainder
 * exchange; no speculative packet-size change, flushing, resynchronization or query retry.
 */
class CaptReplyReader(
    private val channel: BulkChannel,
    private val trace: CaptProtocolTrace,
    private val now: () -> Long,
    private val cancelled: () -> Boolean
) {
    fun read(expectedCommand: Int): ByteArray {
        val frame = ByteArray(1024)
        var received = 0
        var expectedLength: Int? = null
        fun fail(reason: String): Nothing {
            val raw = frame.copyOf(received)
            trace.event("READ_FAILED", mapOf("reason" to reason, "expectedCommand" to expectedCommand,
                "receivedBytes" to received, "expectedBytes" to expectedLength,
                "partialFrameHex" to CaptProtocolTrace.hex(raw)))
            throw CaptReplyReadFailure(reason, raw, expectedLength)
        }
        fun readTo(target: Int, budgetMs: Long) {
            val deadline = now() + budgetMs
            while (received < target) {
                if (cancelled() || Thread.currentThread().isInterrupted) fail("Cancelled while reading CAPT reply")
                val remaining = deadline - now()
                if (remaining <= 0) fail("Timed out reading CAPT reply")
                val requested = target - received
                val offset = received
                val count = try {
                    channel.read(frame, offset, requested, minOf(2000L, remaining).toInt())
                } catch (error: Exception) {
                    trace.event("USB_READ_EXCEPTION", mapOf("type" to error.javaClass.simpleName,
                        "requestedBytes" to requested, "offset" to offset))
                    fail("USB read failed")
                }
                trace.transfer("IN", expectedCommand, offset, requested, count,
                    if (count in 1..requested) frame.copyOfRange(offset, offset + count) else null)
                if (count !in 1..requested) fail("Incomplete CAPT reply")
                received += count
            }
        }
        readTo(6, 5000)
        val command = (frame[0].toInt() and 255) or ((frame[1].toInt() and 255) shl 8)
        if (command != expectedCommand) fail("Unexpected CAPT response command")
        val length = try { CaptFrame.preferredReplyLength(frame, frame.size) }
        catch (_: IllegalArgumentException) { fail("Invalid CAPT response length") }
        expectedLength = length
        readTo(length, 6000)
        return frame.copyOf(received).also {
            try { CaptFrame.parseReply(expectedCommand, it) }
            catch (_: IllegalArgumentException) { fail("Invalid CAPT response frame") }
        }
    }
}

class CaptReplyReadFailure(message: String, val partialFrame: ByteArray, val expectedBytes: Int?) :
    IllegalStateException(message)
