package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.DocumentFormat
import com.cryonum.captusbprint.document.FirstPdfPrintGate
import org.junit.Assert.*
import org.junit.Test

class FirstPdfPrintGateTest {
    private fun spec(range: String, copies: Int = 1, orientation: Orientation = Orientation.PORTRAIT) =
        JobSpecification.create(11, range, copies, orientation)

    @Test fun allowsOnlyExactlyTheVisiblePdfSheet() {
        assertNull(FirstPdfPrintGate.rejection(DocumentFormat.PDF, spec("2"), 1))
        assertNull(FirstPdfPrintGate.rejection(DocumentFormat.PDF,
            JobSpecification.create(1, "", 1), 0))
        assertNotNull(FirstPdfPrintGate.rejection(DocumentFormat.PDF, spec("1"), 1))
        assertNotNull(FirstPdfPrintGate.rejection(DocumentFormat.PDF, spec("1, 2"), 0))
        assertNotNull(FirstPdfPrintGate.rejection(DocumentFormat.PDF, spec(""), 0))
        assertNotNull(FirstPdfPrintGate.rejection(DocumentFormat.PDF, spec("2", 2), 1))
    }

    @Test fun rejectsLandscapeAndImagesBeforeAnyUsbOperation() {
        assertNotNull(FirstPdfPrintGate.rejection(DocumentFormat.PDF,
            spec("1", orientation = Orientation.LANDSCAPE), 0))
        assertNotNull(FirstPdfPrintGate.rejection(DocumentFormat.PNG, spec("1"), 0))
        assertNotNull(FirstPdfPrintGate.rejection(DocumentFormat.JPEG, spec("1"), 0))
    }
}
