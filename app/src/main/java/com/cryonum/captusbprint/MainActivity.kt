package com.cryonum.captusbprint

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
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
import com.cryonum.captusbprint.usb.UsbPermissionReceiver
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var reportView: TextView
    private var currentReport: String = ""
    private var scanGeneration = 0
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) refresh()
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
        content.addView(text("Обезличенный отчёт:").apply { textSize = 19f })
        reportView = text("")
        reportView.textSize = 13f
        content.addView(reportView)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
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
        refresh()
    }

    override fun onPause() {
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
        currentReport = snapshot.report + "\nLast USB permission event: " +
            PermissionEvents.outcome + "\n"
        status.text = PermissionEvents.outcome
        reportView.text = currentReport
        devices.removeAllViews()
        if (snapshot.devices.isEmpty()) devices.addView(text(getString(R.string.no_devices)))
        snapshot.devices.forEach { row ->
            devices.addView(text(row.title +
                if (row.permissionGranted) " — доступ предоставлен" else " — нужен доступ"))
            if (!row.permissionGranted) {
                devices.addView(button(getString(R.string.permission_request)) {
                    requestUsbAccess(row)
                })
            }
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
