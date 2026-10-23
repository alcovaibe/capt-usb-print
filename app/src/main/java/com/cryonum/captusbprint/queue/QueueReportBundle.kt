package com.cryonum.captusbprint.queue

import android.content.Context
import com.cryonum.captusbprint.BuildConfig
import com.cryonum.captusbprint.FirstPdfPrintAttempt
import com.cryonum.captusbprint.printservice.SystemPrintDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** An explicit, immutable export of existing sanitized reports, never document/spool files.
 * Reads and validates one bounded report at a time; the full bundle is not held in RAM.
 */
internal object QueueReportBundle {
    private const val MAX_PART_BYTES = 1024 * 1024
    private const val MAX_PARTS_BYTES = 7 * 1024 * 1024
    const val MAX_BUNDLE_BYTES = 8L * 1024 * 1024

    fun create(context: Context): File {
        val records = PrintQueueRuntime.snapshot().asReversed()
        val omissions = JSONArray()
        var used = 0
        fun report(source: File, kind: String, id: Long? = null): String? {
            fun omitted(reason: String): String? {
                omissions.put(JSONObject().put("kind", kind).put("jobId", id ?: JSONObject.NULL)
                    .put("reason", reason))
                return null
            }
            if (!source.isFile) return omitted("NOT_AVAILABLE")
            if (source.length() !in 1..MAX_PART_BYTES.toLong()) return omitted("SIZE_LIMIT")
            return try {
                // Do not use AtomicFile.openRead here: export must not rename a live writer's backup.
                val bytes = source.inputStream().buffered().use { input ->
                    val output = ByteArrayOutputStream()
                    val block = ByteArray(8192)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        check(output.size() + count <= MAX_PART_BYTES)
                        output.write(block, 0, count)
                    }
                    output.toByteArray()
                }
                val json = JSONObject(String(bytes, Charsets.UTF_8)).toString()
                val size = json.toByteArray(Charsets.UTF_8).size
                if (used + size > MAX_PARTS_BYTES) omitted("BUNDLE_LIMIT")
                else { used += size; json }
            } catch (_: Exception) { omitted("UNREADABLE_OR_INVALID") }
        }
        val directory = File(context.cacheDir, "reports").apply { mkdirs() }
        val destination = File.createTempFile("capt-queue-", ".json", directory)
        try {
            destination.bufferedWriter(Charsets.UTF_8).use { writer ->
                val summaries = JSONArray()
                records.forEach { r -> summaries.put(JSONObject().put("jobId", r.id)
                    .put("format", r.format).put("phase", r.phase.name).put("sheets", r.sheets)
                    .put("confirmed", r.confirmed).put("failureClass", r.failure ?: JSONObject.NULL)) }
                val header = JSONObject().put("schemaVersion", 1).put("reportType", "queue_bundle")
                    .put("appVersion", BuildConfig.VERSION_NAME).put("sourceRevision", BuildConfig.SOURCE_REVISION)
                    .put("generatedAtMillis", System.currentTimeMillis()).put("automaticRetry", false)
                    .put("queue", summaries).toString()
                writer.write(header.dropLast(1))
                writer.write(",\"systemPrint\":")
                writer.write(report(SystemPrintDiagnostics.file(context), "systemPrint") ?: "null")
                writer.write(",\"latestPdfPrint\":")
                writer.write(report(FirstPdfPrintAttempt.reportFile(context), "latestPdfPrint") ?: "null")
                writer.write(",\"usbDiagnostics\":")
                writer.write(report(File(context.filesDir, "capt-diagnostic-latest.json"), "usbDiagnostics") ?: "null")
                writer.write(",\"printerMaintenance\":")
                writer.write(report(com.cryonum.captusbprint.usb.CaptPrinterMaintenance.file(context), "printerMaintenance") ?: "null")
                writer.write(",\"printerReservation\":")
                writer.write(report(com.cryonum.captusbprint.usb.CaptPrinterStateStore.file(context), "printerReservation") ?: "null")
                writer.write(",\"jobs\":[")
                var separator = ""
                records.forEach { record ->
                    report(PrintQueueRuntime.reportFile(record.id), "job", record.id)?.let { json ->
                        writer.write(separator); writer.write(json); separator = ","
                    }
                }
                writer.write("],\"omittedReports\":")
                writer.write(omissions.toString()); writer.write("}\n")
            }
            check(destination.length() in 1..MAX_BUNDLE_BYTES)
            directory.listFiles()?.filter { it.name.startsWith("capt-queue-") && it != destination }
                ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
            return destination
        } catch (error: Throwable) { destination.delete(); throw error }
    }
}
