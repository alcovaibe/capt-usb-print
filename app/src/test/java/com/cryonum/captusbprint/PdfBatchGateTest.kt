package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.DocumentFormat
import com.cryonum.captusbprint.document.PdfBatchGate
import org.junit.Assert.*
import org.junit.Test

class PdfBatchGateTest {
    private fun spec(range: String, copies: Int = 1, orientation: Orientation = Orientation.PORTRAIT) =
        JobSpecification.create(11, range, copies, orientation)

    @Test fun acceptsTwoOrThreePagesAndCollatedCopies() {
        assertNull(PdfBatchGate.rejection(DocumentFormat.PDF, spec("1-3"), 0))
        assertNull(PdfBatchGate.rejection(DocumentFormat.PDF, spec("3,1"), 2))
        assertEquals(listOf(2,0), spec("3,1").outputPageOrder)
        assertNull(PdfBatchGate.rejection(DocumentFormat.PDF, spec("2", copies = 2), 1))
        assertEquals(listOf(1,1), spec("2", copies = 2).outputPageOrder)
        assertNull(PdfBatchGate.rejection(DocumentFormat.PDF, spec("2", copies = 3), 1))
    }

    @Test fun failsClosedOnUnsupportedOrUnexpectedPlan() {
        assertNotNull(PdfBatchGate.rejection(DocumentFormat.PDF, spec("1"), 0))
        assertNotNull(PdfBatchGate.rejection(DocumentFormat.PDF, spec("1-4"), 0))
        assertNotNull(PdfBatchGate.rejection(DocumentFormat.PDF, spec("1-3"), 1))
        assertNotNull(PdfBatchGate.rejection(DocumentFormat.JPEG, spec("1-2"), 0))
        assertNotNull(PdfBatchGate.rejection(DocumentFormat.PDF,
            spec("1-2", orientation = Orientation.LANDSCAPE), 0))
    }
}
