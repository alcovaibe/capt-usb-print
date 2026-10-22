package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.core.Orientation
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Automatic selection is based on the first inspected page, not on phone rotation. */
enum class PageOrientationChoice { AUTO, PORTRAIT, LANDSCAPE;
    fun resolve(firstWidth: Int, firstHeight: Int): Orientation {
        require(firstWidth > 0 && firstHeight > 0)
        return when (this) {
            AUTO -> if (firstWidth > firstHeight) Orientation.LANDSCAPE else Orientation.PORTRAIT
            PORTRAIT -> Orientation.PORTRAIT
            LANDSCAPE -> Orientation.LANDSCAPE
        }
    }
}

/** Coordinates are in the owner-tested A4/600-dpi grid; landscape-to-CAPT wire
 * rotation is a separate, still-unverified print-adapter task. */
data class PageBox(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right: Double get() = left + width
    val bottom: Double get() = top + height
}

data class PreviewSheet(val width: Int, val height: Int, val content: PageBox)

data class SheetPlacement(
    val paperWidth: Int,
    val paperHeight: Int,
    val content: PageBox,
    val orientation: Orientation,
    val contentRotationDegrees: Int = 0
) {
    /** Scale the SAME physical placement to display resolution, not a separate fit. */
    fun preview(maxWidth: Int, maxHeight: Int): PreviewSheet {
        require(maxWidth in 1..PreviewSizing.MAX_EDGE && maxHeight in 1..2200)
        val factor = minOf(
            maxWidth.toDouble() / paperWidth,
            maxHeight.toDouble() / paperHeight,
            sqrt(PreviewSizing.MAX_PIXELS.toDouble() /
                (paperWidth.toDouble() * paperHeight))
        )
        val width = floor(paperWidth * factor).toInt().coerceAtLeast(1)
        val height = floor(paperHeight * factor).toInt().coerceAtLeast(1)
        // Match the integer-sized display sheet on each axis. Error is < 1 px.
        val sx = width.toDouble() / paperWidth
        val sy = height.toDouble() / paperHeight
        return PreviewSheet(width, height, PageBox(
            content.left * sx, content.top * sy, content.width * sx, content.height * sy
        ))
    }
}

/** First, deliberately conservative FIT layout. No 100%-scale claim.
 * Inset 260 px (~11 mm at 600 dpi) is a PROVISIONAL software margin chosen
 * from the successful registration test's first marks, not a measured printer
 * hardware margin or a claim of guaranteed printable area.
 */
object A4PageGeometry {
    const val PORTRAIT_WIDTH = CaptTestPage.WIDTH
    const val PORTRAIT_HEIGHT = CaptTestPage.HEIGHT
    const val PROVISIONAL_INSET_PX = 260

    fun fit(
        sourceWidth: Int,
        sourceHeight: Int,
        orientation: Orientation,
        contentRotationDegrees: Int = 0
    ): SheetPlacement {
        require(sourceWidth in 1..50_000 && sourceHeight in 1..50_000) {
            "Invalid source dimensions"
        }
        require(contentRotationDegrees == 0 || contentRotationDegrees == 90) {
            "Only no-rotation or clockwise 90-degree content rotation is supported"
        }
        val rotatedWidth = if (contentRotationDegrees == 90) sourceHeight else sourceWidth
        val rotatedHeight = if (contentRotationDegrees == 90) sourceWidth else sourceHeight
        val width = if (orientation == Orientation.PORTRAIT) PORTRAIT_WIDTH else PORTRAIT_HEIGHT
        val height = if (orientation == Orientation.PORTRAIT) PORTRAIT_HEIGHT else PORTRAIT_WIDTH
        val availableWidth = width - 2 * PROVISIONAL_INSET_PX
        val availableHeight = height - 2 * PROVISIONAL_INSET_PX
        val factor = min(availableWidth.toDouble() / rotatedWidth,
            availableHeight.toDouble() / rotatedHeight)
        val placedWidth = rotatedWidth * factor
        val placedHeight = rotatedHeight * factor
        return SheetPlacement(width, height, PageBox(
            (width - placedWidth) / 2.0, (height - placedHeight) / 2.0,
            placedWidth, placedHeight
        ), orientation, contentRotationDegrees)
    }
}
