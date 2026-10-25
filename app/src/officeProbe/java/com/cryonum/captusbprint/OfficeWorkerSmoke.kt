package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import com.cryonum.captusbprint.office.OfficeConversionFailure
import com.cryonum.captusbprint.office.OfficeKind
import com.cryonum.captusbprint.office.OfficeOptions
import com.cryonum.captusbprint.office.OfficeWorkerClient
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.document.StoredCaptPage
import com.cryonum.captusbprint.core.HiscoaNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real native conversion via Binder in disposable :office processes; synthetic assets only. */
class OfficeWorkerSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val context = targetContext
        val result = Bundle()
        var outcome = Activity.RESULT_CANCELED
        var stage = "start"
        val source = File(context.cacheDir, "office-worker-source.bin")
        val spool = File(context.cacheDir, "office-worker-spool.capt")
        val root = File(context.noBackupFilesDir, "office-work")
        val baseline = root.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val report = File(context.filesDir, "office-worker-results.json")
        val observations = JSONArray()
        val pids = mutableSetOf<Int>()
        try {
            check(!report.exists() || report.delete())
            for (kind in OfficeKind.entries) {
                stage = "worker-${kind.extension}"
                context.assets.open("office-fixtures/${if (kind.spreadsheet) "workbook" else "word"}.${kind.extension}").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                val options = OfficeOptions(kind, if (kind.spreadsheet) listOf(2, 0) else null)
                OfficeWorkerClient.prepare(context, source, options).use { prepared ->
                    check(prepared.workerPid != Process.myPid() && pids.add(prepared.workerPid))
                    check(prepared.peakObservedRssBytes > 0)
                    check((root.listFiles()?.map { it.name }?.toSet() ?: emptySet()) == baseline)
                    val session = prepared.session
                    check(session.overview.pageCount == if (kind.spreadsheet) 7 else 3)
                    check(prepared.sheetNames == if (kind.spreadsheet) listOf("Сводка", "Широкая", "Длинная") else emptyList<String>())
                    val layout = requireNotNull(session.preparedPdfLayout)
                    val plan = JobSpecification.preparedPdf(layout.orientations, "2-3", 2)
                    check(plan.outputPageOrder == listOf(1, 2, 1, 2))
                    val preview = session.renderPreparedPrintPreview(0) { false }
                    try {
                        val pixels = IntArray(preview.width * preview.height)
                        preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                        check(pixels.any { it == android.graphics.Color.BLACK })
                    } finally { preview.recycle() }
                    StoredCaptPage.write(spool,
                        produce = { accept -> session.forEachPrintBand(0, layout.orientations[0], 0, { false }, accept) },
                        encode = { bytes, stride, lines, last -> HiscoaNative.encodeBand(bytes, stride, lines, last) },
                        cancelled = { false }, reserve = {}).validate { false }
                    observations.put(JSONObject().put("format", kind.extension)
                        .put("selectedSheets", if (kind.spreadsheet) "1+3" else "all")
                        .put("pdfPages", session.overview.pageCount)
                        .put("peakObservedRssBytes", prepared.peakObservedRssBytes))
                }
            }
            report.writeText(JSONObject().put("syntheticOnly", true).put("hardwarePrintTest", false)
                .put("workerProcesses", pids.size).put("conversions", observations).toString())
            result.putString("stream", "CAPT_OFFICE_WORKER_CONVERSION_PASSED (synthetic, no USB)")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "OFFICE_WORKER_CONVERSION_FAILED:$stage:${(error as? OfficeConversionFailure)?.reason?.name ?: error.javaClass.simpleName}")
        } finally { source.delete(); spool.delete() }
        finish(outcome, result)
    }
}
