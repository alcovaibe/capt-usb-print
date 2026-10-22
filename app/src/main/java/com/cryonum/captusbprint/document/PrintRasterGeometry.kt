package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.Orientation

/** Affine source -> logical A4 -> physical portrait CAPT strip. No protocol change. */
internal object PrintRasterGeometry {
    fun matrix(width: Int, height: Int, orientation: Orientation, rotation: Int, stripY: Int): FloatArray {
        require(stripY in 0 until A4PageGeometry.PORTRAIT_HEIGHT)
        val p = A4PageGeometry.fit(width, height, orientation, rotation).content
        val a: Double; val b: Double; val c: Double
        val d: Double; val e: Double; val f: Double
        if (rotation == 90) {
            a = 0.0; b = -p.width / height; c = p.right
            d = p.height / width; e = 0.0; f = p.top
        } else {
            a = p.width / width; b = 0.0; c = p.left
            d = 0.0; e = p.height / height; f = p.top
        }
        return if (orientation == Orientation.PORTRAIT)
            floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(), e.toFloat(),
                (f - stripY).toFloat(), 0f, 0f, 1f)
        else floatArrayOf((-d).toFloat(), (-e).toFloat(),
            (A4PageGeometry.PORTRAIT_WIDTH - f).toFloat(), a.toFloat(), b.toFloat(),
            (c - stripY).toFloat(), 0f, 0f, 1f)
    }

    const val MAX_IMAGE_PIXELS = 4_000_000L
    fun sampleSize(width: Int, height: Int): Int {
        require(width in 1..50000 && height in 1..50000)
        var sample = 1
        while (((width.toLong() + sample - 1) / sample) *
                ((height.toLong() + sample - 1) / sample) > MAX_IMAGE_PIXELS) sample *= 2
        return sample
    }
}
