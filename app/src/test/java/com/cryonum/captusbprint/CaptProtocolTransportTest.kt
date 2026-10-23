package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.*
import com.cryonum.captusbprint.core.CaptJobProtocol as P
import org.junit.Assert.*
import org.junit.Test

class CaptProtocolTransportTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val short = hex("A0 E0 06 00 88 00")
    private val extended = hex("A8 A0 38 00 01 88 00 00 0F 00 00 00 00 00 80 00 00 00 02 00 02 00 02 00 02 00 00 00 57 00 00 00 01 00 00 00 00 05 02 00 00 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00")

    private class Channel(private val bytes: ByteArray, private val chunks: List<Int> = emptyList()) : BulkChannel {
        val requests = mutableListOf<Int>()
        var consumed = 0
        var writes = 0
        override fun write(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            writes++
            return length
        }
        override fun read(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            val index = requests.size
            requests += length
            val count = chunks.getOrNull(index) ?: minOf(length, bytes.size - consumed)
            if (count > 0) {
                require(count <= length)
                bytes.copyInto(data, offset, consumed, consumed + count)
                consumed += count
            }
            return count
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun events(trace: CaptProtocolTrace) = trace.snapshot()["events"] as List<Map<String, Any?>>

    @Test fun observedShortFrameStaysRawWithoutBecomingAControlAckOrAFullStatus() {
        val channel = Channel(short)
        val trace = CaptProtocolTrace()
        val raw = CaptReplyReader(channel, trace, { 0 }, { false }).read(P.GET_BASIC_STATUS)
        assertArrayEquals(short, raw)
        assertEquals(listOf(6), channel.requests)
        val parsed = CaptBasicStatusParser.parse(raw)
        assertEquals("SHORT_UNVERIFIED", parsed.responseForm)
        assertEquals(0x88, parsed.statusByte)
        assertEquals(0, parsed.secondaryByte)
        assertNull(parsed.bufferLowByte)
        trace.reply(P.GET_BASIC_STATUS, raw) // Status bytes are not a control-command ACK.
        assertEquals(false, trace.snapshot()["releaseAcknowledged"])
    }

    @Test fun littleEndian56IsNotTruncatedAtBcd38AndFragmentedReadsArePreserved() {
        for (chunks in listOf(emptyList(), listOf(2, 1, 3, 8, 42))) {
            val channel = Channel(extended, chunks)
            val trace = CaptProtocolTrace()
            val actual = CaptReplyReader(channel, trace, { 0 }, { false }).read(P.GET_EXTENDED_STATUS)
            assertArrayEquals(extended, actual)
            assertEquals(56, actual.size)
            val records = events(trace).filter { it["event"] == "USB_TRANSFER" }
            assertEquals(56, records.sumOf { it["transferReturnCode"] as Int })
            assertEquals(0, channel.writes)
        }
    }

    @Test fun wrongOrLateOpcodeStopsWithoutFlushingOrTryingToFindTheNextReply() {
        val channel = Channel(short + extended)
        val failure = assertThrows(CaptReplyReadFailure::class.java) {
            CaptReplyReader(channel, CaptProtocolTrace(), { 0 }, { false }).read(P.GET_EXTENDED_STATUS)
        }
        assertArrayEquals(short, failure.partialFrame)
        assertEquals(6, channel.consumed)
        assertEquals(listOf(6), channel.requests)
        assertEquals(0, channel.writes)
    }

    @Test fun partialContinuationIsRetainedWithReturnCodeAndDeclaredLength() {
        val channel = Channel(extended, listOf(6, 7, -1))
        val trace = CaptProtocolTrace()
        val failure = assertThrows(CaptReplyReadFailure::class.java) {
            CaptReplyReader(channel, trace, { 0 }, { false }).read(P.GET_EXTENDED_STATUS)
        }
        assertEquals(56, failure.expectedBytes)
        assertArrayEquals(extended.copyOf(13), failure.partialFrame)
        assertEquals("A8 A0 38 00 01 88 00 00 0F 00 00 00 00", events(trace).last()["partialFrameHex"])
        assertTrue(events(trace).any { it["transferReturnCode"] == -1 && it["requestedBytes"] == 43 })
    }

    @Test fun cancellationDeadlineAndBadLengthStopBeforeFurtherTraffic() {
        val cancelledChannel = Channel(short)
        assertThrows(CaptReplyReadFailure::class.java) {
            CaptReplyReader(cancelledChannel, CaptProtocolTrace(), { 0 }, { true }).read(P.GET_BASIC_STATUS)
        }
        assertTrue(cancelledChannel.requests.isEmpty())
        var clock = 0L
        val timedChannel = Channel(short)
        assertThrows(CaptReplyReadFailure::class.java) {
            CaptReplyReader(timedChannel, CaptProtocolTrace(), { clock += 6000; clock }, { false }).read(P.GET_BASIC_STATUS)
        }
        assertTrue(timedChannel.requests.isEmpty())
        val badLength = Channel(hex("A0 E0 FF FF 88 00"))
        assertThrows(CaptReplyReadFailure::class.java) {
            CaptReplyReader(badLength, CaptProtocolTrace(), { 0 }, { false }).read(P.GET_BASIC_STATUS)
        }
        assertEquals(listOf(6), badLength.requests)
    }

    @Test fun controlErrorByteIsCheckedWithoutAssigningMeaningToOpaqueSecondByte() {
        val commands = listOf(P.SET_JOB_INFO2, P.GO_OFFLINE, P.GO_ONLINE,
            P.DISCARD_DATA, P.CLEAR_MIS_PRINT, P.CLEAR_ERROR, P.START_PRINT, P.RELEASE_UNIT)
        for (command in commands) {
            CaptCommandAcks.requireAccepted(command, CaptFrame.encode(command, byteArrayOf(0, 0x7f)))
            val failure = assertThrows(CaptCommandAckFailure::class.java) {
                CaptCommandAcks.requireAccepted(command, CaptFrame.encode(command, byteArrayOf(0x88.toByte(), 0)))
            }
            assertEquals(command, failure.command)
            assertEquals(0x88, failure.resultByte)
        }
        // Reserve has a separate JobID/error contract, never the generic ACK decoder.
        CaptCommandAcks.requireAccepted(P.RESERVE_UNIT, hex("A0 A2 08 00 8C 00 00 00"))
        assertThrows(IllegalArgumentException::class.java) {
            P.parseReservedJob(hex("A0 A2 08 00 8C 00 00 00"))
        }
    }

    @Test fun capturedPowerDownReplyIsPreservedWithoutClaimingPrintAcceptance() {
        val trace = CaptProtocolTrace()
        trace.reserved(1)
        trace.reply(P.SET_POWER_DOWN, hex("BA E0 06 00 80 00"))
        val snapshot = trace.snapshot()
        assertEquals(0x80, snapshot["powerDownResponseWord"])
        assertEquals(false, snapshot["endAcknowledged"])
        assertEquals(false, snapshot["releaseAcknowledged"])
        assertNull(snapshot["printerConfirmedPages"])
        assertEquals(false, events(trace).last()["printAcceptance"])
        assertEquals(true, events(trace).last()["requiresExtendedStateCheck"])
        assertThrows(IllegalArgumentException::class.java) {
            trace.reply(P.SET_POWER_DOWN, hex("BA E0 06 00 80"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            trace.reply(P.SET_POWER_DOWN, hex("BA E0 08 00 80 00 00 00"))
        }
    }

    @Test fun negativeReleaseDoesNotErasePageConfirmationOrClaimReleased() {
        var phase = "RESERVING"
        val trace = CaptProtocolTrace(phase = { phase })
        trace.reserved(42)
        trace.pagesConfirmed(3)
        phase = "FINALIZING"
        trace.reply(P.SET_JOB_INFO2, CaptFrame.encode(P.SET_JOB_INFO2, byteArrayOf(0, 0)),
            P.jobInfo(42, P.END_FLAG))
        assertThrows(CaptCommandAckFailure::class.java) {
            trace.reply(P.RELEASE_UNIT, hex("A9 E0 06 00 88 00"))
        }
        val snapshot = trace.snapshot()
        assertEquals(3, snapshot["printerConfirmedPages"])
        assertEquals(true, snapshot["endAcknowledged"])
        assertEquals(false, snapshot["releaseAcknowledged"])
        assertTrue(events(trace).any { it["rawFrameHex"] == "A9 E0 06 00 88 00" && it["phase"] == "FINALIZING" })
    }

    @Test fun boundedTraceRetainsSetupAndFinalFailureAndNeverCopiesOutPayload() {
        val trace = CaptProtocolTrace()
        trace.event("SETUP")
        repeat(1000) {
            trace.transfer("OUT", 0xc0a0, 0, 13, 13, "SECRET_RASTER".toByteArray())
        }
        trace.event("FINAL_FAILURE")
        val snapshot = trace.snapshot()
        assertEquals(CaptProtocolTrace.HEAD_EVENTS + CaptProtocolTrace.TAIL_EVENTS, events(trace).size)
        assertEquals("SETUP", events(trace).first()["event"])
        assertEquals("FINAL_FAILURE", events(trace).last()["event"])
        assertEquals(1002L - events(trace).size, snapshot["omittedEvents"])
        assertFalse(DiagnosticJson.stringify(snapshot).contains("SECRET_RASTER"))
        assertFalse(events(trace).any { it["direction"] == "OUT" && it.containsKey("rawHex") })
    }
    @Test fun directVideoWriteHasItsOwnExchangeAndScalarJobFlagsAreVisible() {
        val trace = CaptProtocolTrace()
        trace.begin(P.GET_BASIC_STATUS)
        trace.reply(P.GET_BASIC_STATUS, short)
        trace.beginWrite(0xc0a0, 100)
        trace.transfer("OUT", 0xc0a0, 0, 100, 100)
        val records = events(trace)
        assertEquals(1L, records.first()["exchange"])
        assertEquals(2L, records.last()["exchange"])
        trace.begin(P.SET_JOB_INFO2)
        trace.reply(P.SET_JOB_INFO2, CaptFrame.encode(P.SET_JOB_INFO2, byteArrayOf(0, 0)),
            P.jobInfo(42, P.END_FLAG))
        assertEquals(6, events(trace).last()["jobFlag"])
        assertEquals(42, events(trace).last()["jobId"])
        assertFalse(events(trace).last().containsKey("requestPayload"))
    }

}
