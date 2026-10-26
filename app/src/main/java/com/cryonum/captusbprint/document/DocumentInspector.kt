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
import java.util.concurrent.CancellationException

data class DocumentOverview(
    val format: DocumentFormat,
    val pageCount: Int,
    val width: Int,
    val height: Int,
    val dimensionsUnit: String,
    val rotationDegrees: Int?,
    val copiedBytes: Long
)

/**
 * Stage C intake only. Opens user-granted content URIs and reads document
 * metadata on a worker thread. Does not rasterize, submit CAPT or share data.
 * A bounded temporary copy permits non-seekable providers with PdfRenderer.
 */
object DocumentInspector {
    const val MAX_SOURCE_BYTES = PrivateDocumentImport.MAX_BYTES
    private const val MAX_DIMENSION = 50_000
    private const val MAX_IMAGE_PIXELS = 150_000_000L

    fun inspect(context: Context, uri: Uri): DocumentOverview =
        openSession(context, uri).use { it.overview }

    /**
     * Keep one validated, bounded private copy for page-by-page previews.
     * Caller must close the returned session when replacing or leaving a file.
     */
    fun openSession(context: Context, uri: Uri, cancelled: () -> Boolean = { false }): DocumentSession {
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        checkCancelled()
        require(uri.scheme == "content") { "Only user-granted content URI is supported" }
        val reportedSize = context.contentResolver.query(uri,
            arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        checkCancelled()
        if (reportedSize != null && reportedSize > MAX_SOURCE_BYTES)
            throw PrivateDocumentImport.Failure(PrivateDocumentImport.Reason.TOO_LARGE)

        val directory = File(context.cacheDir, "stage-c-inspection").apply {
            if (!exists() && !mkdirs()) throw IOException("Private temp directory unavailable")
        }
        val temp = PrivateDocumentImport.copy(directory,
            { context.contentResolver.openInputStream(uri) }, { cancelled() },
            { StatFs(directory.absolutePath).availableBytes })
        try {
            checkCancelled()
            val total = temp.length()
            val header = ByteArray(8)
            val read = FileInputStream(temp).use { it.read(header) }
            val format = DocumentSignature.detect(header.copyOf(maxOf(0, read)))
                ?: throw PrivateDocumentImport.Failure(PrivateDocumentImport.Reason.UNSUPPORTED)
            val overview = when (format) {
                DocumentFormat.PDF -> inspectPdf(temp, total)
                DocumentFormat.JPEG, DocumentFormat.PNG -> inspectImage(temp, format, total)
            }
            checkCancelled()
            return DocumentSession(temp, overview)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    internal fun inspectPdf(file: File, bytes: Long): DocumentOverview {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (failure: Exception) {
            descriptor.close()
            throw failure
        }
        return renderer.use { pdf ->
            require(pdf.pageCount in 1..1000) { "Invalid or unsupported PDF page count" }
            pdf.openPage(0).use { page ->
                DocumentOverview(DocumentFormat.PDF, pdf.pageCount, page.width,
                    page.height, "points", null, bytes)
            }
        }
    }

    /**
     * Backend handoff for a final private PDF. Copies bytes, validates EVERY A4 page,
     * and captures the layout before it can reach preview/queue. Does not convert Office.
     * The caller continues to own readyPdf; this session owns a separate bounded copy.
     */
    internal fun openPreparedPdf(context: Context, readyPdf: File,
                                 cancelled: () -> Boolean = { false }): DocumentSession =
        openPreparedPdfChecked(context, readyPdf, cancelled) {}

    internal fun openPreparedPdfChecked(context: Context, readyPdf: File,
                                        cancelled: () -> Boolean,
                                        checkpoint: () -> Unit): DocumentSession {
        checkpoint()
        val privateRoot = File(context.applicationInfo.dataDir).canonicalFile.toPath()
        require(readyPdf.canonicalFile.toPath().startsWith(privateRoot)) { "PRIVATE_PDF_REQUIRED" }
        val directory = File(context.cacheDir, "stage-c-inspection").apply {
            if (!exists() && !mkdirs()) throw IOException("Private temp directory unavailable")
        }
        val temp = PrivateDocumentImport.copy(directory, { readyPdf.inputStream() },
            { checkpoint(); cancelled() }, { StatFs(directory.absolutePath).availableBytes })
        var complete = false
        try {
            val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Throwable) {
                descriptor.close(); throw error
            }
            val sizes = renderer.use { pdf ->
                require(pdf.pageCount in 1..1000) { "PREPARED_PDF_PAGE_LIMIT" }
                (0 until pdf.pageCount).map { index ->
                    checkpoint()
                    if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
                    pdf.openPage(index).use { it.width to it.height }
                }
            }
            val layout = PreparedPdfLayout.fromPageSizes(sizes)
            checkpoint()
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            val overview = DocumentOverview(DocumentFormat.PDF, sizes.size, sizes[0].first,
                sizes[0].second, "points", null, temp.length())
            return DocumentSession(temp, overview, layout).also { complete = true }
        } finally { if (!complete) temp.delete() }
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
            "pixels", degrees, bytes)
    }
}

