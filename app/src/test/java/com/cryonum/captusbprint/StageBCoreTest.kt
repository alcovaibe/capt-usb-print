package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.*
import org.junit.Assert.*
import org.junit.Test

class StageBCoreTest {
    @Test fun knownGetBasicStatusRequestVector() {
        // Independent wire vector from upstream enum 0xE0A0 + 4-byte LE header.
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0xE0.toByte(), 0x04, 0x00),
            CaptFrame.encode(0xE0A0))
    }

    @Test fun commandPayloadUsesLittleEndianLengthAndCopiesInput() {
        val body = byteArrayOf(0x12, 0x34)
        val frame = CaptFrame.encode(0xA1A1, body)
        body[0] = 0
        assertArrayEquals(byteArrayOf(0xA1.toByte(), 0xA1.toByte(), 6, 0, 0x12, 0x34), frame)
    }

    @Test fun acceptsOnlyExactKnownResponseShape() {
        val raw = byteArrayOf(0xA0.toByte(), 0xE0.toByte(), 6, 0, 0x12, 0x34)
        assertArrayEquals(byteArrayOf(0x12, 0x34), CaptFrame.parseReply(0xE0A0, raw))
        assertThrows(IllegalArgumentException::class.java) {
            CaptFrame.parseReply(0xE0A1, raw)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptFrame.parseReply(0xE0A0, raw.copyOf(5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptFrame.parseReply(0xE0A0, raw + 0)
        }
    }

    @Test fun acceptsBcdReplyLengthOnlyWhenItMatchesReceivedBytes() {
        // LE=16, BCD=10: upstream CAPT devices may use the BCD interpretation.
        val bcd = byteArrayOf(
            0xA0.toByte(), 0xE0.toByte(), 0x10, 0x00,
            0x41, 0x03, 0x00, 0x00, 0x0F, 0x00
        )
        assertEquals(listOf(16, 10), CaptFrame.lengthCandidates(bcd, 1024))
        assertTrue(CaptFrame.headerLengthMatches(bcd, 10))
        assertTrue(CaptFrame.headerLengthMatches(bcd, 16))
        assertFalse(CaptFrame.headerLengthMatches(bcd, 12))
        assertEquals(6, CaptFrame.parseReply(0xE0A0, bcd).size)
    }

    @Test fun statusParserReadsOnlyTheValidatedBasicStatusFrame() {
        val frame = byteArrayOf(
            0xA0.toByte(), 0xE0.toByte(), 10, 0,
            0x41, 0x03, 0x00, 0x00, 0x0F, 0x00
        )
        val parsed = CaptBasicStatusParser.parse(frame)
        assertEquals(0x41, parsed.statusByte)
        assertEquals(0x03, parsed.secondaryByte)
        assertEquals(0x0F, parsed.bufferLowByte)
        assertEquals(0x00, parsed.bufferHighByte)
        assertThrows(IllegalArgumentException::class.java) {
            CaptBasicStatusParser.parse(frame.copyOf(9))
        }
    }

    @Test fun nestedCommandUsesBoundedLength() {
        // Outer D0A9; two 4-byte children: D0A0, D0A2; size 12.
        val frame = CaptFrame.encodeMulti(listOf(
            0xD0A0 to byteArrayOf(), 0xD0A2 to byteArrayOf()
        ))
        assertArrayEquals(byteArrayOf(
            0xA9.toByte(), 0xD0.toByte(), 12, 0,
            0xA0.toByte(), 0xD0.toByte(), 4, 0,
            0xA2.toByte(), 0xD0.toByte(), 4, 0
        ), frame)
        assertThrows(IllegalArgumentException::class.java) {
            CaptFrame.encode(0xE0A0, ByteArray(65532))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptFrame.encodeMulti(listOf(0xE0A0 to ByteArray(65530), 0xE0A1 to byteArrayOf()))
        }
    }

    @Test fun parserRejectsOverflowsDuplicatesAndOutOfBounds() {
        assertEquals(listOf(0, 1, 2, 4, 7, 0, 1, 2, 4, 7),
            JobSpecification.create(10, "1–3, 5, 8", 2).outputPageOrder)
        assertEquals(listOf(0, 1, 2), JobSpecification.create(3, "", 1).pageIndices)
        for (range in listOf("0", "3-1", "1,1", "2-4", "1,,2", "9999999999999", "1-2, 2")) {
            assertThrows("Invalid: $range", IllegalArgumentException::class.java) {
                JobSpecification.create(3, range, 1)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1000, "", 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1, "", 21)
        }
    }

    @Test fun onlyOneJobMayOwnThePipelineAndNoAutomaticResume() {
        val ledger = JobLedger()
        ledger.begin(12)
        assertThrows(IllegalStateException::class.java) { ledger.begin(13) }
        ledger.transition(12, JobPhase.WAITING_PRINTER)
        ledger.transition(12, JobPhase.TRANSMITTING)
        assertThrows(IllegalStateException::class.java) {
            ledger.transition(12, JobPhase.COMPLETED) // USB write != printed page
        }
        ledger.interrupted(12)
        assertEquals(JobPhase.OUTCOME_UNKNOWN, ledger.phase)
        assertFalse(ledger.hasActiveJob())
        assertThrows(IllegalStateException::class.java) {
            ledger.transition(12, JobPhase.TRANSMITTING) // no implicit replay
        }
        ledger.begin(13) // explicit new job only
        ledger.transition(13, JobPhase.CANCELLED)
    }

    private class RecordingChannel(
        private val writes: List<Int> = emptyList(),
        private val reads: List<Int> = emptyList()
    ) : BulkChannel {
        var writeCalls = 0
        var readCalls = 0
        var accepted = 0
        override fun write(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            require(length <= BulkIo.MAX_USB_CHUNK && timeoutMillis > 0)
            val outcome = writes[writeCalls++]
            if (outcome > 0) accepted += outcome
            return outcome
        }
        override fun read(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            val outcome = reads[readCalls++]
            if (outcome > 0) for (p in offset until offset + outcome) data[p] = 0x55
            return outcome
        }
    }

    @Test fun partialBulkWriteContinuesAtOffsetWithoutReplayingEarlierBytes() {
        val c = RecordingChannel(writes = listOf(2, 1, 3))
        assertEquals(6, BulkIo(c).writeExactly(ByteArray(6), 500))
        assertEquals(3, c.writeCalls)
        assertEquals(6, c.accepted)
    }

    @Test fun ambiguousWriteIsNotAutomaticallyRetried() {
        val c = RecordingChannel(writes = listOf(2, -1, 3))
        val failure = assertThrows(TransferUncertainException::class.java) {
            BulkIo(c).writeExactly(ByteArray(6), 500)
        }
        assertEquals(2, failure.acceptedBytes)
        assertEquals(2, c.writeCalls)
    }

    @Test fun transportIsBoundedAndCancellationDoesNotWrite() {
        val c = RecordingChannel(writes = listOf(1))
        val cancelled = BulkIo(c, cancelled = { true })
        assertEquals(0, assertThrows(TransferUncertainException::class.java) {
            cancelled.writeExactly(byteArrayOf(7), 500)
        }.acceptedBytes)
        assertEquals(0, c.writeCalls)
        assertThrows(IllegalArgumentException::class.java) {
            BulkIo(c).writeExactly(ByteArray(65536), 500)
        }
        var fakeTime = 0L
        val time = BulkIo(c, nowNanos = { fakeTime }, cancelled = { false })
        fakeTime = 2_000_000_000L
        // Same injected clock for start and check; no false claim about a real delay.
        assertEquals(1, time.writeExactly(byteArrayOf(1), 500))
    }

    @Test fun shortReadFailsClosed() {
        val c = RecordingChannel(reads = listOf(1, 0))
        assertThrows(IllegalStateException::class.java) {
            BulkIo(c).readExactly(2, 500)
        }
        assertEquals(2, c.readCalls)
    }
}
