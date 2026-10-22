package com.cryonum.captusbprint.document

import kotlin.math.sqrt

/** Bounded, display-quality preview; never allocates a full 600-dpi print raster. */
object PreviewSizing {
    const val MAX_EDGE = 1600
    const val MAX_PIXELS = 2_400_000L

    fun fit(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(
            1.0,
            MAX_EDGE.toDouble() / width,
            MAX_EDGE.toDouble() / height,
            sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height))
        )
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        var sample = 1
        while (((width.toLong() + sample - 1) / sample > MAX_EDGE ||
                    (height.toLong() + sample - 1) / sample > MAX_EDGE ||
                    ((width.toLong() + sample - 1) / sample) *
                    ((height.toLong() + sample - 1) / sample) > MAX_PIXELS) &&
            sample <= 1 shl 28) {
            sample *= 2
        }
        return sample
    }
}
