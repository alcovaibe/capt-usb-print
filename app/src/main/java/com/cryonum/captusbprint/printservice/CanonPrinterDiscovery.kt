package com.cryonum.captusbprint.printservice

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession
import android.printservice.PrintService
import com.cryonum.captusbprint.MainActivity
import com.cryonum.captusbprint.R

internal object CanonUsbAvailability {
    const val LOCAL_ID = "canon-lbp2900-usb"
    fun devices(context: Context): List<UsbDevice> =
        (context.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.filter {
            it.vendorId == 0x04A9 && it.productId == 0x2676
        }
    fun grantedDevice(context: Context): UsbDevice? = devices(context).singleOrNull()?.takeIf {
        (context.getSystemService(Context.USB_SERVICE) as UsbManager).hasPermission(it)
    }
    fun reason(context: Context): Int {
        val devices = devices(context)
        return when {
            devices.isEmpty() -> R.string.system_usb_missing
            devices.size != 1 -> R.string.system_usb_multiple
            grantedDevice(context) == null -> R.string.system_usb_permission
            else -> R.string.system_usb_ready
        }
    }
}

/** Stable model/slot identity, no serial query and no CAPT discovery traffic. */
internal class CanonPrinterDiscovery(private val service: PrintService) : PrinterDiscoverySession() {
    private val main = Handler(Looper.getMainLooper())
    private var discovering = false
    private val tracking = HashSet<PrinterId>()
    private val tick = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            refresh()
            if (discovering || tracking.isNotEmpty()) main.postDelayed(this, 2500)
        }
    }
    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        discovering = true; poll()
    }
    override fun onStopPrinterDiscovery() { discovering = false; poll() }
    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) { refresh() }
    override fun onStartPrinterStateTracking(printerId: PrinterId) { tracking.add(printerId); poll() }
    override fun onStopPrinterStateTracking(printerId: PrinterId) { tracking.remove(printerId); poll() }
    override fun onDestroy() { main.removeCallbacks(tick); tracking.clear(); discovering = false }
    private fun poll() {
        main.removeCallbacks(tick)
        if (discovering || tracking.isNotEmpty()) main.post(tick)
    }
    private fun refresh() {
        if (isDestroyed) return
        val id = service.generatePrinterId(CanonUsbAvailability.LOCAL_ID)
        val ready = CanonUsbAvailability.grantedDevice(service) != null
        val busy = com.cryonum.captusbprint.queue.PrintQueueRuntime.snapshot().any {
            it.phase != com.cryonum.captusbprint.queue.QueuePhase.QUEUED && !it.phase.terminal
        }
        val capabilities = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addResolution(PrintAttributes.Resolution("600dpi", "600 dpi", 600, 600), true)
            // Same provisional ~11 mm software allowance as C; never applied again to system PDF.
            .setMinMargins(PrintAttributes.Margins(433, 433, 433, 433))
            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            .setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE).build()
        val info = PendingIntent.getActivity(service, 7,
            Intent(service, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val printer = PrinterInfo.Builder(id, "Canon LBP2900 (CAPT USB)",
            if (!ready) PrinterInfo.STATUS_UNAVAILABLE else if (busy) PrinterInfo.STATUS_BUSY else PrinterInfo.STATUS_IDLE)
            .setDescription(service.getString(CanonUsbAvailability.reason(service)))
            .setCapabilities(capabilities).setInfoIntent(info).build()
        addPrinters(listOf(printer))
    }
}
