package com.cryonum.captusbprint.office

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
}

/** One owner of source -> prepared PDF -> preview/held queue copy. Never activates USB. */
internal class OfficePreviewPreparation(
    context: Context,
    private val kind: OfficeKind,
    private val sourceDirectory: File,
    private val source: () -> java.io.InputStream,
    private val service: Class<out OfficeWorkerService> = OfficeWorkerService::class.java,
    listener: (LatestPreparation.State<OfficePreviewMetadata, OfficeFailure>) -> Unit
) : Closeable {
    private val app = context.applicationContext
    private val input = File(sourceDirectory, "source.bin")
    private val delivery = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private val core = LatestPreparation<OfficeOptions, OfficePreparedDocument, OfficePreviewMetadata, OfficeFailure>(
        delivery,
        { options, cancelled -> prepareDocument(options) { cancelled.asBoolean } },
        { ready -> OfficePreviewMetadata(kind, Collections.unmodifiableList(ready.sheetNames.toList()),
            requireNotNull(ready.session.preparedPdfLayout).orientations) },
        { error -> when (error) {
            is CancellationException -> OfficeFailure.CANCELLED
            is LatestPreparation.CleanupFailure -> OfficeFailure.RESOURCE_OBSERVATION
            else -> OfficeWorkerProtocol.failure(error)
        } },
        { listener(it) },
        { check(OfficePrivateFiles.deleteTree(sourceDirectory)) }
    )
    private fun prepareDocument(options: OfficeOptions, cancelled: () -> Boolean): OfficePreparedDocument {
        check(options.kind == kind)
        if (!input.exists()) {
            check(sourceDirectory.isDirectory || sourceDirectory.mkdirs())
            val copy = PrivateDocumentImport.copy(sourceDirectory, { source() },
                { cancelled() }, { sourceDirectory.usableSpace })
            check(copy.renameTo(input))
        }
        // BUSY is reported before backend work, so bounded retry cannot replay conversion/printing.
        val until = android.os.SystemClock.elapsedRealtime() + 8000
        while (true) {
            if (cancelled()) throw CancellationException()
            try {
                return OfficeWorkerClient.prepare(app, input, options, cancelled, serviceClass = service)
            } catch (error: OfficeConversionFailure) {
                if (error.reason != OfficeFailure.BUSY || android.os.SystemClock.elapsedRealtime() >= until) throw error
                try { Thread.sleep(25) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); throw CancellationException()
                }
            }
        }
    }
    fun prepare(selected: List<Int>?, fitWidth: Boolean): Long = core.replace(OfficeOptions(kind, selected, fitWidth))
    val version get() = core.version()
    fun cancel() = core.cancel()
    fun render(version: Long, page: Int, cancelled: () -> Boolean,
               success: (Bitmap) -> Unit, failure: (OfficeFailure) -> Unit): Boolean =
        core.read(version, { ready, stale -> ready.session.renderPreparedPrintPreview(page) { stale.asBoolean || cancelled() } },
            { success(it) }, { failure(it) }, { it.recycle() })

    /** Exact reviewed version/range/copies. Returns a HELD common-queue job; caller confirms activation. */
    fun enqueueHeld(version: Long, range: String, copies: Int, device: String,
                    success: (Long) -> Unit, failure: (OfficeFailure) -> Unit): Boolean =
        core.read(version, { ready, stale ->
            val session = ready.session
            val plan = JobSpecification.preparedPdf(requireNotNull(session.preparedPdfLayout).orientations, range, copies)
            if (stale.asBoolean) throw CancellationException()
            val id = PrintQueueRuntime.enqueue(app, session, plan, device)
            if (stale.asBoolean) { PrintQueueRuntime.cancel(id); throw CancellationException() }
            id
        }, { success(it) }, { failure(it) }, { PrintQueueRuntime.cancel(it) })
    override fun close() = core.close()
}
