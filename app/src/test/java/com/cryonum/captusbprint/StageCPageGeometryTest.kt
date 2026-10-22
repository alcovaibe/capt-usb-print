package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.A4PageGeometry
import com.cryonum.captusbprint.document.PreviewSizing
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StageCPageGeometryTest {
    @Test fun portraitUsesPhysicallyConfirmedCaptRasterSize() {
        val layout = A4PageGeometry.fit(595, 842, Orientation.PORTRAIT)
        assertEquals(CaptTestPage.WIDTH, layout.paperWidth)
        assertEquals(CaptTestPage.HEIGHT, layout.paperHeight)
        assertEquals(260.0, layout.content.left, 0.01)
        assertTrue(layout.content.top >= 260.0)
        assertTrue(layout.content.right <= layout.paperWidth - 260.0 + 0.01)
        assertTrue(layout.content.bottom <= layout.paperHeight - 260.0 + 0.01)
    }

    @Test fun landscapeSwapsLogicalPaperButDoesNotAlterCaptWireRaster() {
        val layout = A4PageGeometry.fit(842, 595, Orientation.LANDSCAPE)
        assertEquals(CaptTestPage.HEIGHT, layout.paperWidth)
        assertEquals(CaptTestPage.WIDTH, layout.paperHeight)
        assertEquals(CaptTestPage.WIDTH, A4PageGeometry.PORTRAIT_WIDTH)
        assertEquals(CaptTestPage.HEIGHT, A4PageGeometry.PORTRAIT_HEIGHT)
        assertEquals(260.0, layout.content.top, 0.01)
    }

    @Test fun fitPreservesAspectAndKeepsAllContentWithinProvisionalInset() {
        for (orientation in Orientation.entries) {
            for ((w, h) in listOf(595 to 842, 842 to 595, 12000 to 2000,
                    2000 to 12000, 1 to 1, 50000 to 1, 1 to 50000)) {
                val page = A4PageGeometry.fit(w, h, orientation)
                assertEquals(w.toDouble() / h, page.content.width / page.content.height,
                    w.toDouble() / h * 0.000001 + 0.000001)
                assertTrue(page.content.left >= 260.0 - 0.001)
                assertTrue(page.content.top >= 260.0 - 0.001)
                assertTrue(page.content.right <= page.paperWidth - 260.0 + 0.001)
                assertTrue(page.content.bottom <= page.paperHeight - 260.0 + 0.001)
            }
        }
    }

    @Test fun previewIsScaledVersionOfPhysicalPlacementAndBounded() {
        for (orientation in Orientation.entries) {
            val page = A4PageGeometry.fit(595, 842, orientation)
            val small = page.preview(1000, 1450)
            val sx = small.width.toDouble() / page.paperWidth
            val sy = small.height.toDouble() / page.paperHeight
            assertEquals(page.content.left * sx, small.content.left, 0.001)
            assertEquals(page.content.top * sy, small.content.top, 0.001)
            assertEquals(page.content.width * sx, small.content.width, 0.001)
            assertEquals(page.content.height * sy, small.content.height, 0.001)
            assertTrue(small.width in 1..1000)
            assertTrue(small.height in 1..1450)
            assertTrue(small.width.toLong() * small.height <= PreviewSizing.MAX_PIXELS)
            assertTrue(small.content.left >= 0 && small.content.right <= small.width + 0.001)
            assertTrue(small.content.top >= 0 && small.content.bottom <= small.height + 0.001)
        }
        val largest = A4PageGeometry.fit(595, 842, Orientation.PORTRAIT).preview(1600, 2200)
        assertTrue(largest.width.toLong() * largest.height <= PreviewSizing.MAX_PIXELS)
        assertTrue(abs(largest.width.toDouble() / largest.height -
            CaptTestPage.WIDTH.toDouble() / CaptTestPage.HEIGHT) < 0.002)
    }

    @Test fun invalidInputsFailWithoutAllocatingImages() {
        assertThrows(IllegalArgumentException::class.java) {
            A4PageGeometry.fit(0, 842, Orientation.PORTRAIT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            A4PageGeometry.fit(50001, 842, Orientation.PORTRAIT)
        }
        val page = A4PageGeometry.fit(595, 842, Orientation.PORTRAIT)
        assertThrows(IllegalArgumentException::class.java) { page.preview(0, 1000) }
        assertThrows(IllegalArgumentException::class.java) { page.preview(1601, 1000) }
    }
}
