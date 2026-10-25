package com.cryonum.captusbprint

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Process
import com.cryonum.captusbprint.office.OfficeOptions
import com.cryonum.captusbprint.office.OfficeWorkerBackend
import com.cryonum.captusbprint.office.OfficeWorkerService
import java.io.File

/** Fault injection only; these bytes are not Office files and this is not a format renderer. */
class OfficeFaultWorkerService : OfficeWorkerService() {
    internal override fun newBackend(): OfficeWorkerBackend = object : OfficeWorkerBackend {
        override fun convert(context: Context, input: File, output: File, directory: File,
                             options: OfficeOptions): List<String> {
            when (input.inputStream().use { it.read() }) {
                1 -> Thread.sleep(60_000)
                2 -> Process.killProcess(Process.myPid())
                3 -> {
                    File(directory, "budget-fill").outputStream().use { stream ->
                        val block = ByteArray(64 * 1024)
                        repeat(64) { stream.write(block) }
                    }
                    Thread.sleep(60_000)
                }
                4 -> error("PRIVATE_FAULT_DETAIL_MUST_NOT_ESCAPE")
                5 -> output.writeText("not a PDF")
                6, 7 -> {
                    val pdf = PdfDocument()
                    try {
                        val size = if (input.inputStream().use { it.read() } == 7) 612 to 792 else 595 to 842
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(size.first, size.second, 1).create())
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawRect(70f, 80f, 200f, 150f, Paint().apply { color = Color.BLACK })
                        pdf.finishPage(page)
                        output.outputStream().use { pdf.writeTo(it) }
                    } finally { pdf.close() }
                }
                else -> error("Unknown synthetic worker fault")
            }
            return emptyList()
        }
    }
}
