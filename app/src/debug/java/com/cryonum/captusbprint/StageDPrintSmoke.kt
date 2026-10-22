package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.*
import android.print.*
import android.view.KeyEvent
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.*
import com.cryonum.captusbprint.printservice.*
import com.cryonum.captusbprint.queue.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android pipe/PDF/framework discovery checks. Never grants, emulates or opens a USB printer. */
class StageDPrintSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var previousServices: String? = null
        var resultCode = Activity.RESULT_CANCELED
        val directory = File(targetContext.cacheDir, "stage-d-smoke").apply { mkdirs() }
        try {
            waitForIdleSync()
            val context = targetContext
            PrintQueueRuntime.initialize(context)
            val component = ComponentName(context, CaptPrintService::class.java)
            @Suppress("DEPRECATION")
            val declared = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
            check(declared.exported && declared.permission == "android.permission.BIND_PRINT_SERVICE")
            check(declared.metaData.getInt("android.printservice") != 0)
            val pdf = File(directory,"source.pdf")
            writePdf(pdf, false)
            val bytes = pdf.readBytes()
            val copied = File(directory,"pipe.pdf")
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = Thread {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    bytes.asList().chunked(311).forEach { output.write(it.toByteArray()) }
                }
            }.apply { start() }
            var reserved = 0L
            check(SystemSpoolReader.copy(pipe[0],copied,{ reserved += it },{false}) == bytes.size.toLong())
            writer.join(2000)
            check(!writer.isAlive && reserved == bytes.size.toLong() && copied.readBytes().contentEquals(bytes))
            check(!pipe[0].fileDescriptor.valid())
            val timeoutPipe = ParcelFileDescriptor.createPipe()
            try {
                try { SystemSpoolReader.copy(timeoutPipe[0],File(directory,"timeout"),{},{false},100); error("No timeout") }
                catch (_: IOException) { }
                check(!timeoutPipe[0].fileDescriptor.valid())
            } finally { timeoutPipe[1].close() }
            val cancelPipe = ParcelFileDescriptor.createPipe()
            val cancelled = AtomicBoolean(false)
            val cancelThread = Thread { SystemClock.sleep(100); cancelled.set(true) }.apply { start() }
            try {
                try { SystemSpoolReader.copy(cancelPipe[0],File(directory,"cancelled"),{},{cancelled.get()}); error("No cancellation") }
                catch (_: CancellationException) { }
                check(!cancelPipe[0].fileDescriptor.valid())
            } finally { cancelPipe[1].close(); cancelThread.join(1000) }

            val overview = DocumentInspector.inspectPdf(pdf,pdf.length())
            DocumentSession(copied,overview).use { source ->
                var mark = false
                source.forEachPrintBand(0,Orientation.PORTRAIT,0,{false},{ y, band ->
                    if (90 in y until y+band.size/620) {
                        mark = band[(90-y)*620+90/8].toInt() and (0x80 ushr (90 and 7)) != 0
                    }
                },preservePdfLayout = true)
                check(mark) { "System PDF point scale or origin changed" }
            }
            val landscape = File(directory,"landscape.pdf")
            writePdf(landscape,true)
            DocumentSession(landscape,DocumentInspector.inspectPdf(landscape,landscape.length())).use { source ->
                var mark = false
                source.forEachPrintBand(0,Orientation.LANDSCAPE,0,{false},{ y, band ->
                    val px = 4960-90
                    if (90 in y until y+band.size/620)
                        mark = band[(90-y)*620+px/8].toInt() and (0x80 ushr (px and 7)) != 0
                },preservePdfLayout = true)
                check(mark) { "Landscape PDF scale/rotation changed" }
            }
            val request = SystemPrintRequest(11,listOf(SystemPageSelection.Range(7,9)),2,
                8268,11693,600,600,1,1,listOf(433,433,433,433))
            val input = ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY)
            val id = PrintQueueRuntime.enqueueSystem(context,input,request,"offline-no-usb") { false }
            check(!input.fileDescriptor.valid())
            check(PrintQueueRuntime.snapshot().first { it.id == id }.let { !it.ready && it.sheets == 6 })
            val owned = File(context.noBackupFilesDir,"print-queue/$id/source.bin")
            check(owned.isFile)
            PrintQueueRuntime.cancel(id)
            check(!owned.exists())
            val before = File(context.noBackupFilesDir,"print-queue").listFiles()?.size ?: 0
            try {
                PrintQueueRuntime.enqueueSystem(context,
                    ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY),request,"offline-no-usb") { true }
                error("Cancelled intake accepted")
            } catch (_: CancellationException) { }
            check((File(context.noBackupFilesDir,"print-queue").listFiles()?.size ?: 0) == before)

            // Enable only in the disposable CI emulator and open a real Android Print dialog.
            // The production Canon remains unavailable, correctly: this emulator has no USB grant.
            val currentServices = shell("settings get secure enabled_print_services").trim()
            previousServices = currentServices
            val existing = currentServices.takeUnless { it == "null" || it.isEmpty() }
            shell("settings put secure enabled_print_services " +
                ((existing?.plus(":") ?: "") + component.flattenToString()))
            activity = startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                (activity!!.getSystemService(Context.PRINT_SERVICE) as PrintManager).print("Offline framework fixture",
                    object : PrintDocumentAdapter() {
                        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: CancellationSignal,
                                              callback: LayoutResultCallback, extras: Bundle?) {
                            callback.onLayoutFinished(PrintDocumentInfo.Builder("fixture.pdf").setPageCount(3)
                                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),true)
                        }
                        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
                                             signal: CancellationSignal, callback: WriteResultCallback) {
                            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { it.write(bytes) }
                            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                        }
                    }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("600dpi","600 dpi",600,600))
                        .setMinMargins(PrintAttributes.Margins(433,433,433,433))
                        .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build())
            }
            val deadline = SystemClock.elapsedRealtime()+20_000
            fun discovered() = runCatching { SystemPrintDiagnostics.file(context).readText()
                .contains("DISCOVERY_PUBLISHED_UNAVAILABLE") }.getOrDefault(false)
            while (SystemClock.elapsedRealtime() < deadline &&
                !discovered()) SystemClock.sleep(100)
            check(discovered()) {
                "Android did not bind/discover the protected PrintService"
            }
            result.putString("stream","CAPT_STAGE_D_SYSTEM_SMOKE_PASSED")
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream","CAPT_STAGE_D_SYSTEM_SMOKE_FAILED: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching {
                uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK),true)
                uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK),true)
            }
            runCatching { runOnMainSync { activity?.finish() } }
            previousServices?.let {
                shell(if (it == "null" || it.isEmpty()) "settings delete secure enabled_print_services"
                    else "settings put secure enabled_print_services $it")
            }
            directory.deleteRecursively()
        }
        finish(resultCode,result)
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun writePdf(file: File, landscape: Boolean) {
        PdfDocument().use { pdf ->
            repeat(3) { n ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(if (landscape) 842 else 595,
                    if (landscape) 595 else 842,n+1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(10f,10f,20f,20f,Paint().apply { color = Color.BLACK })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        }
    }
}
