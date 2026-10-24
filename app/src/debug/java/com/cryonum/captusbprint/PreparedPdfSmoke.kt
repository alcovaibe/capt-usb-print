package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.StoredCaptPage
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import java.io.File
import java.util.concurrent.CancellationException

/** Actual Android PDF/PRINT/HISCOA contract for final layouts; no Office conversion or USB. */
class PreparedPdfSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            waitForIdleSync()
            val context = targetContext
            val directory = File(context.cacheDir, "prepared-pdf-smoke").apply { mkdirs() }
            try {
                val input = File(directory, "ready.pdf")
                makePdf(input, listOf(595 to 842, 842 to 595, 595 to 842))
                val original = input.readBytes()
                DocumentInspector.openPreparedPdf(context, input).use { source ->
                    val orientations = requireNotNull(source.preparedPdfLayout).orientations
                    check(orientations == listOf(Orientation.PORTRAIT, Orientation.LANDSCAPE, Orientation.PORTRAIT))
                    // A subsequent replacement of the conversion output must not change this snapshot.
                    input.writeText("Replaced after successful handoff")
                    for (index in orientations.indices) {
                        val orientation = orientations[index]
                        val raw = ByteArray(620 * 7016)
                        source.forEachPrintBand(index, orientation, 0, { false }, { y, bytes ->
                            bytes.copyInto(raw, y * 620)
                        }) // Default call must preserve final layout too.
                        fun black(x: Int, y: Int) = raw[y * 620 + x / 8].toInt() and (0x80 ushr (x and 7)) != 0
                        val landscape = orientation == Orientation.LANDSCAPE
                        // Independent known PDF marks at 20..100pt / 30..70pt. APP_FIT fails these positions.
                        check(black(if (landscape) 4626 else 333, 333))
                        check(!black(if (landscape) 4626 else 42, if (landscape) 42 else 333))
                        val preview = source.renderPreparedPrintPreview(index) { false }
                        try {
                            check((preview.width > preview.height) == landscape)
                            val pixels = IntArray(preview.width * preview.height)
                            preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
                            val width = if (landscape) 7016 else 4960
                            val height = if (landscape) 4960 else 7016
                            for (y in 0 until preview.height) for (x in 0 until preview.width) {
                                val lx = (x.toLong() * width / preview.width).toInt()
                                val ly = (y.toLong() * height / preview.height).toInt()
                                val px = if (landscape) 4959 - ly else lx
                                val py = if (landscape) lx else ly
                                check((pixels[y * preview.width + x] == Color.BLACK) == black(px, py))
                            }
                        } finally { preview.recycle() }
                        val spool = File(directory, "page-$index.bin")
                        StoredCaptPage.write(spool,
                            produce = { accept -> source.forEachPrintBand(index, orientation, 0, { false }, accept) },
                            encode = { bytes, stride, lines, last -> HiscoaNative.encodeBand(bytes, stride, lines, last) },
                            cancelled = { false }, reserve = {}).validate { false }
                        check(spool.length() > 0)
                    }
                    val badPlan = JobSpecification.create(3, "", 1)
                    check(runCatching { source.validatePlan(badPlan) }.exceptionOrNull() is IllegalArgumentException)
                    val plan = JobSpecification.preparedPdf(orientations, "2-3", 2)
                    source.validatePlan(plan)
                    val id = PrintQueueRuntime.enqueue(context, source, plan, "offline-prepared-no-usb")
                    val owned = File(context.noBackupFilesDir, "print-queue/$id/source.bin")
                    check(owned.readBytes().contentEquals(original))
                    source.close()
                    check(owned.isFile)
                    PrintQueueRuntime.cancel(id)
                    check(!owned.exists())
                }
                val inspection = File(context.cacheDir, "stage-c-inspection")
                fun sources() = inspection.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                val before = sources()
                makePdf(input, listOf(595 to 842, 420 to 595))
                check(runCatching { DocumentInspector.openPreparedPdf(context, input).close() }
                    .exceptionOrNull() is IllegalArgumentException)
                check(sources() == before)
                makePdf(input, listOf(595 to 842, 842 to 595))
                var polls = 0
                check(runCatching { DocumentInspector.openPreparedPdf(context, input) { ++polls > 5 }.close() }
                    .exceptionOrNull() is CancellationException)
                check(sources() == before)
                input.writeText("Corrupt conversion result")
                check(runCatching { DocumentInspector.openPreparedPdf(context, input).close() }.isFailure)
                check(sources() == before)
            } finally { directory.deleteRecursively() }
            result.putString("stream", "CAPT_PREPARED_PDF_LAYOUT_SMOKE_PASSED (Office engine not invoked)")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "PREPARED_PDF_SMOKE_FAILED:${error.javaClass.simpleName}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun makePdf(file: File, sizes: List<Pair<Int, Int>>) {
        PdfDocument().use { pdf ->
            sizes.forEachIndexed { index, (width, height) ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawRect(20f, 30f, 100f, 70f, Paint().apply { color = Color.BLACK })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        }
    }
}
