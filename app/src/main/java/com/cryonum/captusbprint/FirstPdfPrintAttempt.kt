package com.cryonum.captusbprint

import android.content.Context
import com.cryonum.captusbprint.core.DiagnosticJson
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.document.DocumentSession
import com.cryonum.captusbprint.document.PreparedPdfCaptPageBuilder
import com.cryonum.captusbprint.usb.CaptPdfPrintEngine
import com.cryonum.captusbprint.usb.CaptTestPrintResult
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Single explicit first-PDF hardware attempt. The private marker survives an
 * Activity/process interruption and never triggers resume or automatic retry.
 * Document source/path/name/URI and any raster or payload are excluded.
 */
internal object FirstPdfPrintAttempt {
    fun reportFile(context: Context): File =
        File(context.filesDir, "capt-pdf-print-latest.json")

    fun execute(
        context: Context, source: DocumentSession, pageIndex: Int,
        rotation: Int, deviceKey: String, cancelled: () -> Boolean,
        progress: (String) -> Unit
    ): String {
        val count = source.overview.pageCount
        fun persist(phase: String, result: CaptTestPrintResult? = null, failure: String? = null) {
            val record = linkedMapOf<String, Any?>(
                "schemaVersion" to 1,
                "generatedAtMillis" to System.currentTimeMillis(),
                "appVersion" to BuildConfig.VERSION_NAME,
                "sourceRevision" to BuildConfig.SOURCE_REVISION,
                "operation" to "explicit_one_page_portrait_pdf",
                "phase" to phase,
                "pageNumber" to pageIndex + 1,
                "sourcePageCount" to count,
                "copies" to 1, "paper" to "A4", "orientation" to "portrait",
                "contentRotationDegrees" to rotation,
                "printerVidPid" to "04A9:2676",
                "result" to result?.let {
                    linkedMapOf<String, Any?>(
                        "outcome" to it.outcome, "stage" to it.stage,
                        "sentPrintDataBytes" to it.sentPrintDataBytes,
                        "printerConfirmedPages" to it.printerConfirmedPages,
                        "lastStatusFrameHex" to it.lastStatusFrameHex,
                        "trace" to it.trace
                    )
                },
                "failureClass" to failure,
                "automaticRetry" to false,
                "note" to "Unfinished USB_ATTEMPT_STARTED means unknown outcome after process interruption; CAPT counters are not physical visual proof."
            )
            DiagnosticHistory(reportFile(context)).save(DiagnosticJson.stringify(record) + "\n")
        }
        persist("PREPARING")
        var started = false
        return try {
            val prepared = PreparedPdfCaptPageBuilder.prepare(
                produceBands = { accept ->
                    source.forEachPortraitPdfBand(pageIndex, rotation, cancelled, accept)
                },
                encode = { bytes, stride, lines, last ->
                    HiscoaNative.encodeBand(bytes, stride, lines, last)
                },
                cancelled = cancelled
            )
            if (cancelled()) throw CancellationException("Cancelled before USB attempt")
            // Crash-safe conservative marker BEFORE potentially stateful CAPT I/O.
            persist("USB_ATTEMPT_STARTED")
            started = true
            val result = CaptPdfPrintEngine.run(context, deviceKey, prepared, cancelled, progress)
            persist(when (result.outcome) {
                "printer_counter_confirmed" -> "PRINTER_COUNTER_CONFIRMED"
                "failed_before_print_commands" -> "FAILED_BEFORE_PRINT"
                else -> "OUTCOME_UNKNOWN"
            }, result)
            result.summary + " Последний JSON сохранён. Автоматического повтора нет."
        } catch (error: Throwable) {
            persist(if (started) "OUTCOME_UNKNOWN" else "PREPARATION_FAILED",
                failure = error.javaClass.simpleName)
            if (started) "Попытка прервана: исход печати неизвестен. Проверь принтер перед повтором."
            else "Подготовка не завершена (${error.javaClass.simpleName}); команды печати не отправлялись."
        }
    }
}
