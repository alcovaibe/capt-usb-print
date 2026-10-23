package com.cryonum.captusbprint.usb

import android.content.Context
import android.util.AtomicFile
import com.cryonum.captusbprint.core.DiagnosticJson
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Durable reservation evidence, never a reusable JobID or an automatic recovery request. */
internal object CaptPrinterStateStore {
    private val cancellation = AtomicLong()
    @Volatile private var uncertain = false
    @Volatile var maintenance = false
        private set
    fun beginMaintenance() { maintenance = true }
    fun endMaintenance() { maintenance = false }
    fun generation() = cancellation.get()
    fun requestCancellation() { cancellation.incrementAndGet() }
    fun file(context: Context) = File(context.noBackupFilesDir, "capt-printer-state.json")

    @Synchronized fun snapshot(context: Context): JSONObject = try {
        val f = file(context)
        if (!f.exists() && !File(f.path + ".bak").exists()) JSONObject().put("requiresClear", false)
        else {
            check(f.length() <= 64 * 1024)
            JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() })
        }
    } catch (_: Exception) { JSONObject().put("requiresClear", true).put("state", "UNREADABLE") }

    @Synchronized fun requiresClear(context: Context) = maintenance || uncertain || snapshot(context).optBoolean("requiresClear", true)

    @Synchronized fun record(context: Context, trace: Map<String, Any?>) {
        if (trace["reservedJobId"] == null) return
        if (trace["releaseAcknowledged"] != true) uncertain = true
        write(context, linkedMapOf(
            "schemaVersion" to 1, "requiresClear" to (trace["releaseAcknowledged"] != true),
            "state" to if (trace["releaseAcknowledged"] == true) "RELEASE_ACKNOWLEDGED" else "RESERVATION_OPEN",
            "sessionId" to trace["sessionId"], "reservedJobId" to trace["reservedJobId"],
            "abortAcknowledged" to trace["abortAcknowledged"], "endAcknowledged" to trace["endAcknowledged"],
            "releaseAcknowledged" to trace["releaseAcknowledged"], "updatedAtMillis" to System.currentTimeMillis()))
        uncertain = trace["releaseAcknowledged"] != true
    }
    @Synchronized fun requireClear(context: Context, state: String) {
        uncertain = true
        write(context, mapOf("schemaVersion" to 1, "requiresClear" to true,
            "state" to state, "updatedAtMillis" to System.currentTimeMillis()))
    }
    @Synchronized fun cleared(context: Context, paperReady: Boolean) {
        write(context, mapOf("schemaVersion" to 1, "requiresClear" to false,
            "state" to "CLEAR_VERIFIED", "paperReady" to paperReady,
            "updatedAtMillis" to System.currentTimeMillis()))
        uncertain = false
    }
    private fun write(context: Context, data: Map<String, Any?>) {
        val atomic = AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            stream.write(DiagnosticJson.stringify(data).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
