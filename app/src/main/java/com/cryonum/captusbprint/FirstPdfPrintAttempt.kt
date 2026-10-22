package com.cryonum.captusbprint

import android.content.Context
import android.util.AtomicFile
import com.cryonum.captusbprint.core.DiagnosticJson
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.document.DocumentSession
import com.cryonum.captusbprint.document.PreparedPdfCaptPageBuilder
import com.cryonum.captusbprint.usb.CaptPdfPrintEngine
import com.cryonum.captusbprint.usb.CaptTestPrintResult
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single explicit first-PDF hardware attempt. The private marker survives an
 * Activity/process interruption and never triggers resume or automatic retry.
 * Document source/path/name/URI and any raster or payload are excluded.
 */
internal object FirstPdfPrintAttempt {
    private val active = AtomicBoolean(false)
    fun reportFile(context: Context): File =
        File(context.filesDir, "capt-pdf-print-latest.json")

    fun execute(
        context: Context, source: DocumentSession, pageIndex: Int,
        rotation: Int, deviceKey: String, cancelled: () -> Boolean,
        progress: (String) -> Unit
    ): String {
        // A re-created Activity must not overwrite an active attempt's private
        // marker while another one-page operation is still underway.
        if (!active.compareAndSet(false, true)) {
            return "Другая PDF-попытка ещё активна. Новый сеанс не запущен; предыдущий отчёт сохранён."
        }
        try {
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

            // Unlike ordinary historical diagnostics, the USB-start marker is
            // a HARD precondition: a failed disk write must prevent printing.
            val atomic = AtomicFile(reportFile(context))
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write((DiagnosticJson.stringify(record) + "\n").toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                if (stream != null) atomic.failWrite(stream)
                throw IllegalStateException("Could not persist first-PDF print evidence", error)
            }
        }
        var started = false
        return try {
            persist("PREPARING")
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
            val reportSaved = runCatching {
                persist(if (started) "OUTCOME_UNKNOWN" else "PREPARATION_FAILED",
                    failure = error.javaClass.simpleName)
            }.isSuccess
            val reportState = if (reportSaved) "JSON сохранён локально." else
                "Не удалось сохранить JSON-отчёт; проверь свободное место."
            if (started) "Попытка прервана: исход печати неизвестен. Проверь принтер перед повтором. $reportState"
            else "Подготовка не завершена (${error.javaClass.simpleName}); команды печати не отправлялись. $reportState"
        }
        } finally {
            active.set(false)
        }
    }
}
