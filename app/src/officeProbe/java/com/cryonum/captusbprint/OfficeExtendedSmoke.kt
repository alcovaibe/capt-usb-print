package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.core.content.FileProvider
import com.cryonum.captusbprint.office.*
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.document.StoredCaptPage
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Fixed extended assets only. Real worker, prepared preview/spool/held queue; never activate USB. */
class OfficeExtendedSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        val context = targetContext
        var stage = "start"
        val result = Bundle()
        var outcome = Activity.RESULT_CANCELED
        val source = File(context.cacheDir, "reports/office-extended-source.bin").apply { parentFile!!.mkdirs() }
        val output = File(context.filesDir, "office-extended-pdfs")
        val report = File(context.filesDir, "office-extended-results.json")
        val spool = File(context.cacheDir, "office-extended.capt")
        fun folders() = File(context.noBackupFilesDir, "office-work").listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val baseline = folders()
        val rows = JSONArray()
        fun sha(file: File) = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        try {
            check(!report.exists() || report.delete())
            check(!output.exists() || output.deleteRecursively()); check(output.mkdir())
            fun attempt(asset: String, kind: OfficeKind, selected: List<Int>?, fit: Boolean,
                        label: String, reject: Boolean = false, optionalReject: Boolean = false): Int {
                stage = label
                context.assets.open("office-fixtures/$asset").use { input -> source.outputStream().use { input.copyTo(it) } }
                val before = sha(source)
                val queue = PrintQueueRuntime.snapshot()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.reportprovider", source)
                var count = 0
                var failure: OfficeFailure? = null
                try {
                    OfficeWorkerClient.importSource(context, uri).use { imported ->
                        check(imported.importedKind == kind && sha(imported.file) == before)
                        OfficeWorkerClient.prepare(context, imported.file, OfficeOptions(kind, selected, fit)).use { ready ->
                            check(!reject)
                            val session = ready.session
                            val layout = requireNotNull(session.preparedPdfLayout)
                            count = session.overview.pageCount
                            check(count in 1..1000)
                            val copyDir = File(output, "copy").apply { check(mkdir()) }
                            session.copyForPrint(copyDir) {}.use { copy ->
                                File(copyDir, "source.bin").copyTo(File(output, "$label.pdf"))
                                check(copy.preparedPdfLayout === layout)
                            }
                            check(copyDir.delete())
                            for (page in listOf(0, count - 1).distinct()) {
                                val bitmap = session.renderPreparedPrintPreview(page) { false }
                                try { check(bitmap.width > 0 && bitmap.height > 0) } finally { bitmap.recycle() }
                            }
                            StoredCaptPage.write(spool,
                                produce = { accept -> session.forEachPrintBand(0, layout.orientations[0], 0, { false }, accept) },
                                encode = { bytes, stride, lines, last -> HiscoaNative.encodeBand(bytes,stride,lines,last) },
                                cancelled = { false }, reserve = {}).validate { false }
                            // Same final PDF enters HELD common queue. No USB permission/activation.
                            val plan = JobSpecification.preparedPdf(layout.orientations, "1", 2)
                            val id = PrintQueueRuntime.enqueue(context, session, plan, "offline-extended-no-usb")
                            PrintQueueRuntime.cancel(id)
                        }
                    }
                } catch (error: OfficeConversionFailure) {
                    if ((!reject && !optionalReject) || error.reason != OfficeFailure.FIT_WIDTH_UNSUPPORTED) throw error
                    check(PrintQueueRuntime.snapshot() == queue)
                    failure = error.reason
                }
                check(sha(source) == before && folders() == baseline)
                rows.put(JSONObject().put("case",label).put("format",kind.extension)
                    .put("pages",count).put("fitWidth",fit).put("failure",failure?.name ?: JSONObject.NULL))
                return count
            }
            for (kind in listOf(OfficeKind.DOCX, OfficeKind.DOC)) {
                check(attempt("word-flow.${kind.extension}",kind,null,false,"flow-${kind.extension}") >= 3)
                check(attempt("word-fonts.${kind.extension}",kind,null,false,"fonts-${kind.extension}") == 1)
            }
            for (kind in listOf(OfficeKind.XLS, OfficeKind.XLSX)) {
                fun run(sheet: Int?, fit: Boolean, suffix: String, reject: Boolean = false, optional: Boolean = false) =
                    attempt("workbook-edge.${kind.extension}",kind,sheet?.let { listOf(it) },fit,
                        "${kind.extension}-$suffix",reject,optional)
                run(0,true,"minimum",true)
                run(5,true,"single-column",true)
                run(3,true,"areas",true)
                val manual = run(1,true,"manual-fit",optional = true)
                check(manual in 0..1) // Either really fits or a clear refusal; never horizontal success.
                check(run(1,false,"manual-saved") >= 2)
                val tall = run(2,true,"tall"); check(tall > 1)
                check(run(4,true,"numbers-fit") == 1)
                check(run(4,false,"numbers-saved") == 1)
                check(attempt("workbook-edge.${kind.extension}",kind,listOf(4,2),true,
                    "${kind.extension}-subset") == tall + 1)
                run(null,true,"all-rejected",true)
                // Mandatory successful next conversion after the rejected all-sheet layout.
                check(run(4,true,"next") == 1)
            }
            check(attempt("upstream-table.docx",OfficeKind.DOCX,null,false,"upstream-docx") >= 1)
            check(attempt("upstream-borders.doc",OfficeKind.DOC,null,false,"upstream-doc") >= 1)
            check(attempt("upstream-borders.xls",OfficeKind.XLS,listOf(0),true,"upstream-xls") >= 1)
            check(attempt("upstream-borders.xlsx",OfficeKind.XLSX,listOf(0),true,"upstream-xlsx") >= 1)
            check(rows.length() == 30)
            report.writeText(JSONObject().put("syntheticOnly",true).put("hardwarePrintTest",false)
                .put("attempts",rows).toString())
            result.putString("stream","CAPT_OFFICE_EXTENDED_PASSED (30 attempts; generated and pinned upstream corpus, real engine, no USB)")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            val failure = error as? OfficeConversionFailure
            result.putString("stream","CAPT_OFFICE_EXTENDED_FAILED:$stage:${failure?.reason?.name ?: error.javaClass.simpleName}:${failure?.observation?.name ?: "NONE"}")
        } finally { source.delete(); spool.delete() }
        finish(outcome,result)
    }
}
