package com.cryonum.captusbprint.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.core.CaptTestPage
import java.util.concurrent.CancellationException
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

data class PreviewDimensions(val width: Int, val height: Int)

/** Legacy standalone sizing helper retained for its existing pure tests. */
object PreviewGeometry {
    fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): PreviewDimensions {
        require(width > 0 && height > 0 && maxWidth in 1..1600 && maxHeight in 1..2200)
        val bounded = PreviewSizing.fit(width, height)
        val scale = minOf(1.0, maxWidth.toDouble() / width,
            maxHeight.toDouble() / height,
            bounded.first.toDouble() / width, bounded.second.toDouble() / height)
        return PreviewDimensions(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1)
        )
    }
}

/**
 * One validated private local source. UI owns and closes this on replacement
 * or Activity destruction. Display previews use the shared A4 placement; only
 * the requested PDF page or a sampled image is ever decoded.
 *
 * This does NOT yet produce CAPT bands or guarantee a measured printable area.
 */
class DocumentSession internal constructor(
    private val source: File,
    val overview: DocumentOverview
) : Closeable {
    @Volatile private var closed = false

    fun renderPreview(
        pageIndex: Int,
        maxWidth: Int,
        maxHeight: Int,
        orientation: Orientation = Orientation.PORTRAIT,
        contentRotationDegrees: Int = 0
    ): Bitmap {
        check(!closed) { "Document preview source already closed" }
        require(pageIndex in 0 until overview.pageCount)
        require(maxWidth in 1..1600 && maxHeight in 1..2200)
        require(contentRotationDegrees == 0 || contentRotationDegrees == 90)
        return when (overview.format) {
            DocumentFormat.PDF -> renderPdf(pageIndex, maxWidth, maxHeight, orientation, contentRotationDegrees)
            DocumentFormat.JPEG, DocumentFormat.PNG -> renderImage(maxWidth, maxHeight, orientation, contentRotationDegrees)
        }
    }

    private fun renderPdf(index: Int, width: Int, height: Int,
                          orientation: Orientation, rotation: Int): Bitmap {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
        return renderer.use { pdf ->
            require(index < pdf.pageCount) { "PDF page index out of range" }
            pdf.openPage(index).use { page ->
                val layout = A4PageGeometry.fit(page.width, page.height, orientation, rotation)
                    .preview(width, height)
                val bitmap = Bitmap.createBitmap(layout.width, layout.height,
                    Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val target = layout.content.asRectF()
                    val transform = sourceToDestination(page.width, page.height, target, rotation)
                    val clip = Rect(
                        floor(target.left).toInt().coerceAtLeast(0),
                        floor(target.top).toInt().coerceAtLeast(0),
                        ceil(target.right).toInt().coerceAtMost(layout.width),
                        ceil(target.bottom).toInt().coerceAtMost(layout.height)
                    )
                    page.render(bitmap, clip, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Exception) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    }

    private fun renderImage(maxWidth: Int, maxHeight: Int,
                            orientation: Orientation, rotation: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth in 1..50000 && bounds.outHeight in 1..50000) {
            "Invalid preview image bounds"
        }
        // Cap both axes and total decoded pixels; never materialize full photos.
        val sample = PreviewSizing.sampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
            ?: throw IOException("Image decoder returned no preview")
        val oriented = try {
            applyExifOrientation(decoded)
        } catch (error: Exception) {
            decoded.recycle()
            throw error
        }
        if (oriented !== decoded) decoded.recycle()
        try {
            // Fit the decoded, EXIF-corrected image into the same physical A4
            // coordinates later used by the print rasterizer. White paper also
            // correctly composites PNG transparency.
            val layout = A4PageGeometry.fit(overview.width, overview.height, orientation, rotation)
                .preview(maxWidth, maxHeight)
            val paper = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(paper)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(oriented,
                    sourceToDestination(oriented.width, oriented.height,
                        layout.content.asRectF(), rotation), Paint(Paint.FILTER_BITMAP_FLAG))
                return paper
            } catch (error: Exception) {
                paper.recycle()
                throw error
            }
        } finally {
            oriented.recycle()
        }
    }


    /**
     * Offline Stage C PDF prototype: one PRINT-mode PDF page, 64 rows at a
     * time, delivered as MSB-first 1bpp A4 portrait bands. No USB/HISCOA and
     * never retains a full 600-dpi ARGB page. Consumer must not retain or
     * mutate a shared bitmap (only a fresh packed ByteArray is delivered).
     * Physical landscape-to-CAPT mapping remains intentionally unavailable.
     */
    fun forEachPortraitPdfBand(
        pageIndex: Int,
        rotation: Int = 0,
        cancelled: () -> Boolean = { false },
        consumer: (startLine: Int, packedBand: ByteArray) -> Unit
    ) {
        check(!closed) { "Document source closed" }
        require(overview.format == DocumentFormat.PDF) { "Only PDF is supported by this offline band prototype" }
        require(pageIndex in 0 until overview.pageCount && (rotation == 0 || rotation == 90))
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
        renderer.use { pdf ->
            pdf.openPage(pageIndex).use { page ->
                val placement = A4PageGeometry.fit(page.width, page.height,
                    Orientation.PORTRAIT, rotation)
                val width = CaptTestPage.WIDTH
                val height = CaptTestPage.HEIGHT
                val band = Bitmap.createBitmap(width, CaptTestPage.BAND_LINES, Bitmap.Config.ARGB_8888)
                val pixels = IntArray(width * CaptTestPage.BAND_LINES)
                try {
                    var y = 0
                    while (y < height) {
                        if (cancelled() || Thread.currentThread().isInterrupted) {
                            throw CancellationException("Offline PDF preparation cancelled")
                        }
                        val lines = minOf(CaptTestPage.BAND_LINES, height - y)
                        band.eraseColor(Color.WHITE)
                        val target = RectF(
                            placement.content.left.toFloat(),
                            (placement.content.top - y).toFloat(),
                            placement.content.right.toFloat(),
                            (placement.content.bottom - y).toFloat()
                        )
                        val transform = sourceToDestination(page.width, page.height, target, rotation)
                        page.render(band, Rect(0, 0, width, lines), transform,
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        band.getPixels(pixels, 0, width, 0, 0, width, lines)
                        val currentPixels = if (lines == CaptTestPage.BAND_LINES) pixels
                                            else pixels.copyOf(width * lines)
                        consumer(y, MonochromeBands.packArgb(currentPixels, width, y, lines))
                        y += lines
                    }
                } finally {
                    band.recycle()
                }
            }
        }
    }

    /** Affine source-to-A4 mapping, identical for PDF and decoded image content.
     * 90 degrees clockwise: (x,y) -> (H-y,x), then FIT into target.
     */
    private fun sourceToDestination(sourceWidth: Int, sourceHeight: Int,
                                    target: RectF, rotation: Int): Matrix {
        require(sourceWidth > 0 && sourceHeight > 0)
        val values = if (rotation == 90) {
            floatArrayOf(0f, -target.width() / sourceHeight, target.right,
                target.height() / sourceWidth, 0f, target.top, 0f, 0f, 1f)
        } else {
            require(rotation == 0)
            floatArrayOf(target.width() / sourceWidth, 0f, target.left,
                0f, target.height() / sourceHeight, target.top, 0f, 0f, 1f)
        }
        return Matrix().apply { setValues(values) }
    }

    private fun PageBox.asRectF() = RectF(
        left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()
    )

    private fun applyExifOrientation(bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.setRotate(90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.setRotate(270f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            transform, true)
    }

    override fun close() {
        closed = true
        source.delete()
    }
}
