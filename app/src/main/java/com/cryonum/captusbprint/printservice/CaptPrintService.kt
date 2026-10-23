package com.cryonum.captusbprint.printservice

import android.os.Handler
import android.os.Looper
import android.print.PrintJobId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.cryonum.captusbprint.R
import com.cryonum.captusbprint.queue.*
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android owns this bound service's lifetime while jobs are active; no background FGS launch. */
class CaptPrintService : PrintService() {
    private val main = Handler(Looper.getMainLooper())
    private val intake = Executors.newSingleThreadExecutor()
    private val jobs = LinkedHashMap<PrintJobId, Handle>()
    private var connected = false
    private var drainScheduled = false
    private class Handle(val job: PrintJob, val request: SystemPrintRequest) {
        val generation = PrintQueueRuntime.cancelGeneration()
        val cancelled = AtomicBoolean(false)
        var importing = false
        var queueId: Long? = null
    }
    private val listener: () -> Unit = { if (connected) updateJobs() }
    private val tick = object : Runnable {
        override fun run() {
            if (!connected) return
            updateJobs()
            main.postDelayed(this, 2500)
        }
    }
    override fun onConnected() {
        connected = true
        PrintQueueRuntime.initialize(this)
        PrintQueueRuntime.addListener(listener)
        SystemPrintDiagnostics.record(this, "SERVICE_CONNECTED")
        // Tagged attempts are reconciled, never re-read or automatically printed again.
        activePrintJobs.forEach { onPrintJobQueued(it) }
        main.post(tick)
    }
    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = CanonPrinterDiscovery(this)

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!connected || jobs.containsKey(printJob.id)) return
        if (!printJob.isQueued && !printJob.isStarted && !printJob.isBlocked) return
        if (printJob.tag != null) { reconcile(printJob); return }
        SystemPrintDiagnostics.record(this, "JOB_RECEIVED")
        try {
            require(printJob.info.printerId == generatePrinterId(CanonUsbAvailability.LOCAL_ID))
            check(jobs.size < 4) { "SYSTEM_QUEUE_FULL" }
            val info = printJob.info
            val attributes = info.attributes
            val media = requireNotNull(attributes.mediaSize)
            val resolution = requireNotNull(attributes.resolution)
            val margins = requireNotNull(attributes.minMargins)
            val request = SystemPrintRequest(printJob.document.info.pageCount,
                info.pages?.map { SystemPageSelection.Range(it.start, it.end) }, info.copies,
                media.widthMils, media.heightMils, resolution.horizontalDpi, resolution.verticalDpi,
                attributes.colorMode, attributes.duplexMode,
                listOf(margins.leftMils, margins.topMils, margins.rightMils, margins.bottomMils))
            SystemPrintDiagnostics.record(this, "REQUEST_SNAPSHOT", request)
            request.validate()
            check(printJob.setTag(WAITING_TAG))
            check(printJob.start())
            jobs[printJob.id] = Handle(printJob, request)
            SystemPrintDiagnostics.record(this, "REQUEST_VALIDATED", request)
            updateJobs()
        } catch (error: Exception) {
            SystemPrintDiagnostics.record(this, "REQUEST_REJECTED", error = errorCode(error))
            printJob.fail(getString(if (errorCode(error) == "SYSTEM_QUEUE_FULL") R.string.system_queue_full
                else R.string.system_request_rejected))
        }
    }

    private fun reconcile(job: PrintJob) {
        val id = job.tag?.removePrefix(QUEUE_TAG)?.toLongOrNull()
        val record = PrintQueueRuntime.snapshot().firstOrNull { it.id == id }
        when (record?.phase) {
            QueuePhase.COUNTER_CONFIRMED -> { if (!job.isStarted) job.start(); job.complete() }
            QueuePhase.CANCELLED -> job.cancel()
            else -> {
                if (record != null && !record.phase.terminal) PrintQueueRuntime.cancel(record.id)
                job.fail(getString(R.string.system_interrupted))
            }
        }
        SystemPrintDiagnostics.record(this, "TAGGED_JOB_RECONCILED", queueId = id,
            error = record?.phase?.name ?: "INTAKE_INTERRUPTED")
    }

    private fun block(handle: Handle, reason: Int) {
        if (handle.job.isStarted) handle.job.block(getString(reason))
        else if (handle.job.isBlocked) handle.job.setStatus(getString(reason))
    }
    private fun begin(handle: Handle) {
        if (handle.cancelled.get() || handle.importing || handle.queueId != null) return
        val device = CanonUsbAvailability.grantedDevice(this)
        if (device == null) { block(handle, CanonUsbAvailability.reason(this)); return }
        if (PrintQueueRuntime.isPaused()) { block(handle, R.string.system_queue_paused); return }
        if (!PrintQueueRuntime.hasRoom()) { block(handle, R.string.system_queue_full); return }
        var descriptor: android.os.ParcelFileDescriptor? = null
        try {
            if (handle.job.isBlocked) check(handle.job.start())
            check(handle.job.setTag(INTAKE_TAG))
            descriptor = requireNotNull(handle.job.document.data)
            handle.importing = true
            handle.job.setStatus(getString(R.string.system_reading))
            val input = requireNotNull(descriptor)
            val deviceKey = device.deviceName
            intake.execute {
                val outcome = runCatching {
                    PrintQueueRuntime.enqueueSystem(applicationContext, input, handle.request,
                        deviceKey) { handle.cancelled.get() || handle.generation != PrintQueueRuntime.cancelGeneration() }
                }
                main.post {
                    handle.importing = false
                    outcome.fold(onSuccess = { id ->
                        handle.queueId = id
                        if (!connected || handle.cancelled.get() || handle.generation != PrintQueueRuntime.cancelGeneration()) {
                            PrintQueueRuntime.cancel(id)
                            if (connected) { handle.job.cancel(); jobs.remove(handle.job.id) }
                        } else {
                            try {
                                check(handle.job.setTag(QUEUE_TAG + id))
                                PrintQueueRuntime.activate(id)
                                SystemPrintDiagnostics.record(this, "QUEUE_ACCEPTED", handle.request, id)
                                scheduleDrain()
                            } catch (error: Exception) {
                                PrintQueueRuntime.cancel(id)
                                handle.job.fail(getString(R.string.system_intake_failed))
                                jobs.remove(handle.job.id)
                            }
                        }
                    }, onFailure = { error ->
                        SystemPrintDiagnostics.record(this, "INTAKE_FAILED", handle.request, error = errorCode(error))
                        if (connected) {
                            if (handle.cancelled.get() || error is CancellationException) handle.job.cancel()
                            else handle.job.fail(getString(R.string.system_intake_failed))
                            jobs.remove(handle.job.id)
                        }
                    })
                    if (connected) updateJobs()
                }
            }
        } catch (error: Exception) {
            runCatching { descriptor?.close() }
            handle.importing = false
            SystemPrintDiagnostics.record(this, "DATA_UNAVAILABLE", handle.request, error = errorCode(error))
            handle.job.fail(getString(R.string.system_intake_failed))
            jobs.remove(handle.job.id)
        }
    }

    private fun scheduleDrain() {
        if (!connected || drainScheduled || PrintQueueRuntime.isPaused() || !PrintQueueRuntime.hasRunnableWork()) return
        drainScheduled = true
        PrintQueueWorker.execute {
            try { PrintQueueRuntime.drain {} }
            catch (_: Throwable) { PrintQueueRuntime.serviceFailure() }
            finally { main.post { drainScheduled = false; if (connected) updateJobs() } }
        }
    }
    private fun updateJobs() {
        if (!connected) return
        val records = PrintQueueRuntime.snapshot().associateBy { it.id }
        jobs.values.toList().forEach { handle ->
            if (handle.generation != PrintQueueRuntime.cancelGeneration() && !handle.cancelled.get()) {
                handle.cancelled.set(true)
                handle.queueId?.let { PrintQueueRuntime.cancel(it) }
                if (!handle.importing && handle.queueId == null) {
                    handle.job.cancel(); jobs.remove(handle.job.id); return@forEach
                }
            }
            if (handle.job.isCancelled || handle.job.isFailed || handle.job.isCompleted) {
                handle.cancelled.set(true)
                handle.queueId?.let { PrintQueueRuntime.cancel(it) }
                jobs.remove(handle.job.id)
                return@forEach
            }
            val id = handle.queueId
            if (id == null) { begin(handle); return@forEach }
            val record = records[id] ?: return@forEach
            if (record.phase.terminal) {
                when (record.phase) {
                    QueuePhase.COUNTER_CONFIRMED -> {
                        if (!handle.job.isStarted) handle.job.start()
                        handle.job.setProgress(1f); handle.job.complete()
                    }
                    QueuePhase.CANCELLED -> handle.job.cancel()
                    QueuePhase.OUTCOME_UNKNOWN -> handle.job.fail(getString(R.string.system_outcome_unknown))
                    else -> handle.job.fail(getString(when (record.failure) {
                        "CAPT_STATUS_CONFLICT" -> R.string.failure_status_conflict
                        "CAPT_STATUS_INVALID" -> R.string.failure_status_invalid
                        else -> R.string.system_print_failed
                    }))
                }
                SystemPrintDiagnostics.record(this, "JOB_" + record.phase.name, queueId = id, error = record.failure)
                jobs.remove(handle.job.id)
            } else if (PrintQueueRuntime.isPaused() && record.phase == QueuePhase.QUEUED) {
                block(handle, R.string.system_queue_paused)
            } else {
                if (handle.job.isBlocked) handle.job.start()
                handle.job.setProgress(record.confirmed.toFloat() / record.sheets)
                handle.job.setStatus(getString(when (record.phase) {
                    QueuePhase.QUEUED -> R.string.queue_queued
                    QueuePhase.PREPARING -> R.string.queue_preparing
                    QueuePhase.WAITING_PRINTER -> R.string.queue_waiting_printer
                    QueuePhase.WAITING_CONFIRMATION -> R.string.queue_waiting_confirmation
                    else -> R.string.queue_transmitting
                }))
            }
        }
        if (jobs.values.any { it.queueId != null }) scheduleDrain()
    }
    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val handle = jobs[printJob.id]
        if (handle == null) { reconcile(printJob); return }
        handle.cancelled.set(true)
        handle.queueId?.let { PrintQueueRuntime.cancel(it) }
        if (!handle.importing && handle.queueId == null) { printJob.cancel(); jobs.remove(printJob.id) }
        else printJob.setStatus(getString(R.string.system_cancelling))
        SystemPrintDiagnostics.record(this, "CANCEL_REQUESTED", queueId = handle.queueId)
        updateJobs()
    }
    override fun onDisconnected() {
        connected = false
        main.removeCallbacks(tick)
        PrintQueueRuntime.removeListener(listener)
        jobs.values.forEach { handle ->
            handle.cancelled.set(true)
            handle.queueId?.let { runCatching { PrintQueueRuntime.cancel(it) } }
        }
        jobs.clear()
        SystemPrintDiagnostics.record(this, "SERVICE_DISCONNECTED")
    }
    override fun onDestroy() {
        if (connected) onDisconnected()
        intake.shutdown() // queued cancelled imports still run finally/close their descriptors
        super.onDestroy()
    }
    private fun errorCode(error: Throwable): String = error.message?.takeIf {
        it.matches(Regex("[A-Z_]{1,64}"))
    } ?: error.javaClass.simpleName
    companion object {
        private const val WAITING_TAG = "capt-waiting"
        private const val INTAKE_TAG = "capt-intake"
        private const val QUEUE_TAG = "capt-queue:"
    }
}
