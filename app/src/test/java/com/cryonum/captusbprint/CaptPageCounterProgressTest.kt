package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptExtendedStatusParser
import com.cryonum.captusbprint.core.CaptPageCounterProgress
import org.junit.Assert.*
import org.junit.Test

class CaptPageCounterProgressTest {
    private fun status(count: Int) = CaptExtendedStatus(
        basicStatusByte = 0x01, secondaryByte = 0x8A,
        bufferLowByte = 15, bufferHighByte = 0, auxiliaryByte = 0,
        engineCommunicationByte = 0, paperAvailabilityByte = 0x80, engineFlags = 0,
        pageDecoding = count, pagePrinting = count, pageOut = count,
        pageCompleted = count, pageReceived = count
    )

    @Test fun idleBeforeFirstJobAndAfterOneCompletedPageAreBothEligible() {
        assertTrue(CaptPageCounterProgress.hasNoOutstandingPage(status(0)))
        assertTrue(CaptPageCounterProgress.hasNoOutstandingPage(status(1)))
        assertTrue(CaptPageCounterProgress.hasNoOutstandingPage(status(17)))
    }

    @Test fun exactHardwareStatusAfterFirstSheetIsNotAnOutstandingPage() {
        // CAPT GetExtendedStatus returned by owner's Canon LBP2900, 2026-10-22.
        val raw = ("A8 A0 38 00 01 8A 00 00 0F 00 00 00 00 00 80 00 " +
            "00 00 01 00 01 00 01 00 01 00 00 00 57 00 00 00 " +
            "01 00 00 00 00 02 01 00 00 00 00 00 00 00 00 00 " +
            "01 00 00 00 00 00 00 00")
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val parsed = CaptExtendedStatusParser.parse(raw)
        assertEquals(15, parsed.bufferSlots)
        assertEquals(0x80, parsed.paperAvailabilityByte)
        assertEquals(0, parsed.engineFlags)
        assertEquals(1, parsed.pageCompleted)
        assertEquals(1, parsed.pageReceived)
        assertTrue(CaptPageCounterProgress.hasNoOutstandingPage(parsed))
        val initial = CaptPageCounterProgress.snapshot(parsed)
        assertFalse(CaptPageCounterProgress.nextPageDecoded(initial, parsed))
        assertFalse(CaptPageCounterProgress.nextPageCompleted(initial, parsed))
    }

    @Test fun oldCompletedPageCannotFalselyConfirmAnotherPrint() {
        val initial = CaptPageCounterProgress.snapshot(status(1))
        assertFalse(CaptPageCounterProgress.nextPageDecoded(initial, status(1)))
        assertFalse(CaptPageCounterProgress.nextPageCompleted(initial, status(1)))
        val decodingOnly = status(1).copy(pageDecoding = 2, pageReceived = 2)
        assertTrue(CaptPageCounterProgress.nextPageDecoded(initial, decodingOnly))
        assertFalse(CaptPageCounterProgress.nextPageCompleted(initial, decodingOnly))
        val shippedOnly = status(1).copy(pageOut = 2)
        assertFalse(CaptPageCounterProgress.nextPageCompleted(initial, shippedOnly))
        assertTrue(CaptPageCounterProgress.nextPageCompleted(initial, status(2)))
    }

    @Test fun outstandingOrAmbiguousStageCountsFailClosed() {
        assertFalse(CaptPageCounterProgress.hasNoOutstandingPage(
            status(1).copy(pageDecoding = 2)))
        assertFalse(CaptPageCounterProgress.hasNoOutstandingPage(
            status(1).copy(pageReceived = 2)))
        assertFalse(CaptPageCounterProgress.hasNoOutstandingPage(
            status(1).copy(auxiliaryByte = 0x04)))
        assertThrows(IllegalArgumentException::class.java) {
            CaptPageCounterProgress.hasNoOutstandingPage(status(1).copy(pagePrinting = null))
        }
    }

    @Test fun twoPageCheckpointsIgnorePriorJobAndUnfinishedSecondSheet() {
        val initial = CaptPageCounterProgress.snapshot(status(1))
        assertFalse(CaptPageCounterProgress.pagesCompletedSince(initial, status(1), 1))
        assertTrue(CaptPageCounterProgress.pagesCompletedSince(initial, status(2), 1))
        assertFalse(CaptPageCounterProgress.pagesCompletedSince(initial, status(2), 2))
        assertFalse(CaptPageCounterProgress.pagesCompletedSince(initial,
            status(1).copy(pageOut = 3, pageCompleted = 2), 2))
        assertTrue(CaptPageCounterProgress.pagesCompletedSince(initial, status(3), 2))
        assertFalse(CaptPageCounterProgress.pagesCompletedSince(initial,
            status(3).copy(auxiliaryByte = 0x04), 2))
        assertThrows(IllegalArgumentException::class.java) {
            CaptPageCounterProgress.pagesCompletedSince(initial, status(3), 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptPageCounterProgress.pagesCompletedSince(
                CaptPageCounterProgress.snapshot(status(0xffff)), status(0), 1)
        }
    }

    @Test fun priorZeroCountAlsoRequiresNewCounterIncrease() {
        val initial = CaptPageCounterProgress.snapshot(status(0))
        assertFalse(CaptPageCounterProgress.nextPageCompleted(initial, status(0)))
        assertTrue(CaptPageCounterProgress.nextPageDecoded(initial, status(1)))
        assertTrue(CaptPageCounterProgress.nextPageCompleted(initial, status(1)))
    }
}
