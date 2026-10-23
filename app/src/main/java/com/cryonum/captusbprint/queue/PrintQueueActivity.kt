package com.cryonum.captusbprint.queue

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import com.cryonum.captusbprint.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cryonum.captusbprint.R

class PrintQueueActivity : Activity() {
    private lateinit var jobs: LinearLayout
    private val exportWorker = Executors.newSingleThreadExecutor()
    private var exporting = false
    private val listener: () -> Unit = { if (!isDestroyed && !isFinishing) render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        PrintQueueRuntime.initialize(this)
        jobs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { addView(jobs) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        setContentView(scroll)
    }
    override fun onStart() { super.onStart(); PrintQueueRuntime.addListener(listener); render() }
    override fun onStop() { PrintQueueRuntime.removeListener(listener); super.onStop() }
    private fun render() {
        jobs.removeAllViews()
        jobs.addView(TextView(this).apply { text = getString(R.string.queue_title); textSize = 22f })
        jobs.addView(TextView(this).apply { text = getString(R.string.queue_explanation) })
        val records = PrintQueueRuntime.snapshot()
        val maintenance = PrintQueueRuntime.maintenanceState()
        val busy = PrintQueueRuntime.isMaintenanceBusy()
        val maintenanceText = when (maintenance) {
            "BUSY" -> R.string.queue_clear_busy
            "REQUIRED" -> R.string.queue_clear_required
            "CLEARED" -> R.string.queue_clear_verified
            "PAPER_NOT_READY" -> R.string.queue_paper_not_ready
            else -> null
        }
        maintenanceText?.let { jobs.addView(TextView(this).apply { setText(it) }) }
        jobs.addView(Button(this).apply {
            setText(R.string.queue_clear_all); isEnabled = !busy
            setOnClickListener {
                AlertDialog.Builder(this@PrintQueueActivity).setMessage(R.string.queue_clear_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.queue_clear_all) { _, _ ->
                        runCatching { PrintQueueService.clear(this@PrintQueueActivity) }.onFailure {
                            Toast.makeText(this@PrintQueueActivity, R.string.queue_start_failed, Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }
        })
        if (PrintQueueRuntime.isPaused() && !busy && maintenance != "REQUIRED" &&
            records.none { it.phase == QueuePhase.WAITING_PAPER }) {
            jobs.addView(TextView(this).apply { text = getString(R.string.queue_paused) })
            jobs.addView(Button(this).apply {
                text = getString(R.string.queue_resume)
                setOnClickListener {
                    AlertDialog.Builder(this@PrintQueueActivity).setMessage(R.string.queue_resume_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.queue_resume) { _, _ ->
                            runCatching {
                                PrintQueueService.start(this@PrintQueueActivity)
                                PrintQueueRuntime.resumeAfterInspection()
                            }.onFailure { Toast.makeText(this@PrintQueueActivity,
                                R.string.queue_start_failed, Toast.LENGTH_LONG).show() }
                        }.show()
                }
            })
        }
        if (records.isEmpty()) jobs.addView(TextView(this).apply { setText(R.string.queue_empty) })
        records.asReversed().forEach { record ->
            jobs.addView(TextView(this).apply {
                text = getString(R.string.queue_item, record.id.toString().takeLast(6),
                    if (record.format == "SYSTEM_PDF") getString(R.string.system_pdf_format) else record.format,
                    phase(record.phase), record.confirmed, record.sheets) +
                    (record.failure?.let { "\n" + getString(R.string.queue_failure, failure(it)) } ?: "")
                textSize = 16f; setPadding(0, 24, 0, 8); setTextIsSelectable(true)
            })
            if (record.phase == QueuePhase.WAITING_PAPER) {
                jobs.addView(TextView(this).apply { setText(R.string.queue_paper_help) })
                jobs.addView(Button(this).apply {
                    text = getString(R.string.queue_paper_continue, record.confirmed + 1)
                    isEnabled = !busy
                    setOnClickListener {
                        AlertDialog.Builder(this@PrintQueueActivity)
                            .setMessage(getString(R.string.queue_paper_confirm, record.confirmed + 1))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.queue_paper_continue_action) { _, _ ->
                                runCatching { PrintQueueService.continuePaper(this@PrintQueueActivity, record.id) }
                                    .onFailure { Toast.makeText(this@PrintQueueActivity,
                                        R.string.queue_start_failed, Toast.LENGTH_LONG).show() }
                            }.show()
                    }
                })
            }
            if (!record.phase.terminal) jobs.addView(Button(this).apply {
                setText(if (record.cancelRequested) R.string.system_cancelling else R.string.queue_cancel)
                isEnabled = !record.cancelRequested
                setOnClickListener {
                    runCatching { PrintQueueRuntime.cancel(record.id) }.onFailure {
                        Toast.makeText(this@PrintQueueActivity,R.string.failure_preparation,Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        jobs.addView(Button(this).apply {
            setText(R.string.queue_share_reports)
            isEnabled = !exporting
            setOnClickListener { shareAllReports() }
        })
    }
    private fun shareAllReports() {
        if (exporting) return
        exporting = true; render()
        com.cryonum.captusbprint.printservice.SystemPrintDiagnostics.record(this, "MANUAL_SNAPSHOT")
        exportWorker.execute {
            val result = runCatching { QueueReportBundle.create(applicationContext) }
            runOnUiThread {
                exporting = false
                if (isDestroyed || isFinishing) { result.getOrNull()?.delete(); return@runOnUiThread }
                render()
                result.fold({ shareReportFile(it) }, {
                    Toast.makeText(this,R.string.document_share_failed,Toast.LENGTH_LONG).show()
                })
            }
        }
    }
    private fun shareReportFile(copy: File) {
        runCatching {
            check(copy.length() in 1..QueueReportBundle.MAX_BUNDLE_BYTES)
            val uri = FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.reportprovider",copy)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"; putExtra(Intent.EXTRA_STREAM,uri)
                clipData = ClipData.newUri(contentResolver,copy.name,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent,getString(R.string.queue_share_reports)))
        }.onFailure { Toast.makeText(this,R.string.document_share_failed,Toast.LENGTH_LONG).show() }
    }
    override fun onDestroy() { exportWorker.shutdown(); super.onDestroy() }
    private fun failure(code: String) = getString(when (code) {
        "PRINTER_DISCONNECTED" -> R.string.failure_disconnected
        "USB_PERMISSION" -> R.string.failure_permission
        "USB_BUSY" -> R.string.failure_busy
        "USB_TIMEOUT" -> R.string.failure_timeout
        "CAPT_STATUS_CONFLICT" -> R.string.failure_status_conflict
        "CAPT_STATUS_INVALID" -> R.string.failure_status_invalid
        "PRINTER_STATUS", "PRINTER_ERROR" -> R.string.failure_printer
        "PAPER_OUT" -> R.string.queue_waiting_paper
        "PAPER_BOUNDARY_UNKNOWN" -> R.string.queue_paper_boundary_unknown
        "PRINTER_CLEAR_REQUIRED" -> R.string.queue_clear_required
        "RESUME_SOURCE_INVALID" -> R.string.queue_resume_source_invalid
        "PROCESS_INTERRUPTED", "SERVICE_INTERRUPTED" -> R.string.failure_interrupted
        "HISTORY_UNREADABLE" -> R.string.failure_history
        else -> R.string.failure_preparation
    })
    private fun phase(phase: QueuePhase): String = getString(when (phase) {
        QueuePhase.QUEUED -> R.string.queue_queued
        QueuePhase.PREPARING -> R.string.queue_preparing
        QueuePhase.WAITING_PRINTER -> R.string.queue_waiting_printer
        QueuePhase.TRANSMITTING -> R.string.queue_transmitting
        QueuePhase.WAITING_CONFIRMATION -> R.string.queue_waiting_confirmation
        QueuePhase.WAITING_PAPER -> R.string.queue_waiting_paper
        QueuePhase.COUNTER_CONFIRMED -> R.string.queue_confirmed
        QueuePhase.CANCELLED -> R.string.queue_cancelled
        QueuePhase.ERROR -> R.string.queue_error
        QueuePhase.OUTCOME_UNKNOWN -> R.string.queue_unknown
    })
}
