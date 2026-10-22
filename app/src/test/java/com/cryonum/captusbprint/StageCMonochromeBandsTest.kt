package com.cryonum.captusbprint

import com.cryonum.captusbprint.document.MonochromeBands
import org.junit.Assert.*
import org.junit.Test

class StageCMonochromeBandsTest {
    @Test fun blackAndWhiteAreMspFirstAndTransparentIsPaperWhite() {
        val pixels = intArrayOf(
            0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt(), 0xffffffff.toInt(),
            0x00000000, 0xff000000.toInt(), 0xffffffff.toInt(), 0xff000000.toInt()
        )
        val result = MonochromeBands.packArgb(pixels, 8, 0, 1)
        assertEquals(1, result.size)
        assertEquals(0b10100101, result[0].toInt() and 0xff)
    }

    @Test fun solidWhiteIsBlankAndBlackIsAllSet() {
        assertArrayEquals(byteArrayOf(0, 0),
            MonochromeBands.packArgb(IntArray(16) { -1 }, 8, 0, 2))
        assertArrayEquals(byteArrayOf(-1, -1),
            MonochromeBands.packArgb(IntArray(16) { 0xff000000.toInt() }, 8, 0, 2))
    }

    @Test fun bandsHaveIdenticalDitherAsWholePage() {
        val pixels = IntArray(8 * 8) { 0xff808080.toInt() }
        val whole = MonochromeBands.packArgb(pixels, 8, 0, 8)
        val first = MonochromeBands.packArgb(pixels.copyOfRange(0, 8 * 3), 8, 0, 3)
        val second = MonochromeBands.packArgb(pixels.copyOfRange(8 * 3, 8 * 8), 8, 3, 5)
        assertArrayEquals(whole, first + second)
        assertTrue(whole.any { it.toInt() != 0 })
        assertTrue(whole.any { (it.toInt() and 0xff) != 255 })
    }

    @Test fun rejectsInvalidBoundsOrPixelBudget() {
        assertThrows(IllegalArgumentException::class.java) {
            MonochromeBands.packArgb(IntArray(8), 7, 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonochromeBands.packArgb(IntArray(8), 8, 7016, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonochromeBands.packArgb(IntArray(8), 8, 0, 65)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonochromeBands.packArgb(IntArray(7), 8, 0, 1)
        }
    }
}
