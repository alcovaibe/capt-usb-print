package com.cryonum.captusbprint

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Process
import android.system.Os
import com.cryonum.captusbprint.office.OfficeOptions
import com.cryonum.captusbprint.office.OfficeWorkerBackend
import com.cryonum.captusbprint.office.OfficeWorkerService
import com.cryonum.captusbprint.office.OfficeWorkBudget
import java.io.File

/** Fault injection only; these bytes are not Office files and this is not a format renderer. */
class OfficeFaultWorkerService : OfficeWorkerService() {
    companion object { private var retainedMemory: List<ByteArray>? = null }
    // Lifecycle fault bytes deliberately bypass format admission, only in this debug-only service.
    internal override fun validateInput(input: File, options: OfficeOptions) = Unit
    internal override fun newBackend(): OfficeWorkerBackend = object : OfficeWorkerBackend {
        override fun convert(context: Context, input: File, output: File, directory: File,
                             options: OfficeOptions): List<String> {
            val mode = input.inputStream().use { it.read() }
            when (mode) {
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
                6, 7, 8, 9, 10 -> {
                    val pdf = PdfDocument()
                    try {
                        val size = if (mode == 7) 612 to 792 else 595 to 842
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(size.first, size.second, 1).create())
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawRect(70f, 80f, 200f, 150f, Paint().apply { color = Color.BLACK })
                        pdf.finishPage(page)
                        output.outputStream().use { pdf.writeTo(it) }
                    } finally { pdf.close() }
                    // Read/traversal is allowed, so PDF validation succeeds; unlink must fail.
                    if (mode == 8) Os.chmod(directory.absolutePath, 0b101000000)
                    if (mode == 9) File(directory,"budget-fill").outputStream().use { stream ->
                        val block = ByteArray(64*1024)
                        repeat(32) { stream.write(block) }
                    } // Finish immediately, before the controller's next periodic observation.
                    if (mode == 10) {
                        val limit = input.inputStream().use { stream ->
                            java.io.DataInputStream(stream).run { readUnsignedByte(); readLong() }
                        }
                        check(limit in 1..(512L*1024*1024))
                        val blocks = ArrayList<ByteArray>()
                        retainedMemory = blocks
                        // Different disposable workers need not have equal startup RSS.
                        // Commit only enough bounded synthetic memory to exceed THIS attempt's
                        // budget, then keep it alive through the fast success callback.
                        while (OfficeWorkBudget.parseRss(File("/proc/self/status").readText()) <= limit+8L*1024*1024) {
                            check(blocks.size < 8) { "SYNTHETIC_MEMORY_TARGET_NOT_REACHED" }
                            val bytes = ByteArray(16*1024*1024)
                            for (offset in bytes.indices step 4096) bytes[offset] = 0x55
                            blocks.add(bytes)
                        }
                    }
                    if (mode == 9 || mode == 10) File(directory,"fast-result-ready").writeText("ready")
                }
                else -> error("Unknown synthetic worker fault")
            }
            return emptyList()
        }
    }
}
