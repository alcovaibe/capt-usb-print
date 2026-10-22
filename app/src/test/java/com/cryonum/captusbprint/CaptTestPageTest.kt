package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.CaptFrame
import com.cryonum.captusbprint.core.CaptPageProtocol
import com.cryonum.captusbprint.core.CaptTestPage
import org.junit.Assert.*
import org.junit.Test

class CaptTestPageTest {
    @Test fun boundedBandsCoverEntireA4Canvas() {
        var y = 0
        var bands = 0
        while (y < CaptTestPage.HEIGHT) {
            val count = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - y)
            val raster = CaptTestPage.band(y, count)
            assertEquals(count * 620, raster.size)
            bands++
            y += count
        }
        assertEquals(7016, y)
        assertEquals(110, bands)
    }

    @Test fun marksAreDeterministicAndAsymmetric() {
        assertTrue(CaptTestPage.band(256, 64).any { it.toInt() != 0 })
        assertTrue(CaptTestPage.band(2000, 64).all { it.toInt() == 0 })
        assertArrayEquals(CaptTestPage.band(384, 64), CaptTestPage.band(384, 64))
    }

    @Test fun secondPageIsDistinctWithoutChangingFirstSheetOrRasterBounds() {
        // Existing photo-verified page 1 remains the default for single-page USB.
        assertArrayEquals(CaptTestPage.band(2080, 64),
            CaptTestPage.band(2080, 64, pageNumber = 1))
        assertTrue(CaptTestPage.band(2080, 64, pageNumber = 1).all { it == 0.toByte() })
        val secondBar = CaptTestPage.band(2080, 64, pageNumber = 2)
        assertEquals(64 * CaptTestPage.LINE_BYTES, secondBar.size)
        assertTrue(secondBar.any { it != 0.toByte() })
        assertArrayEquals(CaptTestPage.band(260, 64),
            CaptTestPage.band(260, 64, pageNumber = 2)) // shared alignment target
        assertFalse(CaptTestPage.band(2240, 64, pageNumber = 2)
            .contentEquals(CaptTestPage.band(2240, 64)))
        assertThrows(IllegalArgumentException::class.java) {
            CaptTestPage.band(2080, 64, pageNumber = 3)
        }
    }

    @Test fun pageDescriptorHasExpectedMultiCommandAndDimensions() {
        val frame = CaptPageProtocol.beginA4TestPage()
        assertEquals(0xA9, frame[0].toInt() and 0xff)
        assertEquals(0xD0, frame[1].toInt() and 0xff)
        assertEquals(68, frame.size)
        val begin = frame.copyOfRange(8, 48)
        assertEquals(0x02, begin[4].toInt() and 0xff) // A4
        assertEquals(620, (begin[26].toInt() and 0xff) or
            ((begin[27].toInt() and 0xff) shl 8))
        assertEquals(7016, (begin[28].toInt() and 0xff) or
            ((begin[29].toInt() and 0xff) shl 8))
        assertEquals(8, CaptPageProtocol.hiscoaParameters().size)
    }

    @Test fun videoDataIsSplitIntoBoundedCAPTPackets() {
        val packets = CaptPageProtocol.videoData(ByteArray(200), 64)
        assertEquals(listOf(68, 68, 68, 12), packets.map { it.size })
        assertEquals(0xA0, packets[0][0].toInt() and 0xff)
        assertEquals(0xC0, packets[0][1].toInt() and 0xff)
        assertThrows(IllegalArgumentException::class.java) {
            CaptPageProtocol.videoData(ByteArray(1), 0)
        }
    }
}
