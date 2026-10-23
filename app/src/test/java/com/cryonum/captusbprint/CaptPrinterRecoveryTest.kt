package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.*
import com.cryonum.captusbprint.core.CaptJobProtocol as P
import org.junit.Assert.*
import org.junit.Test

/** Synthetic transport faults/command sequences. These are not hardware acceptance. */
class CaptPrinterRecoveryTest {
    private fun status(basic: Int = 1, cnt: Int = 0, pap: Int = 0x80, engine: Int = 0,
                       completed: Int = 0, decoding: Int = completed): ByteArray {
        val body = ByteArray(52)
        body[0] = basic.toByte(); body[1] = 0x88.toByte(); body[4] = 15
        body[9] = cnt.toByte(); body[10] = pap.toByte()
        body[12] = engine.toByte(); body[13] = (engine ushr 8).toByte()
        for (offset in listOf(14, 34)) body[offset] = decoding.toByte()
        for (offset in listOf(16, 18, 20)) body[offset] = completed.toByte()
        return CaptFrame.encode(P.GET_EXTENDED_STATUS, body)
    }
    private class Device(val state: (Boolean, Boolean) -> ByteArray) {
        val commands = ArrayList<Int>()
        var offline = false
        var reset = false
        var reject: Int? = null
        var refuseReserve = false
        var paperInput = 0xc0
        var releasedId: Int? = null
        fun query(command: Int, payload: ByteArray): ByteArray {
            commands += command
            if (command == reject) return CaptFrame.encode(command, byteArrayOf(0x88.toByte(), 0))
            when (command) {
                P.GET_EXTENDED_STATUS -> return state(offline, reset)
                P.GO_OFFLINE -> offline = true
                P.GO_ONLINE -> { offline = false; reset = true }
                P.RESERVE_UNIT -> return CaptFrame.encode(command,
                    if (refuseReserve) byteArrayOf(0x8c.toByte(), 0, 0, 0)
                    else byteArrayOf(0, 0, 1, 0x80.toByte()))
                P.RELEASE_UNIT -> releasedId = (payload[0].toInt() and 255) or ((payload[1].toInt() and 255) shl 8)
                P.GET_INPUT_STATUS -> return CaptFrame.encode(command, ByteArray(18).also { it[2] = paperInput.toByte() })
                P.DISCARD_DATA, P.CLEAR_MIS_PRINT, P.CLEAR_ERROR -> Unit
                else -> error("Clear must not send printing command $command")
            }
            return CaptFrame.encode(command, byteArrayOf(0, 0))
        }
    }
    private fun normal() = Device { offline, reset -> status(if (offline) 0x11 else 1, completed = if (reset) 0 else 2) }
    private fun clear(device: Device): CaptPrinterRecovery.Cleared {
        var ms = 0L
        return CaptPrinterRecovery.clearAndVerify(device::query, { ms }, { ms += it })
    }
    @Test fun clearUsesPinnedErrorClearOrderAndProvesANewReservationCanBeReleasedWithoutPrinting() {
        val device = normal()
        assertTrue(clear(device).paperReady)
        assertEquals(listOf(P.GET_EXTENDED_STATUS, P.GO_OFFLINE, P.GET_EXTENDED_STATUS,
            P.DISCARD_DATA, P.CLEAR_MIS_PRINT, P.CLEAR_ERROR, P.GO_ONLINE,
            P.GET_EXTENDED_STATUS, P.RESERVE_UNIT, P.RELEASE_UNIT,
            P.GET_INPUT_STATUS, P.GET_EXTENDED_STATUS), device.commands)
        assertEquals(0x8001, device.releasedId)
    }
    @Test fun offlineAckAndNonzeroReserveFailureStopWithoutFurtherCommandsOrBorrowedIds() {
        for (reject in listOf(P.GO_OFFLINE, P.RELEASE_UNIT)) {
            val device = normal().apply { this.reject = reject }
            assertThrows(CaptCommandAckFailure::class.java) { clear(device) }
            assertEquals(reject, device.commands.last())
        }
        val device = normal().apply { refuseReserve = true }
        assertThrows(IllegalArgumentException::class.java) { clear(device) }
        assertEquals(1, device.commands.count { it == P.RESERVE_UNIT })
        assertNull(device.releasedId)
    }
    @Test fun hardwareFaultOrUnresetCountersCannotBecomeClearSuccess() {
        val fault = Device { _, _ -> status(engine = 0x100) }
        assertThrows(IllegalStateException::class.java) { clear(fault) }
        assertEquals(listOf(P.GET_EXTENDED_STATUS), fault.commands)
        val stale = Device { offline, _ -> status(if (offline) 0x11 else 1, completed = 2) }
        assertThrows(IllegalStateException::class.java) { clear(stale) }
        assertFalse(stale.commands.contains(P.RESERVE_UNIT))
        assertTrue(stale.commands.size < 50)
    }
    @Test fun inputNotReadyDoesNotPermitPaperContinuation() {
        val device = normal().apply { paperInput = 0x80 }
        assertFalse(clear(device).paperReady)
    }
    @Test fun onlyConfirmedPaperSignatureAndUnacceptedCurrentSheetProvideAResumeBoundary() {
        val paper = CaptExtendedStatusParser.parse(status(0x16, 0x40, 0, completed = 1, decoding = 2))
        assertTrue(CaptPrinterRecovery.isPaperOut(paper))
        assertTrue(CaptPrinterRecovery.canResumeAfter(paper, 1))
        assertFalse(CaptPrinterRecovery.canResumeAfter(paper, 0))
        assertFalse(CaptPrinterRecovery.canResumeAfter(paper.copy(pagePrinting = 2), 1))
        assertFalse(CaptPrinterRecovery.canResumeAfter(paper.copy(pageReceived = null), 1))
        assertFalse(CaptPrinterRecovery.isPaperOut(paper.copy(engineFlags = 0x100)))
        assertFalse(CaptPrinterRecovery.isPaperOut(paper.copy(engineCommunicationByte = 0x41)))
        assertFalse(CaptPrinterRecovery.isPaperOut(paper.copy(paperAvailabilityByte = 0x80)))
    }
    @Test fun cancelBeforeJobInfoStartReleasesOnlyItsOwnValidReservation() {
        val commands = ArrayList<Int>()
        CaptPrinterRecovery.cancelOwned({ cmd, bytes ->
            commands += cmd
            assertArrayEquals(byteArrayOf(0x34, 0x12), bytes)
            CaptFrame.encode(cmd, byteArrayOf(0, 0))
        }, 0x1234, false)
        assertEquals(listOf(P.RELEASE_UNIT), commands)
        assertThrows(IllegalArgumentException::class.java) {
            CaptPrinterRecovery.cancelOwned({ _, _ -> error("No USB") }, 0, true)
        }
    }
}
