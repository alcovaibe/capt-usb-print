package com.cryonum.captusbprint

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.cryonum.captusbprint.core.CaptDiagnosticSession
import com.cryonum.captusbprint.core.CaptDiagnosticException
import com.cryonum.captusbprint.core.CaptDiagnosticResult
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import com.cryonum.captusbprint.printservice.CaptPrintService
import com.cryonum.captusbprint.usb.CaptStatusProbe
import com.cryonum.captusbprint.usb.CaptUsbOwnership
import com.cryonum.captusbprint.usb.UsbRow
import java.time.Instant
import java.util.UUID

/** Explicit foreground diagnostics. No queue mutation, sharing or print commands. */
internal object PrinterDiagnostics {
    fun run(context: Context, row: UsbRow, cancelled: () -> Boolean,
        progress: (String) -> Unit): CaptDiagnosticResult {
        val facts = linkedMapOf<String, Any?>(
            "android" to linkedMapOf("apiLevel" to Build.VERSION.SDK_INT,
                "release" to Build.VERSION.RELEASE, "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL, "osBuild" to Build.DISPLAY,
                "abis" to Build.SUPPORTED_ABIS.toList()),
            "device" to mapOf("vendorId" to row.vendorId, "productId" to row.productId,
                "permissionAtSelection" to row.permissionGranted, "profile" to row.profile?.modelName),
            "printService" to printServiceState(context),
            "queueAtStart" to queueState())
        val session = CaptDiagnosticSession(UUID.randomUUID().toString(),
            { Instant.now().toString() }, { SystemClock.elapsedRealtime() }, { Thread.sleep(it) },
            cancelled, facts, progress)
        return session.collect { block ->
            val lease = try { CaptUsbOwnership.acquire() } catch (_: IllegalStateException) {
                throw CaptDiagnosticException("USB занят другой операцией. Повтори диагностику после её завершения.",
                    stage = "ownership")
            }
            lease.use {
                // Do not take the printer from an unfinished or concurrently scheduled job.
                if (PrintQueueRuntime.hasWork()) throw CaptDiagnosticException(
                    "В очереди есть незавершённые задания. Заверши или отмени их перед диагностикой.", stage = "queue")
                CaptStatusProbe.withSession(context, row.deviceKey, { facts["usbLink"] = it }) { query ->
                    block { command -> query(command.code) }
                }
            }
        }
    }

    private fun printServiceState(context: Context): Map<String, Any?> = try {
        val raw = Settings.Secure.getString(context.contentResolver, "enabled_print_services")
        val own = ComponentName(context, CaptPrintService::class.java)
        mapOf("enabled" to (raw?.split(':')?.any { ComponentName.unflattenFromString(it) == own } ?: false),
            "source" to "Settings.Secure.enabled_print_services",
            "liveConnectionState" to "NOT_QUERIED")
    } catch (_: Exception) { mapOf("enabled" to null, "reason" to "SETTING_UNAVAILABLE") }

    private fun queueState(): Map<String, Any?> = mapOf(
        "paused" to PrintQueueRuntime.isPaused(),
        "jobs" to PrintQueueRuntime.snapshot().map { record -> mapOf(
            "jobId" to record.id, "phase" to record.phase.name, "format" to record.format,
            "sheets" to record.sheets, "confirmed" to record.confirmed,
            "usbStarted" to record.usbStarted, "failure" to record.failure) })
}
