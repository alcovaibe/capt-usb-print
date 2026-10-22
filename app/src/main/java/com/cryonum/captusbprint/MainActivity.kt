package com.cryonum.captusbprint

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.WindowInsets
import android.util.TypedValue
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cryonum.captusbprint.BuildConfig
import com.cryonum.captusbprint.core.DiagnosticJson
import com.cryonum.captusbprint.usb.PermissionEvents
import com.cryonum.captusbprint.usb.CaptStatusProbe
import com.cryonum.captusbprint.usb.CaptStatusProbeException
import com.cryonum.captusbprint.usb.UsbDiagnostic
import com.cryonum.captusbprint.usb.UsbRow
import com.cryonum.captusbprint.usb.UsbSnapshot
import com.cryonum.captusbprint.usb.UsbObservation
import com.cryonum.captusbprint.usb.UsbPermissionReceiver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var reportView: TextView
    private lateinit var reportToggle: Button
    private var reportExpanded = false
    private var currentReport: String = ""
    private var lastSnapshot: UsbSnapshot? = null
    private val diagnosticEvents = mutableListOf<Map<String, Any?>>()
    private var shareAfterScan = false
    private var permissionSharePending = false
    private var captProbeInProgress = false
    private var scanGeneration = 0
    private var receiverRegistered = false
    // ACTION_USB_DEVICE_ATTACHED is an ACTIVITY intent, not a normal broadcast.
    // Observe the UsbManager list only while our Activity is visible; never
    // repeatedly probe/open a printer when there is no actual enumeration change.
    private val monitor = Handler(Looper.getMainLooper())
    private var monitoring = false
    private var observedKeys: Set<String>? = null
    private val pollUsb = object : Runnable {
        override fun run() {
            if (!monitoring) return
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val keys = runCatching { manager.deviceList.keys.toSet() }.getOrNull()
            if (keys != null && keys != observedKeys) {
                observedKeys = keys
                UsbObservation.record(keys.size)
                refresh()
            }
            monitor.postDelayed(this, 2500L)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                // Wake the list monitor promptly, including when another USB device remains.
                monitor.removeCallbacks(pollUsb)
                monitor.post(pollUsb)
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String): TextView = TextView(this).apply {
        this.text = value
        textSize = 16f
        setPadding(0, dp(6), 0, dp(6))
        setTextIsSelectable(true)
    }

    private fun button(title: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = title
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(text(getString(R.string.stage_title)).apply { textSize = 23f })
        content.addView(text(getString(R.string.no_printing)))
        status = text(getString(R.string.permission_not_requested))
        content.addView(status)
        content.addView(button(getString(R.string.refresh)) {
            recordEvent("RefreshUsb", "requested", "User requested USB rescan")
            refresh(shareOnComplete = true)
        })
        content.addView(button(getString(R.string.share_report)) { shareReport() })
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(devices)
        content.addView(text(getString(R.string.report_label)).apply { textSize = 19f })
        reportToggle = button(getString(R.string.show_report)) {
            reportExpanded = !reportExpanded
            reportView.visibility = if (reportExpanded) View.VISIBLE else View.GONE
            reportToggle.text = getString(
                if (reportExpanded) R.string.hide_report else R.string.show_report
            )
        }
        content.addView(reportToggle)
        reportView = text("").apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setHorizontallyScrolling(false)
            visibility = View.GONE
        }
        content.addView(reportView)
        val scrolling = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        // Android 15/16 edge-to-edge: leave space for status/navigation bars
        // and display cutouts, including when font size or orientation changes.
        scrolling.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                content.setPadding(dp(16) + safe.left, dp(16) + safe.top,
                    dp(16) + safe.right, dp(24) + safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                content.setPadding(dp(16) + insets.systemWindowInsetLeft,
                    dp(16) + insets.systemWindowInsetTop,
                    dp(16) + insets.systemWindowInsetRight,
                    dp(24) + insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scrolling)
        scrolling.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        PermissionEvents.listener = {
            if (permissionSharePending) {
                permissionSharePending = false
                recordEvent("UsbPermission", "result", PermissionEvents.outcome)
                refresh(shareOnComplete = true)
            } else refresh()
        }
        if (permissionSharePending &&
            PermissionEvents.outcome != getString(R.string.permission_pending)
        ) {
            permissionSharePending = false
            recordEvent("UsbPermission", "result", PermissionEvents.outcome)
            refresh(shareOnComplete = true)
        }
        monitoring = true
        observedKeys = null
        monitor.post(pollUsb)
    }

    override fun onPause() {
        monitoring = false
        monitor.removeCallbacks(pollUsb)
        PermissionEvents.listener = null
        super.onPause()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        scanGeneration += 1
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refresh(shareOnComplete: Boolean = false) {
        if (shareOnComplete) shareAfterScan = true
        val generation = ++scanGeneration
        status.text = PermissionEvents.outcome + "\nЧтение USB…"
        worker.execute {
            val scan = runCatching { UsbDiagnostic.scan(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != scanGeneration) return@runOnUiThread
                scan.onSuccess { render(it) }.onFailure {
                    val reason = it.javaClass.simpleName
                    status.text = getString(R.string.scan_failed, reason)
                    lastSnapshot = null
                    currentReport = "Diagnostic scan failed: " + reason +
                        "\nNo print commands were sent.\n"
                    reportView.text = currentReport
                    recordEvent("UsbScan", "failed", reason)
                }
                if (shareAfterScan) {
                    shareAfterScan = false
                    shareReport()
                }
            }
        }
    }

    private fun render(snapshot: UsbSnapshot) {
        lastSnapshot = snapshot
        currentReport = snapshot.report + "\n" + UsbObservation.reportLine() +
            "\nUSB permission event in current session: " + PermissionEvents.outcome +
            "\nCurrent USB permission state is listed per device above.\n"
        val knownCapt = snapshot.devices.firstOrNull { it.profile?.protocolFamily == "CAPT" }
        status.text = if (knownCapt?.permissionGranted == true) {
            getString(R.string.permission_granted)
        } else {
            PermissionEvents.outcome
        }
        reportView.text = currentReport
        devices.removeAllViews()
        if (snapshot.devices.isEmpty()) {
            devices.addView(text(getString(R.string.no_devices)))
            devices.addView(text(getString(R.string.no_devices_hint)).apply { textSize = 14f })
        }
        snapshot.devices.forEach { row ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    val background = TypedValue()
                    theme.resolveAttribute(android.R.attr.colorBackground, background, true)
                    setColor(background.data)
                    val border = status.currentTextColor and 0x00ffffff or 0x44000000
                    setStroke(dp(1), border)
                    cornerRadius = dp(12).toFloat()
                }
            }
            card.addView(text(row.title).apply { textSize = 18f })
            card.addView(text(getString(
                if (row.permissionGranted) R.string.permission_granted else R.string.permission_not_requested
            )))
            if (!row.permissionGranted) {
                card.addView(button(getString(R.string.permission_request)) {
                    requestUsbAccess(row)
                })
            } else if (row.profile?.protocolFamily == "CAPT") {
                card.addView(button(getString(R.string.probe_capt_status)) {
                    probeCaptStatus(row)
                })
                card.addView(button(getString(R.string.probe_capt_extended_status)) {
                    probeCaptExtendedStatus(row)
                })
                card.addView(button(getString(R.string.probe_capt_input_status)) {
                    probeCaptInputStatus(row)
                })
                card.addView(button(getString(R.string.probe_capt_printer_info)) {
                    probeCaptPrinterInfo(row)
                })
            }
            devices.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
    }

    private fun requestUsbAccess(row: UsbRow) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        // Device path is only used inside the process, never placed into exported report.
        val device = manager.deviceList.values.firstOrNull { it.deviceName == row.deviceKey }
        if (device == null) {
            recordEvent("UsbPermission", "failed", "USB device no longer enumerated")
            refresh(shareOnComplete = true)
            return
        }
        if (manager.hasPermission(device)) {
            recordEvent("UsbPermission", "already_granted", "Permission from UsbManager")
            refresh(shareOnComplete = true)
            return
        }
        try {
            val permissionIntent = Intent(this, UsbPermissionReceiver::class.java).apply {
                action = PermissionEvents.ACTION
            }
            // Explicit receiver avoids implicit mutable PendingIntent restrictions.
            // MUTABLE permits UsbManager to fill in EXTRA_PERMISSION_GRANTED/EXTRA_DEVICE.
            val pending = PendingIntent.getBroadcast(
                this, device.deviceId, permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            PermissionEvents.outcome = getString(R.string.permission_pending)
            status.text = PermissionEvents.outcome
            permissionSharePending = true
            manager.requestPermission(device, pending)
        } catch (error: Exception) {
            permissionSharePending = false
            PermissionEvents.outcome = "Ошибка запроса USB: " + error.javaClass.simpleName
            recordEvent("UsbPermission", "failed", PermissionEvents.outcome)
            refresh(shareOnComplete = true)
        }
    }

    private fun probeCaptStatus(row: UsbRow) = runCaptProbe(row, "GetBasicStatus") {
        CaptStatusProbe.query(applicationContext, row.deviceKey)
    }

    private fun probeCaptExtendedStatus(row: UsbRow) = runCaptProbe(row, "GetExtendedStatus") {
        CaptStatusProbe.queryExtended(applicationContext, row.deviceKey)
    }

    private fun probeCaptInputStatus(row: UsbRow) = runCaptProbe(row, "GetInputStatus") {
        CaptStatusProbe.queryInputStatus(applicationContext, row.deviceKey)
    }

    private fun probeCaptPrinterInfo(row: UsbRow) = runCaptProbe(row, "GetPrinterInfo") {
        CaptStatusProbe.queryPrinterInfo(applicationContext, row.deviceKey)
    }

    private fun runCaptProbe(
        row: UsbRow,
        commandName: String,
        query: () -> com.cryonum.captusbprint.usb.CaptStatusProbeResult
    ) {
        // Avoid duplicate USB commands if the user taps a status action repeatedly.
        if (captProbeInProgress) return
        captProbeInProgress = true
        status.text = getString(R.string.probe_capt_running, commandName)
        worker.execute {
            val result = runCatching { query() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                captProbeInProgress = false
                val line = result.fold(
                    { it.reportLine },
                    { error ->
                        val reason = if (error is CaptStatusProbeException) {
                            error.message ?: getString(R.string.probe_capt_unknown_error)
                        } else {
                            "USB error (" + error.javaClass.simpleName + ")"
                        }
                        "CAPT $commandName: failed; reason=$reason; no print/page data sent."
                    }
                )
                currentReport += "\n" + line + "\n"
                reportView.text = currentReport
                result.fold(
                    { response ->
                        recordEvent(commandName, "success", response.summary,
                            response.rawFrameHex, response.decoded, response.reportLine)
                    },
                    { error ->
                        recordEvent(commandName, "failed", error.message ?: error.javaClass.simpleName,
                            (error as? CaptStatusProbeException)?.rawFrameHex, emptyMap(), line)
                    }
                )
                val message = result.fold(
                    { it.summary },
                    { error ->
                        val reason = if (error is CaptStatusProbeException) {
                            error.message ?: getString(R.string.probe_capt_unknown_error)
                        } else {
                            "USB error (" + error.javaClass.simpleName + ")"
                        }
                        getString(R.string.probe_capt_failed, reason)
                    }
                )
                status.text = message
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                shareReport()
            }
        }
    }

    private fun utcNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun recordEvent(
        action: String,
        outcome: String,
        summary: String,
        rawFrameHex: String? = null,
        decoded: Map<String, Any?> = emptyMap(),
        reportLine: String? = null
    ) {
        diagnosticEvents.add(linkedMapOf(
            "timeUtc" to utcNow(),
            "action" to action,
            "outcome" to outcome,
            "summary" to summary,
            "rawResponseFrameHex" to rawFrameHex,
            "decoded" to decoded,
            "reportText" to reportLine,
            "printedPages" to 0
        ))
        if (diagnosticEvents.size > 64) diagnosticEvents.removeAt(0)
    }

    private fun jsonReport(): String {
        val snapshot = lastSnapshot
        val report = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "generatedAtUtc" to utcNow(),
            "app" to linkedMapOf(
                "name" to "CAPT USB Print",
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "sourceRevision" to BuildConfig.SOURCE_REVISION
            ),
            // scan.report is the original device diagnostic, not a reconstruction
            // from labels. It intentionally excludes complete USB paths and serials.
            "scan" to linkedMapOf(
                "available" to (snapshot != null),
                "deviceCount" to snapshot?.devices?.size,
                "devices" to (snapshot?.devices?.map {
                    linkedMapOf(
                        "label" to it.title,
                        "permissionGranted" to it.permissionGranted,
                        "profile" to it.profile?.modelName,
                        "protocolFamily" to it.profile?.protocolFamily
                    )
                } ?: emptyList<Any>()),
                "reportText" to (snapshot?.report ?: currentReport),
                "usbObservation" to UsbObservation.reportLine(),
                "permissionEventInSession" to PermissionEvents.outcome
            ),
            "commands" to diagnosticEvents.toList(),
            "note" to "Only manually initiated read-only CAPT requests; raw frames are device replies, not document data. Decoded fields are provisional until physically validated."
        )
        return DiagnosticJson.stringify(report) + "\n"
    }

    private fun shareReport() {
        if (currentReport.isBlank()) {
            refresh(shareOnComplete = true)
            return
        }
        try {
            // Unique immutable snapshot: a later diagnostic must not overwrite a
            // file already selected by Telegram or Android Sharesheet.
            val directory = File(cacheDir, "reports").apply { mkdirs() }
            val file = File.createTempFile("capt-diagnostic-", ".json", directory)
            file.writeText(jsonReport(), Charsets.UTF_8)
            // Keep a bounded set of cache files for grants to previously shared URIs.
            directory.listFiles()?.sortedByDescending { it.lastModified() }
                ?.drop(24)?.forEach { it.delete() }
            val uri = FileProvider.getUriForFile(
                this, "${BuildConfig.APPLICATION_ID}.reportprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Prefer Telegram proper; Android's chooser remains the fallback when
            // it is not installed or cannot accept a JSON document on this build.
            val telegram = Intent(send).apply { setPackage("org.telegram.messenger") }
            if (telegram.resolveActivity(packageManager) != null) {
                startActivity(telegram)
            } else {
                startActivity(Intent.createChooser(send, getString(R.string.share_title)))
            }
        } catch (error: Exception) {
            Toast.makeText(this, getString(R.string.export_failed,
                error.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}
