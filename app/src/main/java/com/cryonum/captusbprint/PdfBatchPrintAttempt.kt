package com.cryonum.captusbprint

import android.content.Context
import android.util.AtomicFile
import com.cryonum.captusbprint.core.DiagnosticJson
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.DocumentFormat
import com.cryonum.captusbprint.document.DocumentSession
import com.cryonum.captusbprint.document.PreparedPdfCaptPage
import com.cryonum.captusbprint.document.PreparedPdfCaptPageBuilder
import com.cryonum.captusbprint.usb.CaptPdfBatchPrintEngine
import com.cryonum.captusbprint.usb.CaptTestPrintResult
import java.io.FileOutputStream
import java.util.concurrent.CancellationException

/**
 * Bounded experimental two/three PDF-sheet attempt. All distinct selected
 * sheets are fully prepared before opening USB (each <=4 MiB compressed).
 * Copies may safely reference the same immutable PreparedPdfCaptPage object.
 * The marker and PDF entrypoint lock are shared with the one-sheet flow.
 */
internal object PdfBatchPrintAttempt {
    fun execute(
        context: Context,
        source: DocumentSession,
        plan: JobSpecification,
        deviceKey: String,
        cancelled: () -> Boolean,
        progress: (String) -> Unit
    ): String {
        val lease = PdfHardwareAttemptLock.tryAcquire()
            ?: return "Другая PDF-попытка уже выполняется. Новое задание не запущено."
        try {
            val order = plan.outputPageOrder.toList()
            require(source.overview.format == DocumentFormat.PDF &&
                plan.orientation == Orientation.PORTRAIT &&
                order.size in 2..3 &&
                order.all { it in 0 until source.overview.pageCount }) {
                "Invalid experimental PDF batch"
            }
            fun persist(
                phase: String, result: CaptTestPrintResult? = null,
                failure: String? = null
            ) {
                val report = linkedMapOf<String, Any?>(
                    "schemaVersion" to 1,
                    "generatedAtMillis" to System.currentTimeMillis(),
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "sourceRevision" to BuildConfig.SOURCE_REVISION,
                    "operation" to "explicit_two_to_three_portrait_pdf_sheets",
                    "phase" to phase,
                    "sourcePageCount" to source.overview.pageCount,
                    "outputSourcePageNumbers" to order.map { it + 1 },
                    "requestedPages" to order.size,
                    "copies" to plan.copies,
                    "paper" to "A4",
                    "orientation" to "portrait",
                    "contentRotationDegrees" to plan.contentRotationDegrees,
                    "printerVidPid" to "04A9:2676",
                    "result" to result?.let {
                        linkedMapOf<String, Any?>(
                            "outcome" to it.outcome,
                            "stage" to it.stage,
                            "sentPrintDataBytes" to it.sentPrintDataBytes,
                            "printerConfirmedPages" to it.printerConfirmedPages,
                            "lastStatusFrameHex" to it.lastStatusFrameHex,
                            "trace" to it.trace
                        )
                    },
                    "failureClass" to failure,
                    "automaticRetry" to false,
                    "note" to "No automatic replay, even after partial counter confirmation; a stale USB marker is uncertain."
                )
                // A successful durable marker is REQUIRED before USB traffic.
                val atomic = AtomicFile(FirstPdfPrintAttempt.reportFile(context))
                var stream: FileOutputStream? = null
                try {
                    stream = atomic.startWrite()
                    stream.write((DiagnosticJson.stringify(report) + "\n").toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (error: Exception) {
                    if (stream != null) atomic.failWrite(stream)
                    throw IllegalStateException("Could not persist PDF batch evidence", error)
                }
            }
            var usbStarted = false
            var lastObserved: CaptTestPrintResult? = null
            return try {
                persist("PREPARING")
                val cache = mutableMapOf<Int, PreparedPdfCaptPage>()
                val prepared = ArrayList<PreparedPdfCaptPage>(order.size)
                for ((index, pageIndex) in order.withIndex()) {
                    if (cancelled() || Thread.currentThread().isInterrupted) {
                        throw CancellationException("Cancelled before PDF batch USB attempt")
                    }
                    val sheet = cache[pageIndex] ?: PreparedPdfCaptPageBuilder.prepare(
                        produceBands = { accept ->
                            source.forEachPortraitPdfBand(pageIndex, plan.contentRotationDegrees,
                                cancelled, accept)
                        },
                        encode = { pixels, lineBytes, lines, last ->
                            HiscoaNative.encodeBand(pixels, lineBytes, lines, last)
                        },
                        cancelled = cancelled
                    ).also { cache[pageIndex] = it }
                    prepared.add(sheet)
                    progress("Подготовлено " + (index + 1) + "/" + order.size +
                        " PDF-листов. USB пока не задействован.")
                }
                if (cancelled() || Thread.currentThread().isInterrupted) {
                    throw CancellationException("Cancelled before USB")
                }
                persist("USB_ATTEMPT_STARTED")
                usbStarted = true
                val result = CaptPdfBatchPrintEngine.run(context, deviceKey, prepared,
                    cancelled, progress, checkpoint = { observed ->
                        lastObserved = observed
                        persist("USB_ATTEMPT_STARTED", observed)
                    })
                lastObserved = result
                persist(when (result.outcome) {
                    "printer_counter_confirmed" -> "PRINTER_COUNTER_CONFIRMED"
                    "failed_before_print_commands" -> "FAILED_BEFORE_PRINT"
                    else -> "OUTCOME_UNKNOWN"
                }, result)
                result.summary + " Отчёт записан. Без автоматического повтора."
            } catch (error: Throwable) {
                val saved = runCatching {
                    persist(if (usbStarted) "OUTCOME_UNKNOWN" else "PREPARATION_FAILED",
                        result = lastObserved, failure = error.javaClass.simpleName)
                }.isSuccess
                val reportText = if (saved) "JSON сохранён." else "Не удалось сохранить JSON."
                if (usbStarted) "Многостраничное задание прервано; возможна частичная печать. " +
                    "Не повторяй его без проверки выданных листов. " + reportText
                else "Подготовка прервана: " + error.javaClass.simpleName +
                    ". Команды печати не отправлялись. " + reportText
            }
        } finally {
            lease.close()
        }
    }
}

