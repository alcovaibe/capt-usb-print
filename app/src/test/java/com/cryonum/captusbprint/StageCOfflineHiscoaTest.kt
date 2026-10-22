package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.document.OfflinePdfHiscoaPreparation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class StageCOfflineHiscoaTest {
    private fun completePage(accept: (Int, ByteArray) -> Unit) {
        var y = 0
        while (y < CaptTestPage.HEIGHT) {
            val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - y)
            accept(y, ByteArray(lines * CaptTestPage.LINE_BYTES))
            y += lines
        }
    }

    @Test fun consumesExactlyOneA4PageAndMarksOnlyLastBandFinal() {
        var lastFlags = 0
        var lastSize = 0
        var sawNonFinalLast = false
        val result = OfflinePdfHiscoaPreparation.prepare(
            produceBands = ::completePage,
            encode = { packed, lineBytes, lines, last ->
                assertEquals(CaptTestPage.LINE_BYTES, lineBytes)
                assertEquals(lines * lineBytes, packed.size)
                if (last) {
                    lastFlags++
                    lastSize = lines
                } else if (lines != CaptTestPage.BAND_LINES) {
                    sawNonFinalLast = true
                }
                byteArrayOf(1, 2, 3)
            }
        )
        assertEquals(110, result.bandCount)
        assertEquals(OfflinePdfHiscoaPreparation.EXPECTED_RASTER_BYTES, result.rasterBytes)
        assertEquals(330L, result.compressedBytes)
        assertEquals(3, result.maxCompressedBandBytes)
        assertEquals(1, lastFlags)
        assertEquals(40, lastSize)
        assertFalse(sawNonFinalLast)
    }

    @Test fun refusesOmittedDuplicatedAndMalformedBandsBeforeCompletion() {
        assertThrows(IllegalStateException::class.java) {
            OfflinePdfHiscoaPreparation.prepare(
                produceBands = { accept -> accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES)) },
                encode = { _, _, _, _ -> byteArrayOf(1) }
            )
        }
        var encoded = 0
        assertThrows(IllegalStateException::class.java) {
            OfflinePdfHiscoaPreparation.prepare(
                produceBands = { accept ->
                    accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES))
                    accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES))
                },
                encode = { _, _, _, _ -> encoded++; byteArrayOf(1) }
            )
        }
        assertEquals(1, encoded)
        assertThrows(IllegalStateException::class.java) {
            OfflinePdfHiscoaPreparation.prepare(
                produceBands = { accept -> accept(0, ByteArray(1)) },
                encode = { _, _, _, _ -> error("Must fail before encoding") }
            )
        }
    }

    @Test fun rejectsEmptyAndOversizedCodecResult() {
        assertThrows(IllegalStateException::class.java) {
            OfflinePdfHiscoaPreparation.prepare(
                produceBands = { accept -> accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES)) },
                encode = { _, _, _, _ -> byteArrayOf() }
            )
        }
        assertThrows(IllegalStateException::class.java) {
            OfflinePdfHiscoaPreparation.prepare(
                produceBands = { accept -> accept(0, ByteArray(64 * CaptTestPage.LINE_BYTES)) },
                encode = { packed, _, _, _ -> ByteArray(packed.size * 2 + 65) }
            )
        }
    }

    @Test fun cancellationStopsBeforeAnotherBandAndDoesNotRetry() {
        var shouldCancel = false
        var encoded = 0
        assertThrows(CancellationException::class.java) {
            OfflinePdfHiscoaPreparation.prepare(
                produceBands = ::completePage,
                encode = { _, _, _, _ ->
                    encoded++
                    shouldCancel = true
                    byteArrayOf(1)
                },
                cancelled = { shouldCancel }
            )
        }
        assertEquals(1, encoded)
    }

    @Test fun propagatesCodecFailureWithoutSwallowingOrReplaying() {
        var attempts = 0
        assertThrows(IllegalArgumentException::class.java) {
            OfflinePdfHiscoaPreparation.prepare(
                produceBands = ::completePage,
                encode = { _, _, _, _ ->
                    attempts++
                    throw IllegalArgumentException("Simulated JNI failure")
                }
            )
        }
        assertEquals(1, attempts)
    }
}
