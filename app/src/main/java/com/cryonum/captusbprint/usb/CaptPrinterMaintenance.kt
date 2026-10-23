package com.cryonum.captusbprint.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.AtomicFile
import com.cryonum.captusbprint.core.*
import java.io.File

/** One explicit user action. No USB reset, guessed JobID, raster or print-job replay. */
internal object CaptPrinterMaintenance {
    data class Result(val cleared: Boolean, val paperReady: Boolean, val failure: String?)
    fun file(context: Context) = File(context.noBackupFilesDir, "capt-printer-maintenance.json")
    fun deviceKey(context: Context): String {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.filter {
            UsbDeviceProfiles.identify(it.vendorId, it.productId)?.modelName == "Canon LBP2900"
        }.singleOrNull() ?: error("PRINTER_DISCONNECTED")
        check(usb.hasPermission(device)) { "USB_PERMISSION" }
        return device.deviceName
    }
    fun run(context: Context): Result {
        val trace = CaptProtocolTrace(phase = { "USER_REQUESTED_CLEAR" }, now = { SystemClock.elapsedRealtime() })
        trace.lifecycle = { CaptPrinterStateStore.record(context, it) }
        var result = Result(false, false, "CLEAR_NOT_CONFIRMED")
        try {
            CaptPrinterStateStore.requireClear(context, "CLEAR_IN_PROGRESS")
            // A cancelled test may be finishing its aligned exchange on the Activity worker.
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (CaptUsbOwnership.isOccupied() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            CaptUsbSession.open(context, deviceKey(context),
                { Thread.currentThread().isInterrupted }, trace, "EXPLICIT_PRINTER_CLEAR").use { usb ->
                val cleared = CaptPrinterRecovery.clearAndVerify(query = { command, payload ->
                    val raw = usb.query(command, payload)
                    trace.reply(command, raw, payload)
                    if (command == CaptJobProtocol.RESERVE_UNIT)
                        trace.reserved(CaptJobProtocol.parseReservedJob(raw))
                    raw
                }, now = { SystemClock.elapsedRealtime() }, pause = { Thread.sleep(it) })
                CaptPrinterStateStore.cleared(context, cleared.paperReady)
                result = Result(true, cleared.paperReady, null)
            }
        } catch (error: Throwable) {
            val code = error.message?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }
                ?: error.javaClass.simpleName
            trace.event("CLEAR_FAILED", mapOf("failureClass" to code))
            runCatching { CaptPrinterStateStore.requireClear(context, "CLEAR_NOT_CONFIRMED") }
            result = Result(false, false, code)
        }
        val atomic = AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            stream.write(DiagnosticJson.stringify(mapOf("schemaVersion" to 1,
                "cleared" to result.cleared, "paperReady" to result.paperReady,
                "failureClass" to result.failure, "generatedAtMillis" to System.currentTimeMillis(),
                "protocolDiagnostics" to trace.snapshot())).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
        return result
    }
}
