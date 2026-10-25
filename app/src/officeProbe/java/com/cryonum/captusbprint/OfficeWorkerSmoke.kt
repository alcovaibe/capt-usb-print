package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.cryonum.captusbprint.office.OfficeConversionFailure
import com.cryonum.captusbprint.office.OfficeKind
import com.cryonum.captusbprint.office.OfficeOptions
import com.cryonum.captusbprint.office.OfficeWorkerClient
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.StoredCaptPage
import com.cryonum.captusbprint.core.HiscoaNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Real native conversion via Binder in disposable :office processes; synthetic assets only. */
class OfficeWorkerSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        val context = targetContext
        val result = Bundle()
        var outcome = Activity.RESULT_CANCELED
        var stage = "start"
        val source = File(context.cacheDir, "office-worker-source.bin")
        val spool = File(context.cacheDir, "office-worker-spool.capt")
        val root = File(context.noBackupFilesDir, "office-work")
        val baseline = root.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val report = File(context.filesDir, "office-worker-results.json")
        val pdfEvidence = File(context.filesDir, "office-layout-smoke")
        val observations = JSONArray()
        val pids = mutableSetOf<Int>()
        try {
            check(!report.exists() || report.delete())
            check(!pdfEvidence.exists() || pdfEvidence.deleteRecursively())
            check(pdfEvidence.mkdir())
            fun convert(kind: OfficeKind, selected: List<Int>?, fitWidth: Boolean, expected: Int? = null): Int {
                stage = "${kind.extension}-${if (fitWidth) "fit" else "saved"}-${selected?.joinToString("-") ?: "all"}"
                val before = source.readBytes() // Fixed, small synthetic assets only.
                var count = 0
                OfficeWorkerClient.prepare(context, source, OfficeOptions(kind, selected, fitWidth)).use { prepared ->
                    check(prepared.workerPid != Process.myPid() && pids.add(prepared.workerPid))
                    val gone = try { Os.kill(prepared.workerPid, 0); false }
                    catch (error: ErrnoException) { error.errno == OsConstants.ESRCH }
                    check(gone)
                    check(prepared.peakObservedRssBytes > 0)
                    check((root.listFiles()?.map { it.name }?.toSet() ?: emptySet()) == baseline)
                    check(source.readBytes().contentEquals(before))
                    val session = prepared.session
                    count = session.overview.pageCount
                    if (expected != null) check(count == expected)
                    check(prepared.sheetNames == if (kind.spreadsheet) listOf("Сводка", "Широкая", "Длинная") else emptyList<String>())
                    val layout = requireNotNull(session.preparedPdfLayout)
                    if (!kind.spreadsheet) check(layout.orientations == listOf(
                        Orientation.PORTRAIT, Orientation.PORTRAIT, Orientation.LANDSCAPE))
                    // Synthetic evidence uses the same exact PDF-copy path as the real queue.
                    val label = "${kind.extension}-${if (fitWidth) "fit" else "saved"}-${selected?.sorted()?.joinToString("+") { (it + 1).toString() } ?: "all"}"
                    val copyDirectory = File(pdfEvidence, "queue-copy").apply { check(mkdir()) }
                    session.copyForPrint(copyDirectory) {}.use { copy ->
                        check(copy.overview == session.overview && copy.preparedPdfLayout === layout)
                        File(copyDirectory, "source.bin").copyTo(File(pdfEvidence, "$label.pdf"))
                    }
                    check(copyDirectory.delete())
                    val range = if (count >= 3) "2-3" else "1"
                    val plan = JobSpecification.preparedPdf(layout.orientations, range, 2)
                    check(plan.outputPageOrder == if (count >= 3) listOf(1, 2, 1, 2) else listOf(0, 0))
                    // First and last prepared pages remain usable after the worker/profile are gone.
                    for (index in listOf(0, count - 1).distinct()) {
                        val preview = session.renderPreparedPrintPreview(index) { false }
                        try {
                            val pixels = IntArray(preview.width * preview.height)
                            preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                            check(pixels.any { it == android.graphics.Color.BLACK })
                        } finally { preview.recycle() }
                    }
                    StoredCaptPage.write(spool,
                        produce = { accept -> session.forEachPrintBand(0, layout.orientations[0], 0, { false }, accept) },
                        encode = { bytes, stride, lines, last -> HiscoaNative.encodeBand(bytes, stride, lines, last) },
                        cancelled = { false }, reserve = {}).validate { false }
                    observations.put(JSONObject().put("format", kind.extension)
                        .put("selectedSheets", selected?.sorted()?.joinToString("+") { (it + 1).toString() } ?: "all")
                        .put("fitWidth", fitWidth).put("pdfPages", count)
                        .put("peakObservedRssBytes", prepared.peakObservedRssBytes))
                }
                return count
            }
            for (kind in OfficeKind.entries) {
                stage = "source-${kind.extension}"
                context.assets.open("office-fixtures/${if (kind.spreadsheet) "workbook" else "word"}.${kind.extension}").use { input ->
                    source.outputStream().use { input.copyTo(it) }
                }
                convert(kind, if (kind.spreadsheet) listOf(2, 0) else null, false, if (kind.spreadsheet) 7 else 3)
                if (kind.spreadsheet) {
                    val fitted = (0 until 3).map { convert(kind, listOf(it), true) }
                    check(fitted[0] == 1)
                    check(fitted[1] == 1) // Saved 100% layout needs 3 horizontal pages for this table.
                    check(fitted[2] > 1) // Height remains unlimited, not a single-page sheet export.
                    check(convert(kind, null, true) == fitted.sum())
                    check(convert(kind, listOf(2, 0), true) == fitted[0] + fitted[2])
                    // A new job with fit disabled uses the untouched original, not mutated native state.
                    convert(kind, listOf(1), false, 3)
                    // Wrong saved results, unchanged formula expressions: observe real import policy.
                    source.writeBytes(OfficeSyntheticFormulaCache.stale(kind.extension,source.readBytes()))
                    convert(kind, listOf(0), false, 1)
                }
            }
            check(pids.size == 18)
            report.writeText(JSONObject().put("syntheticOnly", true).put("hardwarePrintTest", false)
                .put("workerProcesses", pids.size).put("conversions", observations).toString())
            result.putString("stream", "CAPT_OFFICE_WORKER_CONVERSION_PASSED (synthetic, no USB)")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "OFFICE_WORKER_CONVERSION_FAILED:$stage:${(error as? OfficeConversionFailure)?.reason?.name ?: error.javaClass.simpleName}:category=${(error as? OfficeConversionFailure)?.sandboxCategory ?: 0}")
        } finally { source.delete(); spool.delete() }
        finish(outcome, result)
    }
}
