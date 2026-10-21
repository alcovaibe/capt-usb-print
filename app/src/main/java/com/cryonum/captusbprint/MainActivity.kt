package com.cryonum.captusbprint

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
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
import com.cryonum.captusbprint.usb.PermissionEvents
import com.cryonum.captusbprint.usb.UsbDiagnostic
import com.cryonum.captusbprint.usb.UsbRow
import com.cryonum.captusbprint.usb.UsbSnapshot
import com.cryonum.captusbprint.usb.UsbObservation
import com.cryonum.captusbprint.usb.UsbPermissionReceiver
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var reportView: TextView
    private lateinit var reportToggle: Button
    private var reportExpanded = false
    private var currentReport: String = ""
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
        content.addView(button(getString(R.string.refresh)) { refresh() })
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
        PermissionEvents.listener = { refresh() }
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
                    currentReport = "Diagnostic scan failed: " + reason +
                        "\nNo print commands were sent.\n"
                    reportView.text = currentReport
                }
            }
        }
    }

    private fun render(snapshot: UsbSnapshot) {
        currentReport = snapshot.report + "\n" + UsbObservation.reportLine() +
            "\nLast USB permission event: " + PermissionEvents.outcome + "\n"
        status.text = PermissionEvents.outcome
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
            refresh()
            return
        }
        if (manager.hasPermission(device)) {
            refresh()
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
            manager.requestPermission(device, pending)
        } catch (error: Exception) {
            PermissionEvents.outcome = "Ошибка запроса USB: " + error.javaClass.simpleName
            refresh()
        }
    }

    private fun shareReport() {
        if (currentReport.isBlank()) {
            refresh()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "CAPT USB Print A1 diagnostic")
                putExtra(Intent.EXTRA_TEXT, currentReport)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
        } catch (error: Exception) {
            Toast.makeText(this, getString(R.string.export_failed,
                error.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}
