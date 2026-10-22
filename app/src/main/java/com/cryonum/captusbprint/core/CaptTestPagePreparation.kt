package com.cryonum.captusbprint.core

import java.security.MessageDigest

data class CaptPreparedTestPage(
    val widthPixels: Int,
    val heightPixels: Int,
    val bands: Int,
    val rawBytes: Long,
    val compressedBytes: Long,
    val maxBandBytes: Int,
    val compressedSha256: String,
    val descriptorBytes: Int
)

/**
 * An offline Stage B codec test. Compresses exactly one A4 test page using
 * upstream GPL HISCOA; neither USB access nor any printer commands are sent.
 */
object CaptTestPagePreparation {
    fun prepare(): CaptPreparedTestPage {
        val sha = MessageDigest.getInstance("SHA-256")
        var compressedBytes = 0L
        var rawBytes = 0L
        var maxBand = 0
        var n = 0
        var start = 0
        while (start < CaptTestPage.HEIGHT) {
            val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - start)
            val raster = CaptTestPage.band(start, lines)
            val compressed = HiscoaNative.encodeBand(raster, CaptTestPage.LINE_BYTES,
                lines, start + lines == CaptTestPage.HEIGHT)
            require(compressed.isNotEmpty() && compressed.size <= raster.size * 2 + 64)
            sha.update(compressed)
            rawBytes += raster.size
            compressedBytes += compressed.size
            maxBand = maxOf(maxBand, compressed.size)
            n++
            start += lines
        }
        return CaptPreparedTestPage(CaptTestPage.WIDTH, CaptTestPage.HEIGHT, n,
            rawBytes, compressedBytes, maxBand,
            sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            CaptPageProtocol.beginA4TestPage().size)
    }
}
