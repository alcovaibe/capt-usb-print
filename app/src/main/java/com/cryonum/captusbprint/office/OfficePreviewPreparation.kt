package com.cryonum.captusbprint.office

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.net.Uri
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.PrivateDocumentImport
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

internal data class OfficePreviewMetadata(val kind: OfficeKind, val sheets: List<String>,
                                          val orientations: List<Orientation>) {
    val pageCount get() = orientations.size
    val sheetsOnly get() = kind.spreadsheet && orientations.isEmpty()
}

private data class OfficePreviewRequest(val options: OfficeOptions, val sheetsOnly: Boolean = false)
private class OfficePreviewResult(val sheets: List<String>, val document: OfficePreparedDocument? = null) : Closeable {
    override fun close() { document?.close() }
}

/** One owner of source -> prepared PDF -> preview/held queue copy. Never activates USB. */
internal class OfficePreviewPreparation(
    context: Context,
    private val kind: OfficeKind,
    private val sourceDirectory: File,
    private val source: () -> java.io.InputStream,
    private val service: Class<out OfficeWorkerService> = OfficeWorkerService::class.java,
    private val sourceUri: Uri? = null,
    listener: (LatestPreparation.State<OfficePreviewMetadata, OfficeFailure>) -> Unit
) : Closeable {
    private val app = context.applicationContext
    private val input = File(sourceDirectory, "source.bin")
    private val delivery = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private val core = LatestPreparation<OfficePreviewRequest, OfficePreviewResult, OfficePreviewMetadata, OfficeFailure>(
        delivery,
        { request, cancelled -> prepareDocument(request) { cancelled.asBoolean } },
        { ready -> OfficePreviewMetadata(kind, Collections.unmodifiableList(ready.sheets.toList()),
            ready.document?.session?.preparedPdfLayout?.orientations ?: emptyList()) },
        { error -> when (error) {
            is CancellationException -> OfficeFailure.CANCELLED
            is LatestPreparation.CleanupFailure -> OfficeFailure.RESOURCE_OBSERVATION
            else -> OfficeWorkerProtocol.failure(error)
        } },
        { listener(it) },
        { check(OfficePrivateFiles.deleteTree(sourceDirectory)) }
    )
    private fun prepareDocument(request: OfficePreviewRequest, cancelled: () -> Boolean): OfficePreviewResult {
        val options = request.options
        check(options.kind == kind)
        if (!input.exists()) {
            check(sourceDirectory.isDirectory || sourceDirectory.mkdirs())
            try {
                if (sourceUri != null) {
                    OfficeWorkerClient.importSource(app, sourceUri, cancelled).use { imported ->
                        if (imported.importedKind != kind)
                            throw OfficeConversionFailure(OfficeFailure.UNSUPPORTED_DOCUMENT)
                        java.nio.file.Files.move(imported.file.toPath(), input.toPath())
                    }
                } else {
                    // Fixed bundled assets only. External providers must use sourceUri above.
                    val copy = PrivateDocumentImport.copy(sourceDirectory, { source() },
                        { cancelled() }, { sourceDirectory.usableSpace })
                    check(copy.renameTo(input))
                }
            } catch (error: Throwable) {
                if (!OfficePrivateFiles.deleteTree(input))
                    throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION)
                throw error
            }
        }
        // BUSY is reported before backend work, so bounded retry cannot replay conversion/printing.
        val until = android.os.SystemClock.elapsedRealtime() + 8000
        while (true) {
            if (cancelled()) throw CancellationException()
            try {
                if (request.sheetsOnly) return OfficePreviewResult(
                    OfficeWorkerClient.inspectSheets(app, input, kind, cancelled, serviceClass = service))
                val document = OfficeWorkerClient.prepare(app, input, options, cancelled, serviceClass = service)
                return OfficePreviewResult(document.sheetNames, document)
            } catch (error: OfficeConversionFailure) {
                if (error.reason != OfficeFailure.BUSY || android.os.SystemClock.elapsedRealtime() >= until) throw error
                try { Thread.sleep(25) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); throw CancellationException()
                }
            }
        }
    }
    fun inspectSheets(): Long {
        require(kind.spreadsheet)
        return core.replace(OfficePreviewRequest(OfficeOptions(kind), sheetsOnly = true))
    }
    fun prepare(selected: List<Int>?, fitWidth: Boolean): Long =
        core.replace(OfficePreviewRequest(OfficeOptions(kind, selected, fitWidth)))
    val version get() = core.version()
    fun cancel() = core.cancel()
    fun render(version: Long, page: Int, cancelled: () -> Boolean,
               success: (Bitmap) -> Unit, failure: (OfficeFailure) -> Unit): Boolean =
        core.read(version, { ready, stale ->
            val resources = OfficeResourceMonitor(app)
            val stopped = { stale.asBoolean || cancelled() }
            resources.before(0, stopped)
            val bitmap = requireNotNull(ready.document).session.renderPreparedPrintPreview(page) {
                resources.check(0, stopped); stopped()
            }
            try { resources.check(0, stopped, force = true); bitmap }
            catch (error: Throwable) { bitmap.recycle(); throw error }
        },
            { success(it) }, { failure(it) }, { it.recycle() })

    /** Exact reviewed version/range/copies. Returns a HELD common-queue job; caller confirms activation. */
    fun enqueueHeld(version: Long, range: String, copies: Int, device: String,
                    success: (Long) -> Unit, failure: (OfficeFailure) -> Unit): Boolean =
        core.read(version, { ready, stale ->
            val session = requireNotNull(ready.document).session
            val plan = JobSpecification.preparedPdf(requireNotNull(session.preparedPdfLayout).orientations, range, copies)
            if (stale.asBoolean) throw CancellationException()
            val resources = OfficeResourceMonitor(app)
            resources.before(session.overview.copiedBytes + 256L * 1024 * 1024) { stale.asBoolean }
            val id = PrintQueueRuntime.enqueueChecked(app, session, plan, device) {
                resources.check(0, { stale.asBoolean })
            }
            if (stale.asBoolean) { PrintQueueRuntime.cancel(id); throw CancellationException() }
            id
        }, { success(it) }, { failure(it) }, { PrintQueueRuntime.cancel(it) })
    override fun close() = core.close()
}
