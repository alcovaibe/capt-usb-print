package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.os.Bundle
import androidx.core.content.FileProvider
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import com.cryonum.captusbprint.queue.QueuePhase
import com.cryonum.captusbprint.document.*
import java.io.File

/** Runs in the real API 36 graphics/JNI runtime. No USB command or service is invoked. */
class StageCRasterSmoke : Instrumentation() {
    private var verifyRecovery = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        verifyRecovery = arguments?.getString("verifyRecovery") == "true"
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            // Instrumentation.onCreate can start this thread before Application.onCreate finishes.
            // Wait for the main thread's startup work before inspecting durable recovery.
            waitForIdleSync()
            val context = targetContext
            PrintQueueRuntime.initialize(context)
            if (verifyRecovery) {
                check(PrintQueueRuntime.snapshot().any {
                    it.phase == QueuePhase.ERROR && it.failure == "PROCESS_INTERRUPTED"
                }) { "Missing recovered job: ${PrintQueueRuntime.snapshot().map { it.phase.name + ":" + it.failure }}" }
                check(File(context.noBackupFilesDir,"print-queue").listFiles()?.isEmpty() != false) { "Stale queue files remain" }
                check(!PrintQueueRuntime.hasWork()) { "Recovery left runnable work" }
                result.putString("stream", "CAPT_STAGE_C_RECOVERY_SMOKE_PASSED")
                finish(Activity.RESULT_OK,result)
                return
            }
            val directory = File(context.cacheDir, "reports").apply { mkdirs() }
            val png = File(directory, "fixture.png")
            val jpeg = File(directory, "fixture.jpg")
            val pdf = File(directory, "fixture.pdf")
            val image = Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawRect(0f,0f,100f,100f,Paint().apply { color = Color.BLACK })
            png.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            val white = Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
            Canvas(white).apply { drawColor(Color.WHITE); drawBitmap(image,0f,0f,null) }
            jpeg.outputStream().use { white.compress(Bitmap.CompressFormat.JPEG,95,it) }
            white.recycle()
            image.recycle()
            ExifInterface(jpeg).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val doc = PdfDocument()
            try {
                repeat(4) { n ->
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(200,100,n+1).create())
                    page.canvas.drawRect(10f,10f,60f+n*20,90f,Paint().apply { color = Color.BLACK })
                    doc.finishPage(page)
                }
                pdf.outputStream().use { doc.writeTo(it) }
            } finally { doc.close() }
            for (file in listOf(png,jpeg,pdf)) {
                val uri = FileProvider.getUriForFile(context,"${context.packageName}.reportprovider",file)
                DocumentInspector.openSession(context,uri).use { source ->
                    check(source.overview.pageCount == if (file == pdf) 4 else 1)
                    if (file == jpeg) check(source.overview.width == 100 && source.overview.height == 200)
                    var budget = 0L
                    val encoded = File(directory, "fixture-spool")
                    val page = StoredCaptPage.write(encoded,
                        produce = { accept -> source.forEachPrintBand(0,Orientation.PORTRAIT,0,{false},accept) },
                        encode = { pixels,stride,lines,last -> HiscoaNative.encodeBand(pixels,stride,lines,last) },
                        cancelled = { false }, reserve = { budget += it })
                    check(page.bandCount == 110 && page.compressedBytes > 0 && budget == encoded.length())
                    for (orientation in Orientation.entries) for (rotation in listOf(0,90)) {
                        val preview = source.renderPrintPreview(0,orientation,rotation) { false }
                        val pixels = IntArray(preview.width*preview.height)
                        preview.getPixels(pixels,0,preview.width,0,0,preview.width,preview.height)
                        check(pixels.any { it == Color.BLACK } && pixels.any { it == Color.WHITE })
                        check((preview.width < preview.height) == (orientation == Orientation.PORTRAIT))
                        check(preview.getPixel(0,0) == Color.WHITE)
                        if (file == png && orientation == Orientation.PORTRAIT && rotation == 0) {
                            check(preview.getPixel(preview.width*2/5,preview.height/2) == Color.BLACK)
                            check(preview.getPixel(preview.width*3/5,preview.height/2) == Color.WHITE)
                        }
                        preview.recycle()
                    }
                    encoded.delete()
                    if (file == png) {
                        val id = PrintQueueRuntime.enqueue(context,source,
                            JobSpecification.create(1,"1",1), "offline-smoke-no-usb")
                        val owned = File(context.noBackupFilesDir,"print-queue/$id/source.bin")
                        source.close()
                        check(owned.isFile) // Preview teardown cannot delete the queue copy.
                        PrintQueueRuntime.cancel(id)
                        check(!owned.exists())
                    }
                }
            }
            // Leave one staged, never-activated job for the next process's recovery check.
            val recoveryUri = FileProvider.getUriForFile(context,"${context.packageName}.reportprovider",png)
            DocumentInspector.openSession(context,recoveryUri).use { source ->
                PrintQueueRuntime.enqueue(context,source,JobSpecification.create(1,"1",1),"offline-smoke-no-usb")
            }
            listOf(png,jpeg,pdf).forEach { it.delete() }
            result.putString("stream", "CAPT_STAGE_C_RASTER_SMOKE_PASSED")
            finish(Activity.RESULT_OK,result)
        } catch (error: Throwable) {
            result.putString("stream", "CAPT_STAGE_C_RASTER_SMOKE_FAILED: ${error.javaClass.simpleName}: ${error.message}")
            finish(Activity.RESULT_CANCELED,result)
        }
    }
}
