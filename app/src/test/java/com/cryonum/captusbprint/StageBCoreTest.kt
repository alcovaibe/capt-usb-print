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

    @Test fun acceptsMinimalSixByteGetBasicStatusReply() {
        // Upstream CAPT parser accepts two basic status bytes even when no
        // buffer-level bytes are present in the response.
        val frame = byteArrayOf(0xA0.toByte(), 0xE0.toByte(), 6, 0, 0x41, 0x03)
        assertEquals(listOf(6), CaptFrame.lengthCandidates(frame, 1024))
        assertTrue(CaptFrame.headerLengthMatches(frame, 6))
        val parsed = CaptBasicStatusParser.parse(frame)
        assertEquals(0x41, parsed.statusByte)
        assertEquals(0x03, parsed.secondaryByte)
        assertNull(parsed.bufferLowByte)
        assertNull(parsed.bufferHighByte)
    }

    @Test fun statusParserReadsOptionalBufferLevelFromLongerReply() {
        val frame = byteArrayOf(
            0xA0.toByte(), 0xE0.toByte(), 10, 0,
            0x41, 0x03, 0x00, 0x00, 0x0F, 0x00
        )
        val parsed = CaptBasicStatusParser.parse(frame)
        assertEquals(0x41, parsed.statusByte)
        assertEquals(0x03, parsed.secondaryByte)
        assertTrue(parsed.bufferLowByte == 0x0F)
        assertTrue(parsed.bufferHighByte == 0x00)
        assertThrows(IllegalArgumentException::class.java) {
            CaptBasicStatusParser.parse(frame.copyOf(9))
        }
    }

    @Test fun basicStatusFlagsAreDecodedWithoutInventingAFault() {
        assertEquals(listOf("IM_DATA_BUSY", "ERROR_BIT"), CaptStatusFlagDecoder.basic(0x88))
        assertEquals(listOf("NONE"), CaptStatusFlagDecoder.secondary(0x00))
        assertEquals(
            listOf("EXTENDED_STATUS_CHANGED", "NEED_INPUT_STATUS"),
            CaptStatusFlagDecoder.secondary(0x03)
        )
    }

    @Test fun extendedStatusParserReadsEnginePaperAndPageCounters() {
        val payload = ByteArray(36)
        payload[0] = 0x88.toByte()
        payload[1] = 0x00
        payload[4] = 0x0f
        payload[5] = 0x00
        payload[8] = 0x02
        payload[9] = 0x40
        payload[10] = 0x00
        payload[12] = 0x00
        payload[13] = 0x41
        payload[14] = 0x02
        payload[16] = 0x01
        payload[18] = 0x01
        payload[20] = 0x01
        payload[34] = 0x03
        val parsed = CaptExtendedStatusParser.parse(CaptFrame.encode(0xA0A8, payload))
        assertEquals(0x88, parsed.basicStatusByte)
        assertEquals(15, parsed.bufferSlots)
        assertEquals(0x02, parsed.auxiliaryByte)
        assertEquals(0x40, parsed.engineCommunicationByte)
        assertEquals("NO_PAPER", CaptStatusFlagDecoder.paper(parsed.paperAvailabilityByte))
        assertEquals(0x4100, parsed.engineFlags)
        assertEquals(listOf("PAPER_JAM", "DOOR_OPEN"), CaptStatusFlagDecoder.engine(parsed.engineFlags))
        assertEquals(2, parsed.pageDecoding)
        assertEquals(1, parsed.pagePrinting)
        assertEquals(1, parsed.pageOut)
        assertEquals(1, parsed.pageCompleted)
        assertEquals(3, parsed.pageReceived)
    }

    @Test fun extendedStatusParserKeepsPageCountersUnavailableForShortPayload() {
        val parsed = CaptExtendedStatusParser.parse(CaptFrame.encode(0xA0A8, ByteArray(14)))
        assertNull(parsed.pageDecoding)
        assertNull(parsed.pagePrinting)
        assertNull(parsed.pageOut)
        assertNull(parsed.pageCompleted)
        assertNull(parsed.pageReceived)
    }

    @Test fun printerInfoParserUsesDocumentedLittleEndianOffsetsAndClampsChunk() {
        val payload = ByteArray(10)
        payload[6] = 0xf0.toByte()
        payload[7] = 0xff.toByte()
        payload[8] = 0x40
        payload[9] = 0x00
        val parsed = CaptPrinterInfoParser.parse(CaptFrame.encode(0xA1A1, payload))
        assertEquals(65520, parsed.blockSize)
        assertEquals(64, parsed.bufferSize)
        assertEquals(0xff00, parsed.safeChunkBytes)
        assertTrue(parsed.transferParametersUsable)
        assertEquals(10, parsed.payloadBytes)
    }

    @Test fun printerInfoParserRejectsShortOrWrongCommandReplies() {
        val short = CaptFrame.encode(0xA1A1, ByteArray(9))
        assertThrows(IllegalArgumentException::class.java) {
            CaptPrinterInfoParser.parse(short)
        }
        val wrongCommand = CaptFrame.encode(0xA0A8, ByteArray(10))
        assertThrows(IllegalArgumentException::class.java) {
            CaptPrinterInfoParser.parse(wrongCommand)
        }
    }

    @Test fun printerInfoParserDoesNotTrustZeroBlockOrBufferValues() {
        val payload = ByteArray(10)
        payload[8] = 0x40
        val parsed = CaptPrinterInfoParser.parse(CaptFrame.encode(0xA1A1, payload))
        assertNull(parsed.safeChunkBytes)
        assertFalse(parsed.transferParametersUsable)
    }

    @Test fun inputStatusParserPreservesOpaquePayloadWithoutInventingFields() {
        val payload = byteArrayOf(0x11, 0x22, 0x80.toByte(), 0x00)
        val frame = CaptFrame.encode(CaptInputStatusParser.GET_INPUT_STATUS, payload)
        assertArrayEquals(payload, CaptInputStatusParser.parse(frame))
    }

    @Test fun inputStatusParserAcceptsTheObservedTwentyTwoByteReplyShape() {
        // Hardware report from 2026-10-22; payload remains opaque because upstream
        // requests GetInputStatus but does not define these fields.
        val payload = byteArrayOf(
            0x00, 0x10, 0xc0.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x96.toByte(), 0x00, 0xfd.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val frame = CaptFrame.encode(CaptInputStatusParser.GET_INPUT_STATUS, payload)
        assertEquals(22, frame.size)
        assertArrayEquals(payload, CaptInputStatusParser.parse(frame))
    }

    @Test fun inputStatusParserRejectsWrongCommandAndMalformedFrame() {
        val wrongCommand = CaptFrame.encode(0xA0A8, byteArrayOf(1, 2))
        assertThrows(IllegalArgumentException::class.java) {
            CaptInputStatusParser.parse(wrongCommand)
        }
        val malformed = byteArrayOf(0xA1.toByte(), 0xA0.toByte(), 8, 0, 1, 2)
        assertThrows(IllegalArgumentException::class.java) {
            CaptInputStatusParser.parse(malformed)
        }
    }

    @Test fun extendedStatusParserRejectsReplyWithoutRequiredFields() {
        val shortReply = CaptFrame.encode(0xA0A8, ByteArray(12))
        assertThrows(IllegalArgumentException::class.java) {
            CaptExtendedStatusParser.parse(shortReply)
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
