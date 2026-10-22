package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.SystemPdfGeometry
import org.junit.Assert.*
import org.junit.Test

class StageDSystemGeometryTest {
    @Test fun portraitPreservesPdfPointScaleAndOriginWithoutFitOrMargin() {
        val m = SystemPdfGeometry.matrix(595,842,Orientation.PORTRAIT,128)
        assertEquals(600f/72f, m[0], 0.0001f)
        assertEquals(600f/72f, m[4], 0.0001f)
        assertEquals(0f,m[2],0f)
        assertEquals(-128f,m[5],0f)
        assertEquals(600f, m[0]*72f+m[2], 0.001f)
    }
    @Test fun landscapeOnlyRotatesLogicalPaperIntoAcceptedPortraitCaptGrid() {
        val m = SystemPdfGeometry.matrix(842,595,Orientation.LANDSCAPE,64)
        assertEquals(0f,m[0],0f)
        assertEquals(-600f/72f,m[1],0.0001f)
        assertEquals(4960f,m[2],0f)
        assertEquals(600f/72f,m[3],0.0001f)
        assertEquals(-64f,m[5],0f)
    }
    @Test fun roundingToleranceDoesNotRescaleTheDocument() {
        assertArrayEquals(SystemPdfGeometry.matrix(595,842,Orientation.PORTRAIT,0),
            SystemPdfGeometry.matrix(596,841,Orientation.PORTRAIT,0), 0f)
    }
    @Test(expected = IllegalArgumentException::class)
    fun nonA4PdfIsRejectedBeforeUsb() { SystemPdfGeometry.matrix(612,792,Orientation.PORTRAIT,0) }
}
