package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.AssetManager
import android.os.Bundle
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.StoredCaptPage
import com.cryonum.captusbprint.core.HiscoaNative
import org.json.JSONArray
import org.json.JSONObject
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File

/**
 * Build-time opt-in only. Opens the FOUR hardcoded synthetic fixtures, never an external URI.
 * Not a production converter, external-resource sandbox, cancellation or hardware-print test.
 */
class OfficeEngineSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var stage = "bootstrap"
        var outcome = Activity.RESULT_CANCELED
        var activity: Activity? = null
        val context = targetContext
        val directory = File(context.cacheDir, "office-engine-probe").apply { mkdirs() }
        try {
            waitForIdleSync()
            val report = File(context.filesDir, "office-probe-results.json")
            check(!report.exists() || report.delete())
            // Same asset layout as pinned upstream Android Viewer; no downloaded user content.
            unpack(context.assets, "unpack", File(context.applicationInfo.dataDir))
            activity = startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            LibreOfficeKit.init(activity)
            val office = Office(requireNotNull(LibreOfficeKit.getLibreOfficeKitHandle()))
            val observations = JSONArray()
            for (name in listOf("word.docx", "word.doc", "workbook.xls", "workbook.xlsx")) {
                stage = "load-$name"
                val input = File(directory, name)
                context.assets.open("office-fixtures/$name").use { source ->
                    input.outputStream().use { source.copyTo(it) }
                }
                val document = requireNotNull(office.documentLoad(input.absolutePath))
                val book = name.startsWith("workbook")
                try {
                    check(document.getDocumentType() == if (book) Document.DOCTYPE_SPREADSHEET else Document.DOCTYPE_TEXT)
                    if (book) {
                        check(document.getParts() == 3)
                        check((0 until 3).map { document.getPartName(it) } == listOf("Сводка", "Широкая", "Длинная"))
                    }
                    stage = "export-$name"
                    val pdf = File(directory, "$name.pdf")
                    check(!pdf.exists() || pdf.delete()) // A failed saveAs must never reuse an old PDF.
                    document.saveAs(pdf.toURI().toASCIIString(), "pdf", "")
                    check(pdf.isFile && pdf.length() in 1..DocumentInspector.MAX_SOURCE_BYTES)
                    stage = "validate-$name"
                    DocumentInspector.openPreparedPdf(context, pdf).use { prepared ->
                        if (!book) check(prepared.overview.pageCount == 3)
                        else check(prepared.overview.pageCount >= 3)
                        check(prepared.preparedPdfLayout != null)
                        val preview = prepared.renderPreparedPrintPreview(0) { false }
                        try {
                            val pixels = IntArray(preview.width * preview.height)
                            preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                            check(pixels.any { it == android.graphics.Color.BLACK })
                        } finally { preview.recycle() }
                        val spool = File(directory, "$name.capt")
                        StoredCaptPage.write(spool,
                            produce = { accept -> prepared.forEachPrintBand(0,
                                prepared.preparedPdfLayout.orientations[0], 0, { false }, accept) },
                            encode = { bytes, stride, lines, last -> HiscoaNative.encodeBand(bytes, stride, lines, last) },
                            cancelled = { false }, reserve = {}).validate { false }
                        observations.put(JSONObject().put("format", name.substringAfterLast('.'))
                            .put("pdfPages", prepared.overview.pageCount).put("pdfBytes", pdf.length())
                    }
                } finally { document.destroy() }
            }
            report.writeText(JSONObject()
                .put("sourceCommit", "8221e31b3ac356a1623c672912a3d2b492f7e3d1")
                .put("syntheticOnly", true).put("hardwarePrintTest", false)
                .put("conversions", observations).toString())
            result.putString("stream", "CAPT_OFFICE_ENGINE_CONVERSION_SMOKE_PASSED (synthetic only)")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "OFFICE_ENGINE_PROBE_FAILED:$stage:${error.javaClass.simpleName}")
        } finally {
            directory.deleteRecursively()
            activity?.let { runOnMainSync { it.finish() } }
        }
        finish(outcome, result)
    }

    private fun unpack(assets: AssetManager, path: String, destination: File) {
        val names = requireNotNull(assets.list(path))
        check(names.isNotEmpty()) { "Engine unpack assets missing" }
        destination.mkdirs()
        for (name in names) {
            val next = "$path/$name"
            val target = File(destination, name)
            if (!assets.list(next).isNullOrEmpty()) unpack(assets, next, target)
            else assets.open(next).use { source -> target.outputStream().use { source.copyTo(it) } }
        }
    }
}
