package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.CaptTestPage

/**
 * Offline deterministic 4x4 ordered grayscale dither into CAPT test grid's
 * MSB-first 1bpp line layout. Absolute y keeps the pattern aligned across
 * 64-line bands. No USB / JNI calls are made here.
 */
object MonochromeBands {
    private const val MAX_PIXELS = CaptTestPage.WIDTH * CaptTestPage.BAND_LINES
    private val BAYER4 = intArrayOf(
         0,  8,  2, 10,
        12,  4, 14,  6,
         3, 11,  1,  9,
        15,  7, 13,  5
    )

    fun packArgb(
        pixels: IntArray, width: Int, startY: Int, lines: Int,
        paperHeight: Int = CaptTestPage.HEIGHT
    ): ByteArray {
        require(width in 8..CaptTestPage.WIDTH && width % 8 == 0)
        require(paperHeight in 1..CaptTestPage.HEIGHT)
        require(lines in 1..CaptTestPage.BAND_LINES &&
            startY >= 0 && startY.toLong() + lines <= paperHeight)
        val count = width.toLong() * lines
        require(count <= MAX_PIXELS && pixels.size == count.toInt())
        val lineBytes = width / 8
        val output = ByteArray(lineBytes * lines) // paper is white: unset bit
        for (line in 0 until lines) {
            for (x in 0 until width) {
                val color = pixels[line * width + x]
                val alpha = color ushr 24
                val r = color ushr 16 and 0xff
                val g = color ushr 8 and 0xff
                val b = color and 0xff
                val luma = (299 * r + 587 * g + 114 * b + 500) / 1000
                val onWhite = (luma * alpha + 255 * (255 - alpha) + 127) / 255
                val threshold = BAYER4[((startY + line) and 3) * 4 + (x and 3)] * 16 + 8
                if (onWhite < threshold) {
                    val index = line * lineBytes + (x ushr 3)
                    output[index] = (output[index].toInt() or
                        (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return output
    }
}
