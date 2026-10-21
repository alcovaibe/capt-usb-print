package com.cryonum.captusbprint.engine

/**
 * Pure endpoint selection. These are reported descriptors, not evidence that a
 * connected printer understands CAPT. No UsbDeviceConnection is opened here.
 */
data class EndpointFact(val address: Int, val transferType: Int) {
    val isIn: Boolean get() = address and 0x80 != 0
    val isBulk: Boolean get() = transferType == 2
}
data class InterfaceFact(
    val id: Int,
    val alternateSetting: Int,
    val endpoints: List<EndpointFact>
)
sealed interface LinkSelection {
    data class Unique(val interfaceId: Int, val alternateSetting: Int,
                      val inAddress: Int, val outAddress: Int) : LinkSelection
    data object None : LinkSelection
    data object Ambiguous : LinkSelection
}
object UsbLinkSelector {
    fun select(interfaces: List<InterfaceFact>): LinkSelection {
        if (interfaces.size > 16) return LinkSelection.Ambiguous
        val candidates = interfaces.mapNotNull { iface ->
            if (iface.endpoints.size > 32 || iface.endpoints.any { it.address !in 0..255 }) {
                return LinkSelection.Ambiguous
            }
            val bulk = iface.endpoints.filter { it.isBulk }
            val ins = bulk.filter { it.isIn }
            val outs = bulk.filterNot { it.isIn }
            if (ins.isEmpty() || outs.isEmpty()) null
            else if (ins.size != 1 || outs.size != 1) return LinkSelection.Ambiguous
            else LinkSelection.Unique(iface.id, iface.alternateSetting,
                ins.single().address, outs.single().address)
        }
        return when (candidates.size) {
            0 -> LinkSelection.None
            1 -> candidates.single()
            else -> LinkSelection.Ambiguous
        }
    }
}

/** One bounded USB write call; only positive returned counts are known to be accepted. */
fun interface BulkSink {
    fun write(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
}
sealed interface BulkOutcome {
    data class Accepted(val bytes: Int) : BulkOutcome
    // Bytes already accepted must NOT be resent as a new command; caller must
    // mark the job outcome unknown after disconnect, timeout, or cancellation.
    data class Uncertain(val knownAcceptedBytes: Int, val reason: Reason) : BulkOutcome
    enum class Reason { CANCELLED, TIMEOUT_OR_IO_ERROR, ZERO_PROGRESS, INVALID_COUNT }
}
class BoundedBulkWriter(
    private val sink: BulkSink,
    private val chunkSize: Int = 16 * 1024,
    private val timeoutMs: Int = 3000
) {
    init {
        require(chunkSize in 1..64 * 1024)
        require(timeoutMs in 1..30_000)
    }
    fun send(data: ByteArray, cancelled: () -> Boolean = { false }): BulkOutcome {
        require(data.size <= 1024 * 1024) { "Use bounded CAPT packets, not an entire page." }
        var offset = 0
        while (offset < data.size) {
            if (cancelled()) return BulkOutcome.Uncertain(offset, BulkOutcome.Reason.CANCELLED)
            val remaining = minOf(chunkSize, data.size - offset)
            val result = try { sink.write(data, offset, remaining, timeoutMs) }
            catch (_: Exception) { return BulkOutcome.Uncertain(offset, BulkOutcome.Reason.TIMEOUT_OR_IO_ERROR) }
            when {
                result < 0 -> return BulkOutcome.Uncertain(offset, BulkOutcome.Reason.TIMEOUT_OR_IO_ERROR)
                result > remaining -> return BulkOutcome.Uncertain(offset, BulkOutcome.Reason.INVALID_COUNT)
                // Even zero can represent a timeout with uncertain device effects.
                // Never silently repeat an already sent part of a command.
                result == 0 -> return BulkOutcome.Uncertain(offset, BulkOutcome.Reason.ZERO_PROGRESS)
                else -> offset += result
            }
        }
        return BulkOutcome.Accepted(offset)
    }
}
