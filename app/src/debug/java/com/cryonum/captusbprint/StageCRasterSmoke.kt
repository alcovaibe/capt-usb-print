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
import com.cryonum.captusbprint.queue.QueueReportBundle
import org.json.JSONObject
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
                verifyPausedPaperRecovery()
                result.putString("stream", "CAPT_STAGE_C_RECOVERY_SMOKE_PASSED\nCAPT_QUEUE_RECOVERY_SMOKE_PASSED")
                finish(Activity.RESULT_OK,result)
                return
            }
            verifyDiagnosticHistory()
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
                        verifyQueueReportExport(id)
                        verifyMissingUsbDoesNotConsumeSuccessors(uri)
                    }
                }
            }
            // Leave one staged, never-activated job for the next process's recovery check.
            val recoveryUri = FileProvider.getUriForFile(context,"${context.packageName}.reportprovider",png)
            DocumentInspector.openSession(context,recoveryUri).use { source ->
                PrintQueueRuntime.enqueue(context,source,JobSpecification.create(1,"1",1),"offline-smoke-no-usb")
            }
            seedPausedPaperFixtures()
            listOf(png,jpeg,pdf).forEach { it.delete() }
            result.putString("stream", "CAPT_STAGE_C_RASTER_SMOKE_PASSED\nCAPT_QUEUE_REPORT_BUNDLE_SMOKE_PASSED\nCAPT_DIAGNOSTIC_SESSION_SMOKE_PASSED")
            finish(Activity.RESULT_OK,result)
        } catch (error: Throwable) {
            result.putString("stream", "CAPT_STAGE_C_RASTER_SMOKE_FAILED: ${error.javaClass.simpleName}: ${error.message}")
            finish(Activity.RESULT_CANCELED,result)
        }
    }

    /** A real pre-USB failure must remain ERROR and retain later queued documents. */
    private fun verifyMissingUsbDoesNotConsumeSuccessors(uri: android.net.Uri) {
        val context = targetContext
        DocumentInspector.openSession(context, uri).use { source ->
            val plan = JobSpecification.create(1, "1", 1)
            val first = PrintQueueRuntime.enqueue(context, source, plan, "offline-smoke-no-usb")
            val second = PrintQueueRuntime.enqueue(context, source, plan, "offline-smoke-no-usb")
            PrintQueueRuntime.activate(first); PrintQueueRuntime.activate(second)
            val done = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            com.cryonum.captusbprint.queue.PrintQueueWorker.execute {
                try { PrintQueueRuntime.drain {} } catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }
            check(done.await(30, java.util.concurrent.TimeUnit.SECONDS) && failure.get() == null)
            val records = PrintQueueRuntime.snapshot().associateBy { it.id }
            check(records.getValue(first).phase == QueuePhase.ERROR)
            check(records.getValue(first).failure == "PRINTER_DISCONNECTED")
            check(!records.getValue(first).usbStarted)
            check(PrintQueueRuntime.isPaused() && records.getValue(second).phase == QueuePhase.QUEUED)
            check(File(context.noBackupFilesDir, "print-queue/$second/source.bin").isFile)
            PrintQueueRuntime.cancel(second)
            PrintQueueRuntime.resumeAfterInspection()
        }
    }

    /** Synthetic paper boundaries written only in the debug emulator's private storage. No USB. */
    private fun seedPausedPaperFixtures() {
        val context = targetContext
        val history = File(context.noBackupFilesDir, "print-history.json")
        val json = JSONObject(history.readText())
        val items = json.getJSONArray("jobs")
        repeat(2) { number ->
            val id = System.currentTimeMillis() + 100_000 + number
            val directory = File(context.noBackupFilesDir, "print-queue/$id").apply { check(mkdir()) }
            val page = StoredCaptPage.write(File(directory, "page-0.bin"), produce = { accept ->
                var y = 0
                while (y < 7016) {
                    val lines = minOf(64, 7016 - y)
                    accept(y, ByteArray(620 * lines)); y += lines
                }
            }, encode = { _, _, _, _ -> byteArrayOf(1) }, cancelled = { false }, reserve = {})
            File(directory, "page-0.bin").copyTo(File(directory, "page-1.bin"))
            File(directory, "resume.json").writeText(JSONObject()
                .put("schemaVersion", 1).put("fixture", "synthetic-paper-pause")
                .put("resumeAllowed", true).put("confirmed", 1).put("copies", 1)
                .put("pages", org.json.JSONArray(listOf(0, 1))).put("orientation", "PORTRAIT")
                .put("rotation", 0).put("layout", "APP_FIT")
                .put("compressedBytes", JSONObject().put("0", page.compressedBytes).put("1", page.compressedBytes))
                .toString())
            if (number == 1) java.io.RandomAccessFile(File(directory, "page-1.bin"), "rw").use {
                it.seek(8); it.write(2) // Valid sizes, corrupt strip CRC: must stop BEFORE maintenance/USB.
            }
            items.put(JSONObject().put("id", id).put("format", "PAPER_SMOKE_$number")
                .put("sheets", 2).put("phase", "WAITING_PAPER").put("confirmed", 1)
                .put("usbStarted", true).put("failure", "PAPER_OUT"))
        }
        json.put("paused", true)
        val atomic = android.util.AtomicFile(history)
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) }
        catch (error: Throwable) { atomic.failWrite(out); throw error }
    }

    private fun verifyPausedPaperRecovery() {
        val context = targetContext
        check(PrintQueueRuntime.isPaused() && !PrintQueueRuntime.hasRunnableWork())
        val valid = PrintQueueRuntime.snapshot().single { it.format == "PAPER_SMOKE_0" }
        val corrupt = PrintQueueRuntime.snapshot().single { it.format == "PAPER_SMOKE_1" }
        check(valid.phase == QueuePhase.WAITING_PAPER && valid.confirmed == 1)
        check(PrintQueueRuntime.owns(valid.id) && PrintQueueRuntime.owns(corrupt.id))
        PrintQueueRuntime.requestPaperContinuation(valid.id)
        PrintQueueRuntime.continuePaper(valid.id) // Missing USB: keep waiting, never enqueue/replay the sheet.
        check(PrintQueueRuntime.snapshot().first { it.id == valid.id }.phase == QueuePhase.WAITING_PAPER)
        check(PrintQueueRuntime.snapshot().first { it.id == valid.id }.confirmed == 1)
        check(!PrintQueueRuntime.hasRunnableWork())
        val maintenance = com.cryonum.captusbprint.usb.CaptPrinterMaintenance.file(context)
        val previousClear = maintenance.readText()
        PrintQueueRuntime.requestPaperContinuation(corrupt.id)
        PrintQueueRuntime.continuePaper(corrupt.id)
        check(PrintQueueRuntime.snapshot().first { it.id == corrupt.id }.failure == "RESUME_SOURCE_INVALID")
        check(maintenance.readText() == previousClear) { "Corrupt spool attempted printer maintenance" }
        PrintQueueRuntime.requestClear()
        PrintQueueRuntime.clearRequested()
        check(PrintQueueRuntime.snapshot().isEmpty()) { "Clear left queue records" }
        check(File(context.noBackupFilesDir, "print-queue").listFiles()?.isEmpty() != false)
        check(!PrintQueueRuntime.hasWork())
        check(com.cryonum.captusbprint.usb.CaptPrinterStateStore.requiresClear(context))
        val report = JSONObject(maintenance.readText())
        check(!report.getBoolean("cleared") && report.getString("failureClass") == "PRINTER_DISCONNECTED")
        check(report.getJSONObject("protocolDiagnostics").isNull("reservedJobId"))
        val bundle = QueueReportBundle.create(context)
        try {
            val json = JSONObject(bundle.readText())
            check(json.getJSONArray("queue").length() == 0)
            check(!json.getJSONObject("printerMaintenance").getBoolean("cleared"))
            check(json.getJSONObject("printerReservation").getBoolean("requiresClear"))
        } finally { bundle.delete() }
    }

    /** Real Android settings/JSON/AtomicFile round trip; no physical USB is present. */
    private fun verifyDiagnosticHistory() {
        val context = targetContext
        val file = File(context.cacheDir, "diagnostic-history-smoke.json")
        file.delete()
        try {
            val history = DiagnosticHistory(file)
            check(history.save("""{"schemaVersion":1,"commands":[{"timeUtc":"2026-10-23T00:00:00Z","action":"GetBasicStatus","rawResponseFrameHex":"A0 E0 06 00 88 00","decoded":{"statusByte":136}}]}"""))
            val result = PrinterDiagnostics.run(context,
                com.cryonum.captusbprint.usb.UsbRow("offline-smoke-no-usb", "fixture", true,
                    vendorId = 0x04a9, productId = 0x2676), { false }, {})
            check(result.outcome == "incomplete")
            val event = mapOf("eventId" to "smoke-session", "timeUtc" to "2026-10-23T01:00:00Z",
                "action" to "PrinterDiagnostics", "decoded" to result.report)
            val snapshot = com.cryonum.captusbprint.core.DiagnosticJson.stringify(
                mapOf("schemaVersion" to 1, "commands" to listOf(event)))
            check(history.save(snapshot))
            check(history.save(snapshot)) // Saving a stale Activity snapshot cannot duplicate a session.
            check(history.save("""{"schemaVersion":1,"commands":[]}""")) // Nor erase the other Activity's result.
            val events = DiagnosticHistory(file).loadEvents()
            check(events.size == 2)
            check((events.first()["decoded"] as Map<*, *>)["statusByte"] == 136)
            val restored = events.last()["decoded"] as Map<*, *>
            check(restored["sessionId"] == result.report["sessionId"])
            check((restored["steps"] as List<*>).size == 4)
            check((restored["context"] as Map<*, *>).containsKey("android"))
            check(((restored["context"] as Map<*, *>)["app"] as Map<*, *>)["sourceRevision"] == BuildConfig.SOURCE_REVISION)
            check(JSONObject(file.readText()).getJSONObject("historyPolicy").getBoolean("wholeSessionEviction"))
            // Bound both bytes and event count without cutting the steps from retained sessions.
            val large = (1..40).map { n -> mapOf("eventId" to "large-$n",
                "timeUtc" to "2026-10-23T02:00:00Z", "action" to "PrinterDiagnostics",
                "decoded" to mapOf("sessionId" to n, "steps" to listOf("я".repeat(20_000)))) }
            check(history.save(com.cryonum.captusbprint.core.DiagnosticJson.stringify(mapOf("commands" to large))))
            val retained = history.loadEvents()
            check(retained.size in 1..20)
            check(file.length() <= DiagnosticHistory.MAX_FILE_BYTES)
            check(retained.all { ((it["decoded"] as Map<*, *>)["steps"] as List<*>).size == 1 })
        } finally { file.delete() }
    }

    /** Real Android JSON/FileProvider checks on this smoke's own cancelled fixture. No USB. */
    private fun verifyQueueReportExport(id: Long) {
        val context = targetContext
        val report = PrintQueueRuntime.reportFile(id)
        check(!report.exists()) // This never-activated fixture has no real USB evidence to overwrite.
        report.parentFile!!.mkdirs()
        fun export() = QueueReportBundle.create(context)
        try {
            report.writeText("{\"schemaVersion\":2,\"jobId\":$id,\"phase\":\"CANCELLED\",\"fixture\":\"queue-export-smoke\"}")
            val bundle = export()
            val text = bundle.readText()
            val json = JSONObject(text)
            check(json.getString("reportType") == "queue_bundle")
            check(!json.getBoolean("automaticRetry"))
            check(json.has("systemPrint") && json.has("latestPdfPrint") && json.has("usbDiagnostics"))
            check(json.getJSONArray("jobs").let { array -> (0 until array.length()).any {
                array.getJSONObject(it).optLong("jobId") == id
            } })
            check(!text.contains("source.bin") && !text.contains("fixture.png"))
            val uri = FileProvider.getUriForFile(context,"${context.packageName}.reportprovider",bundle)
            context.contentResolver.openInputStream(uri)!!.use { check(it.read() == '{'.code) }
            bundle.delete()
            for ((content, reason) in listOf("{broken-json" to "UNREADABLE_OR_INVALID",
                "x".repeat(1024 * 1024 + 1) to "SIZE_LIMIT")) {
                report.writeText(content)
                val rejected = export()
                val omissions = JSONObject(rejected.readText()).getJSONArray("omittedReports")
                check((0 until omissions.length()).any {
                    val entry = omissions.getJSONObject(it)
                    entry.optLong("jobId") == id && entry.getString("reason") == reason
                })
                rejected.delete()
            }
        } finally { report.delete() }
    }
}
