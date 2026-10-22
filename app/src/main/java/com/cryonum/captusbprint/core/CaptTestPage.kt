package com.cryonum.captusbprint.core

/**
 * Stage B: deterministic monochrome test page in the printer's 600-dpi grid.
 * This is NOT an Image/PDF renderer. It emits one bounded band at a time.
 * Dimensions correspond to an A4 raster rounded to the nearest 8-pixel width.
 */
object CaptTestPage {
    const val WIDTH = 4960
    const val HEIGHT = 7016
    const val LINE_BYTES = WIDTH / 8
    const val BAND_LINES = 64

    /** Page 1 is bit-identical to the owner's confirmed physical sheet. Page 2
     * adds distinct registration bars to detect duplicated or shifted sheets.
     * This is only offline multi-page preparation; it does not send USB jobs.
     */
    fun band(startLine: Int, lines: Int, pageNumber: Int = 1): ByteArray {
        require(pageNumber in 1..2) { "Only two Stage B test patterns are defined" }
        require(startLine in 0 until HEIGHT && lines in 1..BAND_LINES)
        require(startLine + lines <= HEIGHT)
        val result = ByteArray(LINE_BYTES * lines)
        for (row in 0 until lines) {
            val y = startLine + row
            // A small registration target plus asymmetric top/left corner marks.
            // Deliberately stays well away from the unverified physical margins.
            val vertical = (y in 260..1450) || (y in 1720..1880)
            if (vertical) {
                horizontal(result, row, 260, 266)
                if (y in 260..380 || y in 1720..1880) horizontal(result, row, 266, 1500)
            }
            if (y in 440..460) horizontal(result, row, 260, 1000)
            if (y in 700..710) horizontal(result, row, 260, 1200)
            if (y in 980..990) horizontal(result, row, 260, 800)
            if (y in 1220..1230) horizontal(result, row, 260, 650)
            // Right-side short mark identifies page orientation after printing.
            if (y in 380..410) horizontal(result, row, 4080, 4490)
            // Two bars distinguish the second physical sheet; the identical
            // corner target on both sheets makes positional drift visible.
            if (pageNumber == 2) {
                if (y in 2080..2140) horizontal(result, row, 300, 1300)
                if (y in 2240..2300) horizontal(result, row, 300, 1300)
            }
        }
        return result
    }

    private fun horizontal(bytes: ByteArray, row: Int, first: Int, last: Int) {
        val base = row * LINE_BYTES
        for (x in first..last) {
            val p = base + (x ushr 3)
            bytes[p] = (bytes[p].toInt() or (0x80 ushr (x and 7))).toByte()
        }
    }
}

object CaptPageProtocol {
    private const val IC_BEGIN_PAGE = 0xD0A0
    private const val IC_BLACK_PLANE = 0xD0A4
    private const val IC_BEGIN_DATA = 0xD0A1
    private const val IC_END_PAGE = 0xD0A2

    fun hiscoaParameters(): ByteArray = byteArrayOf(1, 4, 1, 1, 0, (-7).toByte(), 0, 0)

    /**
     * A4 plain paper page descriptor follows the LBP2900 model path in
     * ValdikSS/captdriver at commit 892f320c019974a5b737e000fcc2c8582264a131.
     * PageSeq is zero for the initial single-page experiment.
     */
    fun beginA4TestPage(): ByteArray {
        val b = ByteArray(40)
        b[2] = 0x30
        b[3] = 0x2a
        b[4] = 0x02      // A4 tPaperSizeTbl
        b[8] = 0x1f
        b[9] = 0x1c
        b[10] = 0x1c
        b[11] = 0x1c
        b[12] = 0       // plain paper, special LBP2900 case
        b[13] = 0x11    // 600 dpi
        b[14] = 0x04
        b[16] = 0x01
        b[17] = 0x01
        b[18] = 0x02
        putLe16(b, 22, 0)                       // left margin
        putLe16(b, 24, 0)                       // top margin
        putLe16(b, 26, CaptTestPage.LINE_BYTES) // bytes per line
        putLe16(b, 28, CaptTestPage.HEIGHT)
        putLe16(b, 30, CaptTestPage.WIDTH)
        putLe16(b, 32, CaptTestPage.HEIGHT)
        b[36] = 0x01   // plain paper/media type
        return CaptFrame.encodeMulti(
            listOf(
                IC_BEGIN_PAGE to b,
                IC_BLACK_PLANE to hiscoaParameters(),
                IC_BEGIN_DATA to byteArrayOf(),
                IC_END_PAGE to byteArrayOf()
            )
        )
    }

    fun videoData(compressed: ByteArray, chunkLimit: Int): List<ByteArray> {
        require(chunkLimit in 1..0xFF00) { "Unvalidated printer transfer limit" }
        require(compressed.isNotEmpty() && compressed.size <= 256 * 1024)
        return compressed.asList().chunked(chunkLimit).map { part ->
            CaptFrame.encode(0xC0A0, part.toByteArray())
        }
    }

    private fun putLe16(bytes: ByteArray, index: Int, number: Int) {
        require(number in 0..65535)
        bytes[index] = number.toByte()
        bytes[index + 1] = (number ushr 8).toByte()
    }
}
