package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.*
import com.cryonum.captusbprint.core.CaptJobProtocol as P
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CaptDocumentPreflightTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    // Captured on build 144 immediately before ReserveUnit succeeded with JobID=1.
    // Basic was 88 in separate diagnostic sessions; its meaning is NOT established.
    private val extended = hex("A8 A0 38 00 31 88 00 00 0F 00 00 00 00 00 80 00 00 00 00 00 00 00 00 00 00 00 00 00 56 00 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
    private val input = hex("A1 A0 16 00 00 10 C0 00 00 00 00 00 00 00 96 00 FD 00 00 00 00 00")

    private inner class Probe {
        var millis = 0L
        var samples = 0
        val commands = ArrayList<Int>()
        val trace = ArrayList<String>()
        var status: () -> ByteArray = { extended }
        var onInput: () -> Unit = {}
        var stop = false
        var advanceClock = true
        fun run(afterPowerDown: Boolean = false): CaptExtendedStatus {
            val query: (Int) -> ByteArray = { command ->
                commands.add(command)
                when (command) {
                    P.GET_EXTENDED_STATUS -> { samples++; status().copyOf() }
                    P.GET_INPUT_STATUS -> { onInput(); input.copyOf() }
                    else -> error("Command outside LBP2900 status policy: $command")
                }
            }
            val guard = { if (stop) throw CancellationException() }
            val pause: (Long) -> Unit = { if (advanceClock) millis += it }
            return if (afterPowerDown) CaptDocumentPreflight.afterPowerDown(
                query, guard, { millis }, pause, { trace.add(it) })
            else CaptDocumentPreflight.awaitReady(query, guard, { millis }, pause, { trace.add(it) })
        }
    }

    @Test fun capturedOfflineStartupUsesExtendedPolicyWithoutABasicStatusVeto() {
        val probe = Probe()
        assertEquals(0x31, probe.run().basicStatusByte)
        assertEquals(listOf(P.GET_EXTENDED_STATUS), probe.commands)
        assertTrue(probe.trace.any { it.contains("A8 A0 38 00 31 88") })
    }

    @Test fun transientNotReadyAndBusyWaitWithoutRecoveryCommands() {
        for (flag in listOf(0x02, 0x04, 0x08)) {
            val probe = Probe().apply {
                status = { extended.copyOf().also { if (samples == 1) it[4] = (0x31 or flag).toByte() } }
            }
            probe.run()
            assertEquals(2, probe.samples)
            assertEquals(250L, probe.millis)
            assertTrue(probe.commands.all { it == P.GET_EXTENDED_STATUS })
        }
    }

    @Test fun inputRequestRequiresAFreshExtendedStatusAfterInput() {
        var pending = true
        val probe = Probe().apply {
            status = { extended.copyOf().also { if (pending) it[5] = 0x8a.toByte() } }
            onInput = { pending = false }
        }
        probe.run()
        assertEquals(listOf(P.GET_EXTENDED_STATUS, P.GET_INPUT_STATUS, P.GET_EXTENDED_STATUS), probe.commands)
    }

    @Test fun persistentInputRequestIsBoundedAndCannotReserve() {
        val probe = Probe().apply { status = { extended.copyOf().also { it[5] = 0x8a.toByte() } } }
        assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
        assertEquals(5_000L, probe.millis)
        assertEquals(21, probe.samples)
        assertEquals(21, probe.commands.count { it == P.GET_INPUT_STATUS })
    }

    @Test fun hardwareFlagsOrMissingPaperStopImmediately() {
        // Synthetic fault mutations, not claims about the owner's physical printer.
        for ((offset, value) in listOf(4 to 0xb1, 13 to 0x10, 14 to 0, 17 to 0x40)) {
            val probe = Probe().apply { status = { extended.copyOf().also { it[offset] = value.toByte() } } }
            assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
            assertEquals(1, probe.samples)
        }
    }

    @Test fun pendingPageDeliveryAndPersistentBusyCannotPassAdmission() {
        for ((offset, value) in listOf(18 to 1, 38 to 1, 12 to 4, 12 to 2, 4 to 0x35, 4 to 0x33)) {
            val probe = Probe().apply { status = { extended.copyOf().also { it[offset] = value.toByte() } } }
            assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
            assertEquals(21, probe.samples)
        }
    }

    @Test fun afterPowerConfigurationChecksActualStateWithoutRequiringFreeBit() {
        // A reservation can clear FREE. ReserveUnit, not this bit, owns the JobID.
        val probe = Probe().apply { status = { extended.copyOf().also { it[4] = 0x30 } } }
        assertEquals(0x30, probe.run(afterPowerDown = true).basicStatusByte)
        assertTrue(probe.trace.any { it.startsWith("JobSetup") })
    }

    @Test fun afterPowerConfigurationStillRejectsEngineFaults() {
        val probe = Probe().apply { status = { extended.copyOf().also { it[16] = 1 } } }
        assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) {
            probe.run(afterPowerDown = true)
        }.code)
        assertEquals(1, probe.samples)
    }

    @Test fun truncatedStatusStopsAndRetainsMalformedFrame() {
        val probe = Probe().apply { status = { extended.copyOf(8) } }
        assertEquals("CAPT_STATUS_INVALID", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
        assertEquals(1, probe.samples)
        assertTrue(probe.trace.any { it.contains("GetExtendedStatus bytes=8:") })
    }

    @Test fun missingPageCountersFailClosed() {
        for (length in listOf(18, 26)) {
            val probe = Probe().apply {
                status = { extended.copyOf(length).also { it[2] = length.toByte() } }
            }
            assertEquals("CAPT_STATUS_INVALID", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
        }
    }

    @Test fun cancellationBeforeOrAfterFirstQueryNeverSendsFurtherCommands() {
        val before = Probe().apply { stop = true }
        assertThrows(CancellationException::class.java) { before.run() }
        assertTrue(before.commands.isEmpty())
        val during = Probe().apply { status = { stop = true; extended } }
        assertThrows(CancellationException::class.java) { during.run() }
        assertEquals(1, during.commands.size)
    }

    @Test fun sampleLimitAlsoStopsWithAClockThatDoesNotAdvance() {
        val probe = Probe().apply {
            advanceClock = false
            status = { extended.copyOf().also { it[4] = 0x35 } }
        }
        assertEquals("PRINTER_STATUS", assertThrows(CaptPreflightFailure::class.java) { probe.run() }.code)
        assertEquals(21, probe.samples)
    }
}
