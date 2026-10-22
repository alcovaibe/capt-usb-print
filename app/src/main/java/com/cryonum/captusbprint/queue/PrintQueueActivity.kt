package com.cryonum.captusbprint.queue

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import com.cryonum.captusbprint.BuildConfig
import java.io.File
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cryonum.captusbprint.R

class PrintQueueActivity : Activity() {
    private lateinit var jobs: LinearLayout
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
        jobs.addView(Button(this).apply {
            setText(R.string.system_report)
            setOnClickListener { shareSystemReport() }
        })
        if (PrintQueueRuntime.isPaused()) {
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
        val records = PrintQueueRuntime.snapshot()
        if (records.isEmpty()) jobs.addView(TextView(this).apply { setText(R.string.queue_empty) })
        records.asReversed().forEach { record ->
            jobs.addView(TextView(this).apply {
                text = getString(R.string.queue_item, record.id.toString().takeLast(6),
                    record.format, phase(record.phase), record.confirmed, record.sheets) +
                    (record.failure?.let { "\n" + getString(R.string.queue_failure, failure(it)) } ?: "")
                textSize = 16f; setPadding(0, 24, 0, 8); setTextIsSelectable(true)
            })
            if (!record.phase.terminal) jobs.addView(Button(this).apply {
                setText(R.string.queue_cancel)
                setOnClickListener {
                    runCatching { PrintQueueRuntime.cancel(record.id) }.onFailure {
                        Toast.makeText(this@PrintQueueActivity,R.string.failure_preparation,Toast.LENGTH_LONG).show()
                    }
                }
            })
            if (PrintQueueRuntime.reportFile(record.id).isFile) jobs.addView(Button(this).apply {
                setText(R.string.document_report)
                setOnClickListener { shareJobReport(record.id) }
            })
        }
    }
    private fun shareJobReport(id: Long) {
        shareReportFile(PrintQueueRuntime.reportFile(id))
    }
    private fun shareSystemReport() {
        com.cryonum.captusbprint.printservice.SystemPrintDiagnostics.record(this, "MANUAL_SNAPSHOT")
        shareReportFile(com.cryonum.captusbprint.printservice.SystemPrintDiagnostics.file(this))
    }
    private fun shareReportFile(source: File) {
        runCatching {
            check(source.length() in 1..1024*1024)
            val directory = File(cacheDir,"reports").apply { mkdirs() }
            val copy = File.createTempFile("capt-job-", ".json", directory)
            copy.writeText(source.readText(Charsets.UTF_8),Charsets.UTF_8)
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
            val uri = FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.reportprovider",copy)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"; putExtra(Intent.EXTRA_STREAM,uri)
                clipData = ClipData.newUri(contentResolver,copy.name,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent,getString(R.string.document_report)))
        }.onFailure { Toast.makeText(this,R.string.document_share_failed,Toast.LENGTH_LONG).show() }
    }
    private fun failure(code: String) = getString(when (code) {
        "PRINTER_DISCONNECTED" -> R.string.failure_disconnected
        "USB_PERMISSION" -> R.string.failure_permission
        "USB_BUSY" -> R.string.failure_busy
        "USB_TIMEOUT" -> R.string.failure_timeout
        "PRINTER_STATUS", "PRINTER_ERROR" -> R.string.failure_printer
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
        QueuePhase.COUNTER_CONFIRMED -> R.string.queue_confirmed
        QueuePhase.CANCELLED -> R.string.queue_cancelled
        QueuePhase.ERROR -> R.string.queue_error
        QueuePhase.OUTCOME_UNKNOWN -> R.string.queue_unknown
    })
}
