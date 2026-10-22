package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptBasicStatusParser
import com.cryonum.captusbprint.core.PdfBatchPrinterReadiness
import org.junit.Assert.*
import org.junit.Test

class PdfBatchPrinterReadinessTest {
    @Test fun capturedWedgeReplyFailsBeforeReserveUnit() {
        val captured = byteArrayOf(0xA0.toByte(), 0xE0.toByte(), 6, 0,
            0x88.toByte(), 0)
        val basic = CaptBasicStatusParser.parse(captured)
        assertEquals(0x88, basic.statusByte)
        val message = PdfBatchPrinterReadiness.rejection(basic.statusByte)
        assertNotNull(message)
        assertTrue(message!!.contains("0x88"))
        assertTrue(message.contains("ReserveUnit"))
    }

    @Test fun idleAndOfflineOnlyAreNotMisdiagnosedAsWedge() {
        assertNull(PdfBatchPrinterReadiness.rejection(0x00))
        assertNull(PdfBatchPrinterReadiness.rejection(0x01))
        assertNull(PdfBatchPrinterReadiness.rejection(0x10))
        assertNull(PdfBatchPrinterReadiness.rejection(0x41))
        for (bit in listOf(0x02, 0x04, 0x08, 0x80, 0x88)) {
            assertNotNull(PdfBatchPrinterReadiness.rejection(bit))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PdfBatchPrinterReadiness.rejection(256)
        }
    }
}
