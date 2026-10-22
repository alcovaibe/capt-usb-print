package com.cryonum.captusbprint.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException

data class PreviewDimensions(val width: Int, val height: Int)

/** Pure, testable fit calculation: no page image is ever decoded at print DPI. */
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
 * One validated private local source. UI owns this session and closes it on
 * replacement or Activity destruction. No USB/CAPT, network, or export.
 * Only the requested PDF page (or a sampled image) is decoded at a time.
 */
class DocumentSession internal constructor(
    private val source: File,
    val overview: DocumentOverview
) : Closeable {
    @Volatile private var closed = false

    fun renderPreview(pageIndex: Int, maxWidth: Int, maxHeight: Int): Bitmap {
        check(!closed) { "Document preview source already closed" }
        require(pageIndex in 0 until overview.pageCount)
        require(maxWidth in 1..1600 && maxHeight in 1..2200)
        return when (overview.format) {
            DocumentFormat.PDF -> renderPdf(pageIndex, maxWidth, maxHeight)
            DocumentFormat.JPEG, DocumentFormat.PNG -> renderImage(maxWidth, maxHeight)
        }
    }

    private fun renderPdf(index: Int, width: Int, height: Int): Bitmap {
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
                val fitted = PreviewGeometry.fit(page.width, page.height, width, height)
                val bitmap = Bitmap.createBitmap(fitted.width, fitted.height,
                    Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Exception) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    }

    private fun renderImage(maxWidth: Int, maxHeight: Int): Bitmap {
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
        val size = PreviewGeometry.fit(oriented.width, oriented.height, maxWidth, maxHeight)
        if (size.width == oriented.width && size.height == oriented.height) return oriented
        return try {
            Bitmap.createScaledBitmap(oriented, size.width, size.height, true).also {
                if (it !== oriented) oriented.recycle()
            }
        } catch (error: Exception) {
            oriented.recycle()
            throw error
        }
    }

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
