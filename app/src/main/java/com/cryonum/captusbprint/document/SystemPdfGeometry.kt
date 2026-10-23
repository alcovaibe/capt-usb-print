package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.Orientation
import kotlin.math.abs

/** Android supplies a laid-out A4 PDF in 1/72-inch points. Preserve scale, margins and placement. */
internal object SystemPdfGeometry {
    fun matrix(width: Int, height: Int, orientation: Orientation, stripY: Int): FloatArray {
        require(stripY in 0 until 7016)
        val landscape = orientation == Orientation.LANDSCAPE
        require(abs(width - if (landscape) 842 else 595) <= 2 &&
            abs(height - if (landscape) 595 else 842) <= 2) { "SYSTEM_PDF_MEDIA_MISMATCH" }
        val scale = 600f / 72f
        return if (landscape) floatArrayOf(0f, -scale, 4960f,
            scale, 0f, -stripY.toFloat(), 0f, 0f, 1f)
        else floatArrayOf(scale, 0f, 0f, 0f, scale, -stripY.toFloat(), 0f, 0f, 1f)
    }
}
