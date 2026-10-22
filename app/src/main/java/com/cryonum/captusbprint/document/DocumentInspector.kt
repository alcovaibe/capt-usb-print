package com.cryonum.captusbprint.document

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Actual dimensions of a PDF page in points, or an image in oriented pixels. */
data class DocumentPageSize(val width: Int, val height: Int)

data class DocumentOverview(
    val format: DocumentFormat,
    val pageCount: Int,
    val width: Int,
    val height: Int,
    val dimensionsUnit: String,
    val rotationDegrees: Int?,
    val copiedBytes: Long,
    val pageSizes: List<DocumentPageSize> = emptyList()
)

/**
 * Stage C intake only. Opens user-granted content URIs and reads document
 * metadata on a worker thread. Does not rasterize, submit CAPT or share data.
 * A bounded temporary copy permits non-seekable providers with PdfRenderer.
 */
object DocumentInspector {
    const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L
    private const val MAX_DIMENSION = 50_000
    private const val MAX_IMAGE_PIXELS = 150_000_000L

    fun inspect(context: Context, uri: Uri): DocumentOverview {
        require(uri.scheme == "content") { "Only user-granted content URI is supported" }
        val reportedSize = context.contentResolver.query(uri,
            arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        require(reportedSize == null || reportedSize in 0..MAX_SOURCE_BYTES) {
            "Document exceeds the inspection size limit"
        }

        val directory = File(context.cacheDir, "stage-c-inspection").apply {
            if (!exists() && !mkdirs()) throw IOException("Private temp directory unavailable")
        }
        val temp = File.createTempFile("incoming-", ".bin", directory)
        try {
            val available = StatFs(directory.absolutePath).availableBytes
            require(available >= 8L * 1024L * 1024L) { "Insufficient local space" }
            var total = 0L
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Document provider supplied no data")
            source.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw IOException("Inspection interrupted")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        require(total + n <= MAX_SOURCE_BYTES) {
                            "Document exceeds the inspection size limit"
                        }
                        output.write(buffer, 0, n)
                        total += n
                    }
                }
            }
            require(total > 0) { "Empty document" }
            val header = ByteArray(8)
            val read = FileInputStream(temp).use { it.read(header) }
            val format = DocumentSignature.detect(header.copyOf(maxOf(0, read)))
                ?: throw IllegalArgumentException("Unsupported or invalid PDF/JPEG/PNG")
            return when (format) {
                DocumentFormat.PDF -> inspectPdf(temp, total)
                DocumentFormat.JPEG, DocumentFormat.PNG -> inspectImage(temp, format, total)
            }
        } finally {
            temp.delete()
        }
    }

    private fun inspectPdf(file: File, bytes: Long): DocumentOverview {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (failure: Exception) {
            descriptor.close()
            throw failure
        }
        return renderer.use { pdf ->
            require(pdf.pageCount in 1..1000) { "Invalid or unsupported PDF page count" }
            // PdfRenderer permits only one open page at a time. Never rasterize
            // or allocate a full-page bitmap during this metadata pass.
            val sizes = (0 until pdf.pageCount).map { index ->
                pdf.openPage(index).use { page ->
                    require(page.width in 1..100_000 && page.height in 1..100_000) {
                        "Unsupported PDF page dimensions"
                    }
                    DocumentPageSize(page.width, page.height)
                }
            }
            val first = sizes.first()
            DocumentOverview(DocumentFormat.PDF, pdf.pageCount, first.width,
                first.height, "points", null, bytes, sizes)
        }
    }

    private fun inspectImage(file: File, format: DocumentFormat, bytes: Long): DocumentOverview {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth in 1..MAX_DIMENSION && bounds.outHeight in 1..MAX_DIMENSION &&
            bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) {
            "Invalid or oversized image dimensions"
        }
        val exif = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        val degrees = when (exif) {
            ExifInterface.ORIENTATION_NORMAL -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> null
        }
        val swap = exif in setOf(ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE)
        return DocumentOverview(format, 1,
            if (swap) bounds.outHeight else bounds.outWidth,
            if (swap) bounds.outWidth else bounds.outHeight,
            "pixels", degrees, bytes,
            listOf(DocumentPageSize(
                if (swap) bounds.outHeight else bounds.outWidth,
                if (swap) bounds.outWidth else bounds.outHeight)))
    }
}
