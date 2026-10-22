package com.cryonum.captusbprint

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ActivityNotFoundException
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
import com.cryonum.captusbprint.core.CaptTestPagePreparation
import com.cryonum.captusbprint.usb.CaptTestPrintEngine
import com.cryonum.captusbprint.usb.CaptTwoPagePrintEngine
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
import java.util.concurrent.atomic.AtomicBoolean

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
    private var permissionRequestPending = false
    private var captProbeInProgress = false
    private var rasterPreparationInProgress = false
    private var testPrintInProgress = false
    private val testPrintCancelled = AtomicBoolean(false)
    private var scanGeneration = 0
    private var receiverRegistered = false
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
        restoreDiagnosticEvents()
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
            refresh()
        })
        content.addView(button("Выбрать PDF / JPEG / PNG (без печати)") {
            startActivity(Intent(this, DocumentActivity::class.java))
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
            if (permissionRequestPending) {
                permissionRequestPending = false
                recordEvent("UsbPermission", "result", PermissionEvents.outcome)
            }
            refresh()
        }
        if (permissionRequestPending &&
            PermissionEvents.outcome != getString(R.string.permission_pending)
        ) {
            permissionRequestPending = false
            recordEvent("UsbPermission", "result", PermissionEvents.outcome)
            refresh()
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

    private fun refresh() {
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
        saveDiagnosticSnapshot()
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
                card.addView(button(getString(R.string.prepare_test_page)) {
                    prepareTestPageOffline()
                })
                card.addView(button(getString(R.string.test_page_print)) {
                    printExperimentalTestPage(row)
                })
                card.addView(button(getString(R.string.two_page_print)) {
                    confirmTwoPagePrint(row)
                })
                card.addView(button(getString(R.string.cancel_test_page)) {
                    if (testPrintInProgress) {
                        testPrintCancelled.set(true)
                        status.text = getString(R.string.test_page_cancel_requested)
                        recordEvent("CancelTestPage", "requested",
                            "User requested cancellation of the active experimental print")
                    } else {
                        status.text = getString(R.string.test_page_cancel_not_running)
                        recordEvent("CancelTestPage", "no_active_print",
                            "Cancellation requested; no experimental print is active")
                    }
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
        val device = manager.deviceList.values.firstOrNull { it.deviceName == row.deviceKey }
        if (device == null) {
            recordEvent("UsbPermission", "failed", "USB device no longer enumerated")
            refresh()
            return
        }
        if (manager.hasPermission(device)) {
            recordEvent("UsbPermission", "already_granted", "Permission from UsbManager")
            refresh()
            return
        }
        try {
            val permissionIntent = Intent(this, UsbPermissionReceiver::class.java).apply {
                action = PermissionEvents.ACTION
            }
            val pending = PendingIntent.getBroadcast(
                this, device.deviceId, permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            PermissionEvents.outcome = getString(R.string.permission_pending)
            status.text = PermissionEvents.outcome
            permissionRequestPending = true
            manager.requestPermission(device, pending)
        } catch (error: Exception) {
            permissionRequestPending = false
            PermissionEvents.outcome = "Ошибка запроса USB: " + error.javaClass.simpleName
            recordEvent("UsbPermission", "failed", PermissionEvents.outcome)
            refresh()
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
        if (captProbeInProgress || testPrintInProgress || rasterPreparationInProgress) return
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
            }
        }
    }

    private fun prepareTestPageOffline() {
        if (rasterPreparationInProgress || captProbeInProgress || testPrintInProgress) return
        rasterPreparationInProgress = true
        status.text = getString(R.string.preparing_test_page)
        worker.execute {
            val prepared = runCatching { CaptTestPagePreparation.prepare() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                rasterPreparationInProgress = false
                val details = prepared.fold(
                    { page ->
                        "A4 600dpi ${page.widthPixels}x${page.heightPixels}; " +
                            "bands=${page.bands}; rawBytes=${page.rawBytes}; " +
                            "compressedBytes=${page.compressedBytes}; " +
                            "maxBandBytes=${page.maxBandBytes}; " +
                            "sha256=${page.compressedSha256}; " +
                            "descriptorBytes=${page.descriptorBytes}; " +
                            "no page data sent to USB."
                    },
                    { error ->
                        "HISCOA local preparation failed: ${error.javaClass.simpleName}; no USB transfer."
                    }
                )
                val metrics = prepared.getOrNull()?.let { page ->
                    mapOf<String, Any?>(
                        "widthPixels" to page.widthPixels,
                        "heightPixels" to page.heightPixels,
                        "bands" to page.bands,
                        "rawBytes" to page.rawBytes,
                        "compressedBytes" to page.compressedBytes,
                        "maxBandBytes" to page.maxBandBytes,
                        "compressedSha256" to page.compressedSha256,
                        "descriptorBytes" to page.descriptorBytes,
                        "usbPrintCommandSent" to false
                    )
                } ?: emptyMap()
                recordEvent("PrepareTestPage", if (prepared.isSuccess) "success" else "failed",
                    details, decoded = metrics, reportLine = details)
                currentReport += "\n" + details + "\n"
                reportView.text = currentReport
                status.text = details
            }
        }
    }

    private fun printExperimentalTestPage(row: UsbRow) {
        if (testPrintInProgress || captProbeInProgress || rasterPreparationInProgress) return
        testPrintInProgress = true
        testPrintCancelled.set(false)
        status.text = getString(R.string.test_page_started)
        worker.execute {
            val result = CaptTestPrintEngine.run(applicationContext, row.deviceKey,
                { testPrintCancelled.get() }) { progress ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) status.text = progress
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                testPrintInProgress = false
                val last = result.trace.lastOrNull().orEmpty()
                val detail = "CAPT test print: ${result.outcome}; stage=${result.stage}; " +
                    "sentPrintDataBytes=${result.sentPrintDataBytes}; " +
                    "printerCompletedCounter=${result.printerConfirmedPages ?: "unavailable"}; $last"
                currentReport += "\n" + detail + "\n"
                reportView.text = currentReport
                recordEvent("PrintTestPage", result.outcome, result.summary,
                    result.lastStatusFrameHex,
                    mapOf<String, Any?>(
                        "stage" to result.stage,
                        "sentPrintDataBytes" to result.sentPrintDataBytes,
                        "compressedSha256" to result.compressedSha256,
                        "printerConfirmedPages" to result.printerConfirmedPages,
                        "trace" to result.trace,
                        "automaticRetry" to false
                    ), detail)
                status.text = result.summary
                Toast.makeText(this, result.summary, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmTwoPagePrint(row: UsbRow) {
        if (testPrintInProgress || captProbeInProgress || rasterPreparationInProgress) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.two_page_confirm_title))
            .setMessage(getString(R.string.two_page_confirm_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.two_page_confirm_action)) { _, _ ->
                printExperimentalTwoPages(row)
            }
            .show()
    }

    private fun printExperimentalTwoPages(row: UsbRow) {
        if (testPrintInProgress || captProbeInProgress || rasterPreparationInProgress) return
        testPrintInProgress = true
        testPrintCancelled.set(false)
        status.text = getString(R.string.two_page_started)
        worker.execute {
            val result = CaptTwoPagePrintEngine.run(applicationContext, row.deviceKey,
                { testPrintCancelled.get() }) { message ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) status.text = message
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                testPrintInProgress = false
                val detail = "CAPT two-page experiment: ${result.outcome}; stage=${result.stage}; " +
                    "sentPrintDataBytes=${result.sentPrintDataBytes}; " +
                    "confirmedPages=${result.printerConfirmedPages ?: "unavailable"}; " +
                    result.trace.lastOrNull().orEmpty()
                currentReport += "\n" + detail + "\n"
                reportView.text = currentReport
                recordEvent("PrintTwoTestPages", result.outcome, result.summary,
                    result.lastStatusFrameHex,
                    mapOf<String, Any?>(
                        "stage" to result.stage,
                        "requestedPages" to 2,
                        "oneReservedCaptJob" to true,
                        "sentPrintDataBytes" to result.sentPrintDataBytes,
                        "compressedSha256" to result.compressedSha256,
                        "printerConfirmedPages" to result.printerConfirmedPages,
                        "trace" to result.trace,
                        "automaticRetry" to false
                    ), detail)
                status.text = result.summary
                Toast.makeText(this, result.summary, Toast.LENGTH_LONG).show()
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
            "printedPages" to if (outcome == "printer_counter_confirmed") when (action) {
                "PrintTestPage" -> 1
                "PrintTwoTestPages" -> 2
                else -> null
            } else null
        ))
        if (diagnosticEvents.size > 64) diagnosticEvents.removeAt(0)
        saveDiagnosticSnapshot()
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
            "scan" to linkedMapOf(
                "available" to (snapshot != null),
                "deviceCount" to snapshot?.devices?.size,
                "devices" to (snapshot?.devices?.map {
                    linkedMapOf(
                        "label" to it.title,
                        "vendorId" to it.vendorId,
                        "productId" to it.productId,
                        "vendorIdHex" to String.format(Locale.ROOT, "%04X", it.vendorId),
                        "productIdHex" to String.format(Locale.ROOT, "%04X", it.productId),
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
            "note" to "USB scans are read-only. PrintTestPage is an explicitly initiated physical print attempt; printedPages counts only pages confirmed for that event by CAPT counters, not visually verified sheets. No document raster or serial number is included. Events are saved as private JSON; only the share button exports them."
        )
        return DiagnosticJson.stringify(report) + "\n"
    }

    private val diagnosticHistory: DiagnosticHistory by lazy {
        DiagnosticHistory(File(filesDir, "capt-diagnostic-latest.json"))
    }

    private fun saveDiagnosticSnapshot() {
        diagnosticHistory.save(jsonReport())
    }

    private fun restoreDiagnosticEvents() {
        diagnosticEvents.addAll(diagnosticHistory.loadEvents())
    }

    private fun shareReport() {
        try {
            val directory = File(cacheDir, "reports").apply { mkdirs() }
            val file = File.createTempFile("capt-diagnostic-", ".json", directory)
            file.writeText(jsonReport(), Charsets.UTF_8)
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
            val telegram = Intent(send).apply { setPackage("org.telegram.messenger") }
            if (telegram.resolveActivity(packageManager) != null) {
                try {
                    startActivity(telegram)
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent.createChooser(send, getString(R.string.share_title)))
                }
            } else {
                startActivity(Intent.createChooser(send, getString(R.string.share_title)))
            }
        } catch (error: Exception) {
            Toast.makeText(this, getString(R.string.export_failed,
                error.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}
