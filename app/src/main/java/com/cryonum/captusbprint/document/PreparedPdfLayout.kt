package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.Orientation
import java.util.Collections
import kotlin.math.abs

/** Final A4 page orientations, captured once from the validated private PDF. No reflow. */
class PreparedPdfLayout private constructor(val orientations: List<Orientation>) {
    companion object {
        fun fromPageSizes(sizes: List<Pair<Int, Int>>): PreparedPdfLayout {
            require(sizes.size in 1..1000) { "PREPARED_PDF_PAGE_LIMIT" }
            return PreparedPdfLayout(Collections.unmodifiableList(sizes.map { (width, height) ->
                when {
                    abs(width.toLong() - 595) <= 2 && abs(height.toLong() - 842) <= 2 -> Orientation.PORTRAIT
                    abs(width.toLong() - 842) <= 2 && abs(height.toLong() - 595) <= 2 -> Orientation.LANDSCAPE
                    else -> throw IllegalArgumentException("PREPARED_PDF_MEDIA_MISMATCH")
                }
            }))
        }
    }
}
