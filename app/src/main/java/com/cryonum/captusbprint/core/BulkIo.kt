package com.cryonum.captusbprint.core

/**
 * A single claimed USB bulk interface must be owned by exactly one executor.
 * This primitive never retries an ambiguous write from its beginning.
 */
interface BulkChannel {
    /** Must return a count in [1, length] or a nonpositive failure code. */
    fun write(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int
    fun read(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int
}

/** A partial write can have affected the physical printer. Do not replay it. */
class TransferUncertainException(val acceptedBytes: Int, message: String) :
    IllegalStateException(message)

class BulkIo(
    private val channel: BulkChannel,
    private val nowNanos: () -> Long = System::nanoTime,
    private val cancelled: () -> Boolean = { false }
) {
    companion object {
        const val MAX_USB_CHUNK = 4096
        const val MAX_TRANSACTION_BYTES = 65535
    }

    /**
     * Caller owns the interface. Every bulk transfer has a finite deadline;
     * no operation runs on the Android main thread. Failed writes report the
     * accepted byte count, not an invented "nothing was sent" outcome.
     */
    fun writeExactly(
        data: ByteArray,
        timeoutMillis: Int,
        perCallTimeoutMillis: Int = 1000
    ): Int {
        require(data.isNotEmpty() && data.size <= MAX_TRANSACTION_BYTES)
        require(timeoutMillis in 1..120_000)
        require(perCallTimeoutMillis in 1..10_000)
        val start = nowNanos()
        var accepted = 0
        while (accepted < data.size) {
            if (cancelled()) throw TransferUncertainException(accepted, "Cancelled during write")
            val timeLeft = remainingMillis(start, timeoutMillis)
            if (timeLeft <= 0) throw TransferUncertainException(accepted, "Write deadline exceeded")
            val count = try {
                channel.write(
                    data, accepted, minOf(MAX_USB_CHUNK, data.size - accepted),
                    minOf(perCallTimeoutMillis, timeLeft)
                )
            } catch (error: Exception) {
                throw TransferUncertainException(accepted, "USB write failed: " + error.javaClass.simpleName)
            }
            if (count !in 1..minOf(MAX_USB_CHUNK, data.size - accepted)) {
                throw TransferUncertainException(accepted, "Invalid/failed USB write count")
            }
            accepted += count
        }
        return accepted
    }

    /** Reads exactly the *expected bounded number* of bytes or fails closed. */
    fun readExactly(
        length: Int,
        timeoutMillis: Int,
        perCallTimeoutMillis: Int = 1000
    ): ByteArray {
        require(length in 1..MAX_TRANSACTION_BYTES)
        require(timeoutMillis in 1..120_000)
        require(perCallTimeoutMillis in 1..10_000)
        val data = ByteArray(length)
        val start = nowNanos()
        var received = 0
        while (received < length) {
            if (cancelled()) throw IllegalStateException("Cancelled during read")
            val timeLeft = remainingMillis(start, timeoutMillis)
            if (timeLeft <= 0) throw IllegalStateException("Read deadline exceeded")
            val requested = minOf(MAX_USB_CHUNK, length - received)
            val count = try {
                channel.read(data, received, requested, minOf(perCallTimeoutMillis, timeLeft))
            } catch (error: Exception) {
                throw IllegalStateException("USB read failed: " + error.javaClass.simpleName)
            }
            if (count !in 1..requested) throw IllegalStateException("Incomplete USB read")
            received += count
        }
        return data
    }

    private fun remainingMillis(start: Long, timeoutMillis: Int): Int {
        val elapsedNanos = (nowNanos() - start).coerceAtLeast(0L)
        val totalNanos = timeoutMillis.toLong() * 1_000_000L
        if (elapsedNanos >= totalNanos) return 0
        return ((totalNanos - elapsedNanos + 999_999L) / 1_000_000L).toInt()
    }
}
