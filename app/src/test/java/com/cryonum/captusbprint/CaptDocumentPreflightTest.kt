package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.*
import com.cryonum.captusbprint.core.CaptJobProtocol as P
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CaptDocumentPreflightTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    // Owner-captured replies. Basic 0x88 and extended Bas=0x01/Bas1=0x88
    // must stay separate; neither is fabricated into a successful reservation.
    private val blocked = hex("A0 E0 06 00 88 00")
    // Synthetic positive Basic; no positive Basic was captured on hardware.
    private val ready = hex("A0 E0 06 00 01 00")
    private val extended = hex("A8 A0 38 00 01 88 00 00 0F 00 00 00 00 00 80 00 00 00 02 00 02 00 02 00 02 00 00 00 57 00 00 00 01 00 00 00 00 05 02 00 00 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00")
    private val input = hex("A1 A0 16 00 00 10 C0 00 00 00 00 00 00 00 96 00 FD 00 00 00 00 00")

    private inner class Probe {
        var millis = 0L
        var samples = 0
        var checks = 0
        val commands = ArrayList<Int>()
        val trace = ArrayList<String>()
        var basic: () -> ByteArray = { blocked }
        var status: () -> ByteArray = { extended }
        var onInput: () -> Unit = {}
        var stop = false
        var advanceClock = true
        fun run(): CaptExtendedStatus = CaptDocumentPreflight.awaitReady(
            query = { command ->
                commands.add(command)
                when (command) {
                    P.GET_BASIC_STATUS -> { samples++; basic().copyOf() }
                    P.GET_EXTENDED_STATUS -> status().copyOf()
                    P.GET_INPUT_STATUS -> { onInput(); input.copyOf() }
                    else -> error("State-changing command in preflight: $command")
                }
            },
            guard = { checks++; if (stop) throw CancellationException() },
            now = { millis },
            pause = { if (advanceClock) millis += it },
            note = { trace.add(it) }
        )
    }

    @Test fun persistentCapturedConflictIsBoundedAndPreservesBothRawReplies() {
        val probe = Probe()
        val error = assertThrows(CaptPreflightFailure::class.java) { probe.run() }
        assertEquals("CAPT_STATUS_CONFLICT", error.code)
        assertEquals(5_000L, probe.millis)
        assertEquals(21, probe.samples)
        assertEquals(42, probe.commands.size)
        assertTrue(probe.trace.any { it.endsWith("A0 E0 06 00 88 00") })
        assertTrue(probe.trace.any { it.contains("A8 A0 38 00 01 88") })
    }

    @Test fun transientBusyClearsBeforeReadinessWithoutAnyRecoveryCommand() {
        val probe = Probe().apply { basic = { if (samples == 1) blocked else ready } }
        assertEquals(2, probe.run().pageCompleted)
        assertEquals(2, probe.samples)
        assertEquals(250L, probe.millis)
        assertEquals(listOf(P.GET_BASIC_STATUS, P.GET_EXTENDED_STATUS,
            P.GET_BASIC_STATUS, P.GET_EXTENDED_STATUS), probe.commands)
    }

    @Test fun inputFlagFromEitherReplyIsConsumedAndBothRepliesAreReadAgain() {
        for (basicFlag in listOf(true, false)) {
            var pending = true
            val probe = Probe().apply {
                basic = { ready.copyOf().also { if (pending && basicFlag) it[5] = 2 } }
                status = { extended.copyOf().also { if (pending && !basicFlag) it[5] = 0x8a.toByte() } }
                onInput = { pending = false }
            }
            probe.run()
            assertEquals(listOf(P.GET_BASIC_STATUS, P.GET_EXTENDED_STATUS, P.GET_INPUT_STATUS,
                P.GET_BASIC_STATUS, P.GET_EXTENDED_STATUS), probe.commands)
        }
    }

    @Test fun inputFlagThatNeverClearsCannotStartPrinting() {
        val probe = Probe().apply { basic = { ready.copyOf().also { it[5] = 2 } } }
        assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
        assertEquals(21, probe.commands.count { it == P.GET_INPUT_STATUS })
    }

    @Test fun hardwareFlagsOrMissingPaperStopImmediatelyEvenWhenBasicLooksReady() {
        // Synthetic mutations of the captured frame model faults, not physical evidence.
        for ((offset, value) in listOf(4 to 0x81, 4 to 0x03, 13 to 0x10, 14 to 0, 17 to 0x40)) {
            val probe = Probe().apply {
                basic = { ready }
                status = { extended.copyOf().also { it[offset] = value.toByte() } }
            }
            assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
            assertEquals(1, probe.samples)
        }
    }

    @Test fun pendingPageAndPaperDeliveryCannotPassPreflight() {
        for ((offset, value) in listOf(18 to 3, 12 to 4, 12 to 2, 4 to 5)) {
            val probe = Probe().apply {
                basic = { ready }
                status = { extended.copyOf().also { it[offset] = value.toByte() } }
            }
            assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
            assertEquals(21, probe.samples)
        }
    }

    @Test fun offlineByItselfDoesNotBlockTheExistingJobPrologue() {
        val probe = Probe().apply {
            basic = { ready.copyOf().also { it[4] = 0x11 } }
            status = { extended.copyOf().also { it[4] = 0x11 } }
        }
        assertEquals(0x11, probe.run().basicStatusByte)
        assertEquals(1, probe.samples)
    }

    @Test fun truncatedStatusStopsAndRetainsTheMalformedFrameForDiagnosis() {
        val probe = Probe().apply { status = { extended.copyOf(8) } }
        assertEquals("CAPT_STATUS_INVALID", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
        assertEquals(1, probe.samples)
        assertTrue(probe.trace.any { it.contains("GetExtendedStatus bytes=8:") })
    }

    @Test fun missingPageCountersFailClosed() {
        val probe = Probe().apply {
            basic = { ready }
            status = { hex("A8 A0 12 00 01 00 00 00 0F 00 00 00 00 00 80 00 00 00") }
        }
        assertEquals("CAPT_STATUS_INVALID", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
    }

    @Test fun cancellationBetweenSamplesAndBeforeFirstQuerySendNoFurtherCommands() {
        val before = Probe().apply { stop = true }
        assertThrows(CancellationException::class.java) { before.run() }
        assertTrue(before.commands.isEmpty())
        val during = Probe().apply { status = { stop = true; extended } }
        assertThrows(CancellationException::class.java) { during.run() }
        assertEquals(2, during.commands.size)
    }

    @Test fun sampleLimitAlsoStopsWithAClockThatDoesNotAdvance() {
        val probe = Probe().apply { advanceClock = false }
        assertEquals("CAPT_STATUS_CONFLICT", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
        assertEquals(21, probe.samples)
    }
}
