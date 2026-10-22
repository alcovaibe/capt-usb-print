package com.cryonum.captusbprint

import com.cryonum.captusbprint.document.PreviewGeometry
import com.cryonum.captusbprint.document.PreviewSizing
import org.junit.Assert.*
import org.junit.Test

class StageCPreviewTest {
    @Test fun pdfPageFitsWithoutStretchingOrUpscaling() {
        val portrait = PreviewGeometry.fit(595, 842, 1000, 1450)
        assertEquals(595, portrait.width)
        assertEquals(842, portrait.height)
        val landscape = PreviewGeometry.fit(1190, 842, 1000, 1450)
        assertTrue(landscape.width <= 1000)
        assertTrue(landscape.height <= 1450)
        assertEquals(1190.0 / 842.0,
            landscape.width.toDouble() / landscape.height, 0.01)
    }

    @Test fun tallPdfAndLargeImageStayWithinMemoryBudget() {
        val fitted = PreviewSizing.fit(4960, 7016)
        assertTrue(fitted.first in 1..PreviewSizing.MAX_EDGE)
        assertTrue(fitted.second in 1..PreviewSizing.MAX_EDGE)
        assertTrue(fitted.first.toLong() * fitted.second <= PreviewSizing.MAX_PIXELS)
        val sample = PreviewSizing.sampleSize(12000, 12000)
        assertTrue(sample > 1)
        val width = (12000L + sample - 1) / sample
        assertTrue(width * width <= PreviewSizing.MAX_PIXELS)
        assertTrue(width <= PreviewSizing.MAX_EDGE)
    }

    @Test fun invalidGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PreviewSizing.fit(0, 800)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewSizing.sampleSize(-1, 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewGeometry.fit(600, 800, 0, 900)
        }
    }
}
