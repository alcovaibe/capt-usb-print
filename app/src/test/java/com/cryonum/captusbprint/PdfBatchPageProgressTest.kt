package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptPageCounterProgress
import com.cryonum.captusbprint.core.PdfBatchPageProgress
import org.junit.Assert.*
import org.junit.Test

class PdfBatchPageProgressTest {
    private fun status(count: Int) = CaptExtendedStatus(
        1, 0, 15, 0, 0, 0, 0x80, 0, count, count, count, count, count)

    @Test fun thirdSheetRequiresExactlyTheThirdNewCounter() {
        val start = CaptPageCounterProgress.snapshot(status(0))
        assertFalse(PdfBatchPageProgress.decoded(start, status(2), 3))
        assertTrue(PdfBatchPageProgress.decoded(start, status(3), 3))
        assertFalse(PdfBatchPageProgress.decoded(start, status(4), 3))
        assertFalse(PdfBatchPageProgress.completed(start, status(2), 3))
        assertTrue(PdfBatchPageProgress.completed(start, status(3), 3))
        assertFalse(PdfBatchPageProgress.completed(start, status(4), 3))
    }

    @Test fun retainedHistoryAndIncompletePaperDeliveryNeverConfirmANewSheet() {
        val start = CaptPageCounterProgress.snapshot(status(2))
        assertFalse(PdfBatchPageProgress.completed(start, status(2), 1))
        assertTrue(PdfBatchPageProgress.completed(start, status(3), 1))
        assertFalse(PdfBatchPageProgress.completed(start, status(3).copy(pageReceived = 2), 1))
        assertFalse(PdfBatchPageProgress.completed(start, status(3).copy(pagePrinting = 2), 1))
        assertFalse(PdfBatchPageProgress.completed(start, status(3).copy(auxiliaryByte = 4), 1))
    }

    @Test fun unsupportedCountRolloverAndMissingCountersFailClosed() {
        val start = CaptPageCounterProgress.snapshot(status(0))
        for (page in listOf(0, 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                PdfBatchPageProgress.decoded(start, status(3), page)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfBatchPageProgress.completed(CaptPageCounterProgress.snapshot(status(65534)), status(1), 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfBatchPageProgress.completed(start, status(3).copy(pageCompleted = null), 3)
        }
    }
}
