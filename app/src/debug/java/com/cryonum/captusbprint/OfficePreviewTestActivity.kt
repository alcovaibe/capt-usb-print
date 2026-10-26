package com.cryonum.captusbprint

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Looper
import com.cryonum.captusbprint.office.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference

/** Non-exported synthetic UI test only, not an Office renderer or a URI gateway. */
internal class OfficePreviewTestActivity : OfficePreviewActivity() {
    companion object { var current = WeakReference<OfficePreviewTestActivity>(null) }
    override val officeKind get() = OfficeKind.entries.singleOrNull { it.extension == intent.getStringExtra("kind") } ?: OfficeKind.XLS
    override val service get() = if (intent.getBooleanExtra("rejectedSheet", false))
        OfficePreviewRejectedSheetWorkerService::class.java else OfficePreviewTestWorkerService::class.java
    override fun openSource(): InputStream {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (intent.getBooleanExtra("sourceFailure",false)) throw IOException("PRIVATE_PROVIDER_SENTINEL")
        return applicationContext.assets.open("office-fixtures/${if (officeKind.spreadsheet) "workbook" else "word"}.${officeKind.extension}")
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); current = WeakReference(this) }
}

/** Production admission + real worker lifecycle, deterministic fake page layout. */
open class OfficePreviewTestWorkerService : OfficeWorkerService() {
    internal open val rejectFirstSheet get() = false
    internal override fun newBackend() = object : OfficeWorkerBackend {
        override fun inspectSheets(context: Context, input: File, directory: File, kind: OfficeKind): List<String> {
            require(kind.spreadsheet)
            // The slow fixture makes cancelling this phase observable without UI timing races.
            if (rejectFirstSheet) File(directory, "sheet-scan-started").writeText("synthetic")
            Thread.sleep(if (rejectFirstSheet) 1200 else 180)
            return listOf("Сводка", "Широкая", "Длинная")
        }
        override fun convert(context: Context,input: File,output: File,directory: File,options: OfficeOptions): List<String> {
            Thread.sleep(180) // Allow UI replacement/cancellation schedules, not performance assertions.
            val selection = options.selectedSheets ?: listOf(0,1,2)
            require(!options.kind.spreadsheet || selection.all { it in 0..2 })
            if (rejectFirstSheet && options.fitWidth && 0 in selection)
                throw OfficeConversionFailure(OfficeFailure.FIT_WIDTH_UNSUPPORTED)
            val counts = if (options.fitWidth) listOf(1,1,4) else listOf(1,3,6)
            val pages = if (options.kind.spreadsheet) selection.sumOf { counts[it] } else 3
            val pdf = PdfDocument()
            try {
                for (i in 0 until pages) {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595,842,i+1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val paint = Paint().apply { color = Color.BLACK; textSize = 18f }
                    page.canvas.drawRect(40f,50f,200f,85f,paint)
                    page.canvas.drawText("Synthetic UI page ${i+1}",40f,140f,paint)
                    pdf.finishPage(page)
                }
                output.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            return if (options.kind.spreadsheet) listOf("Сводка","Широкая","Длинная") else emptyList()
        }
    }
}

class OfficePreviewRejectedSheetWorkerService : OfficePreviewTestWorkerService() {
    internal override val rejectFirstSheet get() = true
}
