package com.cryonum.captusbprint

import com.cryonum.captusbprint.document.PreviewGeometry
import com.cryonum.captusbprint.document.PreviewSizing
import org.junit.Assert.*
import org.junit.Test

class StageCPreviewSizingTest {
    @Test fun largePagesAndImagesStayWithinDisplayMemoryBudget() {
        val sizes = listOf(4960 to 7016, 50000 to 3000, 3000 to 50000,
            50000 to 50000, 1 to 50000, 1 to 1, 595 to 842)
        sizes.forEach { (width, height) ->
            val (w, h) = PreviewSizing.fit(width, height)
            assertTrue(w in 1..PreviewSizing.MAX_EDGE)
            assertTrue(h in 1..PreviewSizing.MAX_EDGE)
            assertTrue(w.toLong() * h <= PreviewSizing.MAX_PIXELS)
            val sample = PreviewSizing.sampleSize(width, height)
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
            val decodedWidth = (width + sample - 1) / sample
            val decodedHeight = (height + sample - 1) / sample
            assertTrue(decodedWidth.toLong() * decodedHeight <= PreviewSizing.MAX_PIXELS)
        }
    }

    @Test fun portraitAndLandscapeRetainAspectAndDoNotUpscale() {
        assertEquals(PreviewSizing.fit(595, 842), 595 to 842)
        assertEquals(PreviewSizing.fit(842, 595), 842 to 595)
        assertEquals(PreviewGeometry.fit(595, 842, 1000, 1450).width, 595)
        assertEquals(PreviewGeometry.fit(595, 842, 1000, 1450).height, 842)
        val fitted = PreviewGeometry.fit(3000, 1500, 1000, 1450)
        assertEquals(1000, fitted.width)
        assertEquals(500, fitted.height)
    }

    @Test fun invalidPageDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PreviewSizing.fit(0, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            PreviewGeometry.fit(595, 842, 0, 1450)
        }
    }
}
