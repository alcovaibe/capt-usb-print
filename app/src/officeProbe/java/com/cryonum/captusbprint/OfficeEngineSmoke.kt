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
        val stageFile = File(targetContext.filesDir, "office-probe-stage.txt")
        var stage = "bootstrap"
        fun mark(value: String) { stage = value; stageFile.writeText(value) }
        var outcome = Activity.RESULT_CANCELED
        var activity: Activity? = null
        val context = targetContext
        val directory = File(context.cacheDir, "office-engine-probe")
        try {
            mark("bootstrap")
            check(!directory.exists() || directory.deleteRecursively())
            check(directory.mkdirs())
            waitForIdleSync()
            val report = File(context.filesDir, "office-probe-results.json")
            check(!report.exists() || report.delete())
            // Same asset layout as pinned upstream Android Viewer; no downloaded user content.
            val engineRoot = File(directory, "engine")
            unpack(context.assets, "unpack", engineRoot)
            // The upstream asset contains the original Viewer package name.
            // Keep font caches inside this disposable probe, never in that package.
            val fonts = File(engineRoot, "etc/fonts/fonts.conf")
            val fontConfig = fonts.readText()
            check(fontConfig.contains("/data/data/org.documentfoundation.libreoffice/fontconfig"))
            fonts.writeText(fontConfig.replace("/data/data/org.documentfoundation.libreoffice/fontconfig",
                File(engineRoot, "fontconfig").absolutePath))
            activity = startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            mark("jni-init")
            LibreOfficeKit.init(context, engineRoot)
            mark("jni-handle")
            val office = Office(requireNotNull(LibreOfficeKit.getLibreOfficeKitHandle()))
            val observations = JSONArray()
            for (name in listOf("word.docx", "word.doc", "workbook.xls", "workbook.xlsx")) {
                mark("load-$name")
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
                    fun export(selectedSheets: List<Int>? = null): Int {
                        // Reverse click order 3→1 still exports in workbook order 1→3.
                        val selection = selectedSheets?.distinct()?.sorted()
                        val suffix = selection?.joinToString("-") { (it + 1).toString() } ?: "all"
                        val options = if (selection == null) "" else JSONObject()
                            .put("SheetRange", JSONObject().put("type", "string")
                                .put("value", selection.joinToString(",") { (it + 1).toString() }))
                            .put("SinglePageSheets", JSONObject().put("type", "boolean").put("value", false))
                            .toString()
                        mark("export-$name-$suffix")
                        val pdf = File(directory, "$name-$suffix.pdf")
                        check(!pdf.exists() || pdf.delete()) // A failed saveAs must never reuse an old PDF.
                        check(document.saveAs(pdf.toURI().toASCIIString(), "pdf", options) != 0)
                        check(pdf.isFile && pdf.length() in 1..DocumentInspector.MAX_SOURCE_BYTES)
                        mark("validate-$name-$suffix")
                        return DocumentInspector.openPreparedPdf(context, pdf).use { prepared ->
                            if (!book) check(prepared.overview.pageCount == 3)
                            else check(prepared.overview.pageCount >= 1)
                            check(prepared.preparedPdfLayout != null)
                            val preview = prepared.renderPreparedPrintPreview(0) { false }
                            try {
                                val pixels = IntArray(preview.width * preview.height)
                                preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                                check(pixels.any { it == android.graphics.Color.BLACK })
                            } finally { preview.recycle() }
                            val spool = File(directory, "$name-$suffix.capt")
                            StoredCaptPage.write(spool,
                                produce = { accept -> prepared.forEachPrintBand(0,
                                    prepared.preparedPdfLayout.orientations[0], 0, { false }, accept) },
                                encode = { bytes, stride, lines, last -> HiscoaNative.encodeBand(bytes, stride, lines, last) },
                                cancelled = { false }, reserve = {}).validate { false }
                            observations.put(JSONObject().put("format", name.substringAfterLast('.'))
                                .put("sheetRange", suffix).put("pdfPages", prepared.overview.pageCount).put("pdfBytes", pdf.length()))
                            prepared.overview.pageCount
                        }
                    }
                    val allPages = export()
                    if (book) {
                        val singleCounts = (0 until 3).map { export(listOf(it)) }
                        val subsetPages = export(listOf(2, 0))
                        check(singleCounts[2] > 1) // The long sheet must keep pagination by height.
                        check(allPages == singleCounts.sum())
                        check(subsetPages == singleCounts[0] + singleCounts[2])
                    }
                } finally { document.destroy() }
            }
            report.writeText(JSONObject()
                .put("sourceCommit", "8221e31b3ac356a1623c672912a3d2b492f7e3d1")
                .put("syntheticOnly", true).put("hardwarePrintTest", false)
                .put("conversions", observations).toString())
            mark("complete")
            result.putString("stream", "CAPT_OFFICE_ENGINE_CONVERSION_SMOKE_PASSED (synthetic only)")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "OFFICE_ENGINE_PROBE_FAILED:$stage:${error.javaClass.simpleName}")
        } finally {
            if (!directory.deleteRecursively()) {
                result.putString("stream", "OFFICE_ENGINE_PROBE_FAILED:cleanup")
                outcome = Activity.RESULT_CANCELED
            }
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
