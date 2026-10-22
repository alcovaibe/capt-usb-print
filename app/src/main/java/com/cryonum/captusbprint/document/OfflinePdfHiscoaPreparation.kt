package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.CaptTestPage
import java.util.concurrent.CancellationException

/**
 * Bounded, offline-only PDF -> 1bpp -> HISCOA one-page pipeline.
 *
 * The producer supplies one 64-row band at a time (last band is shorter).
 * Only the current source band and its compressed result are live inside this
 * function; compressed/document bytes are neither logged nor retained.
 * This deliberately has no USB, CAPT job, file-export or print dependency.
 */
data class OfflineHiscoaPageSummary(
    val bandCount: Int,
    val rasterBytes: Long,
    val compressedBytes: Long,
    val maxCompressedBandBytes: Int
)

object OfflinePdfHiscoaPreparation {
    const val EXPECTED_BANDS =
        (CaptTestPage.HEIGHT + CaptTestPage.BAND_LINES - 1) / CaptTestPage.BAND_LINES
    const val EXPECTED_RASTER_BYTES =
        1L * CaptTestPage.LINE_BYTES * CaptTestPage.HEIGHT
    // An additional per-page guard beyond the per-band 2x + 64 limit.
    const val MAX_COMPRESSED_PAGE_BYTES = 12L * 1024L * 1024L

    fun prepare(
        produceBands: (consumer: (startLine: Int, packedBand: ByteArray) -> Unit) -> Unit,
        encode: (pixels: ByteArray, lineBytes: Int, lines: Int, lastBand: Boolean) -> ByteArray,
        cancelled: () -> Boolean = { false }
    ): OfflineHiscoaPageSummary {
        fun guard() {
            if (cancelled() || Thread.currentThread().isInterrupted) {
                throw CancellationException("Offline document preparation cancelled")
            }
        }
        var expectedY = 0
        var count = 0
        var rawBytes = 0L
        var compressedBytes = 0L
        var maxCompressed = 0
        guard()
        produceBands { startY, packed ->
            guard()
            // No reordering, partial rows or duplicates; fail before native call.
            check(startY == expectedY && expectedY < CaptTestPage.HEIGHT) {
                "Nonsequential or extra document band"
            }
            val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - expectedY)
            val expectedBytes = CaptTestPage.LINE_BYTES * lines
            check(packed.size == expectedBytes) { "Unexpected 1bpp document band length" }
            val last = expectedY + lines == CaptTestPage.HEIGHT
            // Use the same JNI codec/last-band semantics as accepted Stage B.
            val compressed = encode(packed, CaptTestPage.LINE_BYTES, lines, last)
            check(compressed.isNotEmpty() && compressed.size <= packed.size * 2 + 64) {
                "HISCOA returned an invalid bounded result"
            }
            val nextTotal = compressedBytes + compressed.size
            check(nextTotal <= MAX_COMPRESSED_PAGE_BYTES) {
                "Offline compressed page exceeded its bounded budget"
            }
            rawBytes += packed.size
            compressedBytes = nextTotal
            maxCompressed = maxOf(maxCompressed, compressed.size)
            expectedY += lines
            count++
            guard()
            // Do not retain packed or compressed data between producer callbacks.
        }
        guard()
        check(expectedY == CaptTestPage.HEIGHT && count == EXPECTED_BANDS &&
            rawBytes == EXPECTED_RASTER_BYTES) {
            "Offline PDF page is incomplete"
        }
        return OfflineHiscoaPageSummary(count, rawBytes, compressedBytes, maxCompressed)
    }
}
