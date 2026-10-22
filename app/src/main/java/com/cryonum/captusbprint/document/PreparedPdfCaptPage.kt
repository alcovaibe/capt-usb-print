package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.CaptPageProtocol
import com.cryonum.captusbprint.core.CaptTestPage
import java.util.concurrent.CancellationException

/**
 * One complete, privately owned, bounded A4 portrait PDF page ALREADY compressed
 * offline. No live document URI/bitmap or USB session is held here.
 *
 * Bytes are copied on capture and on each emitted frame; callers cannot mutate
 * the owned HISCOA strips. A future printer engine must still validate hardware
 * state, own the exclusive USB queue, and confirm real CAPT page counters.
 */
class PreparedPdfCaptPage private constructor(
    private val storedBands: List<ByteArray>,
    override val compressedBytes: Int,
    val maximumBandBytes: Int
) : PreparedCaptPage {
    override val bandCount: Int get() = storedBands.size

    override fun descriptorFrame(): ByteArray = CaptPageProtocol.beginA4TestPage()

    /** Counts/framing use an explicit printer-validated chunk limit, not a
     * guessed transfer size. Does not claim a device or send any data.
     */
    override fun forEachVideoFrame(
        chunkLimit: Int,
        cancelled: () -> Boolean,
        consumer: (bandIndex: Int, frame: ByteArray) -> Unit
    ) {
        require(chunkLimit in 64..0xFF00) { "Printer-validated chunk size required" }
        storedBands.forEachIndexed { index, compressed ->
            if (cancelled() || Thread.currentThread().isInterrupted) {
                throw CancellationException("Prepared CAPT page consumption cancelled")
            }
            // Existing Stage B packetization; allocate only the current
            // compressed band's frames, not the complete video stream.
            CaptPageProtocol.videoData(compressed, chunkLimit).forEach { frame ->
                if (cancelled() || Thread.currentThread().isInterrupted) {
                    throw CancellationException("Prepared CAPT page consumption cancelled")
                }
                consumer(index, frame)
            }
        }
    }

    companion object {
        const val MAX_BUFFERED_PAGE_BYTES = 4 * 1024 * 1024

        internal fun fromOwned(bands: List<ByteArray>, summary: OfflineHiscoaPageSummary):
            PreparedPdfCaptPage {
            check(bands.size == OfflinePdfHiscoaPreparation.EXPECTED_BANDS &&
                summary.bandCount == bands.size)
            val total = bands.sumOf { it.size.toLong() }
            check(total > 0 && total == summary.compressedBytes &&
                total <= MAX_BUFFERED_PAGE_BYTES)
            check(bands.all { it.isNotEmpty() && it.size <= 256 * 1024 })
            check(bands.maxOf { it.size } == summary.maxCompressedBandBytes)
            return PreparedPdfCaptPage(bands.map { it.copyOf() }, total.toInt(),
                summary.maxCompressedBandBytes)
        }
    }
}

/** Offline bridge between Stage C PDF strips and the existing Stage B packet
 * format. The only active memory retention is a single compressed A4 page,
 * bounded to the Stage B 4 MiB envelope. The 600-dpi ARGB page never exists.
 */
object PreparedPdfCaptPageBuilder {
    fun prepare(
        produceBands: (consumer: (startLine: Int, packedBand: ByteArray) -> Unit) -> Unit,
        encode: (pixels: ByteArray, lineBytes: Int, lines: Int, lastBand: Boolean) -> ByteArray,
        cancelled: () -> Boolean = { false }
    ): PreparedPdfCaptPage {
        val bands = ArrayList<ByteArray>(OfflinePdfHiscoaPreparation.EXPECTED_BANDS)
        var retained = 0L
        val summary = OfflinePdfHiscoaPreparation.prepare(
            produceBands = produceBands,
            encode = encode,
            cancelled = cancelled,
            onCompressedBand = { _, bytes ->
                val next = retained + bytes.size
                check(next <= PreparedPdfCaptPage.MAX_BUFFERED_PAGE_BYTES) {
                    "Compressed document page exceeds Stage B buffered budget"
                }
                // Never keep an encoder-owned mutable output buffer by reference.
                bands.add(bytes.copyOf())
                retained = next
            }
        )
        check(retained == summary.compressedBytes) { "Prepared page total mismatch" }
        return PreparedPdfCaptPage.fromOwned(bands, summary)
    }
}

