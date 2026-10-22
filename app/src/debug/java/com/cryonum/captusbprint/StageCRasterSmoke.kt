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
import com.cryonum.captusbprint.document.*
import java.io.File

/** Runs in the real API 36 graphics/JNI runtime. No USB command or service is invoked. */
class StageCRasterSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val context = targetContext
            val directory = File(context.cacheDir, "reports").apply { mkdirs() }
            val png = File(directory, "fixture.png")
            val jpeg = File(directory, "fixture.jpg")
            val pdf = File(directory, "fixture.pdf")
            val image = Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawRect(0f,0f,100f,100f,Paint().apply { color = Color.BLACK })
            png.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            jpeg.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG,95,it) }
            image.recycle()
            ExifInterface(jpeg).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            PdfDocument().use { doc ->
                repeat(4) { n ->
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(200,100,n+1).create())
                    page.canvas.drawRect(10f,10f,60f+n*20,90f,Paint().apply { color = Color.BLACK })
                    doc.finishPage(page)
                }
                pdf.outputStream().use { doc.writeTo(it) }
            }
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
                        preview.recycle()
                    }
                    encoded.delete()
                }
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
