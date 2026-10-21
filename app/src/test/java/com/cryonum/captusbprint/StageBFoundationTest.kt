package com.cryonum.captusbprint

import com.cryonum.captusbprint.engine.*
import org.junit.Assert.*
import org.junit.Test

class StageBFoundationTest {
    private val printer = InterfaceFact(0, 0, listOf(
        EndpointFact(0x81, 2), EndpointFact(0x02, 2), EndpointFact(0x83, 3)
    ))
    @Test fun selectsExactBidirectionalBulkPair() {
        assertEquals(LinkSelection.Unique(0, 0, 0x81, 0x02), UsbLinkSelector.select(listOf(printer)))
    }
    @Test fun noBulkAndMultipleInterfacesAreNotGuessed() {
        assertEquals(LinkSelection.None, UsbLinkSelector.select(listOf(
            InterfaceFact(1, 0, listOf(EndpointFact(0x81, 3))))))
        assertEquals(LinkSelection.Ambiguous, UsbLinkSelector.select(listOf(printer, printer.copy(id = 1))))
        assertEquals(LinkSelection.Ambiguous, UsbLinkSelector.select(listOf(printer.copy(
            endpoints = printer.endpoints + EndpointFact(0x84, 2)))))
    }
    @Test fun partialWritesResumeAtCorrectOffset() {
        val seen = mutableListOf<Pair<Int, Int>>()
        val writer = BoundedBulkWriter(BulkSink { _, start, length, _ ->
            seen += start to length
            minOf(3, length)
        }, chunkSize = 5)
        assertEquals(BulkOutcome.Accepted(11), writer.send(ByteArray(11)))
        assertEquals(listOf(0 to 5, 3 to 5, 6 to 5, 9 to 2), seen)
    }
    @Test fun timeoutsAndZeroProgressAreNotBlindlyRetried() {
        var calls = 0
        val writer = BoundedBulkWriter(BulkSink { _, _, _, _ ->
            calls++
            if (calls == 1) 2 else -1
        }, chunkSize = 4)
        assertEquals(BulkOutcome.Uncertain(2, BulkOutcome.Reason.TIMEOUT_OR_IO_ERROR),
            writer.send(ByteArray(8)))
        assertEquals(2, calls)
        var zeros = 0
        val zero = BoundedBulkWriter(BulkSink { _, _, _, _ -> zeros++; 0 })
        assertEquals(BulkOutcome.Uncertain(0, BulkOutcome.Reason.ZERO_PROGRESS),
            zero.send(ByteArray(3)))
        assertEquals(1, zeros)
    }
    @Test fun cancelledAfterPartialDoesNotAttemptNextWrite() {
        var calls = 0
        val writer = BoundedBulkWriter(BulkSink { _, _, _, _ -> calls++; 2 }, chunkSize = 2)
        assertEquals(BulkOutcome.Uncertain(2, BulkOutcome.Reason.CANCELLED),
            writer.send(ByteArray(5)) { calls >= 1 })
        assertEquals(1, calls)
    }
    @Test fun noFalseCompletedStateAndNoAutomaticReplay() {
        val active = JobState().advance(PrintStage.PREPARING).advance(PrintStage.TRANSFERRING)
        assertFalse(active.mayAutoRestart)
        assertEquals(PrintStage.UNKNOWN_AFTER_INTERRUPTION, active.interrupted().stage)
        assertThrows(IllegalArgumentException::class.java) { active.advance(PrintStage.COMPLETED) }
        assertThrows(IllegalArgumentException::class.java) {
            active.interrupted().advance(PrintStage.TRANSFERRING)
        }
    }
    @Test fun explicitResultAfterDataTransfer() {
        val sending = JobState().advance(PrintStage.PREPARING).advance(PrintStage.TRANSFERRING)
        assertEquals(PrintStage.COMPLETED, sending.advance(PrintStage.AWAITING_RESULT)
            .advance(PrintStage.COMPLETED).stage)
    }
    @Test fun pageRangesCopyOrderAndValidation() {
        val p = PagePlan.parse(8, "1–3, 5, 8", 2)
        assertEquals(listOf(1,2,3,5,8,1,2,3,5,8), p.orderedSheets().toList())
        assertThrows(IllegalArgumentException::class.java) { PagePlan.parse(8, "1-3, 3-4", 1) }
        assertThrows(IllegalArgumentException::class.java) { PagePlan.parse(8, "9", 1) }
        assertThrows(IllegalArgumentException::class.java) { PagePlan.parse(8, "1--2", 1) }
        assertThrows(IllegalArgumentException::class.java) { PagePlan.parse(8, "", 21) }
        assertEquals((1..8).toList(), PagePlan.parse(8,"",1).pages)
    }
}
