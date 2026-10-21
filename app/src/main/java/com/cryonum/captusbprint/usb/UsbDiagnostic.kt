package com.cryonum.captusbprint.usb

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.cryonum.captusbprint.BuildConfig
import java.util.Locale

data class UsbRow(
    val deviceKey: String, // Internal only; never included in export
    val title: String,
    val permissionGranted: Boolean
)
data class UsbSnapshot(val devices: List<UsbRow>, val report: String)

object UsbDiagnostic {
    private fun hex(value: Int): String =
        String.format(Locale.ROOT, "%04X", value and 0xffff)

    private fun transferType(value: Int): String = when (value) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        else -> "UNKNOWN(" + value + ")"
    }

    private fun direction(endpoint: UsbEndpoint): String =
        if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"

    private fun isBidirectionalBulk(iface: UsbInterface): Boolean {
        var hasIn = false
        var hasOut = false
        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true
                if (endpoint.direction == UsbConstants.USB_DIR_OUT) hasOut = true
            }
        }
        return hasIn && hasOut
    }

    fun scan(context: Context): UsbSnapshot {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val report = StringBuilder(4096)
        report.appendLine("CAPT USB Print — diagnostic stage A / no printing")
        report.appendLine("Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        report.appendLine("Revision: " + BuildConfig.SOURCE_REVISION)
        report.appendLine("Device model: " + Build.MANUFACTURER + " " + Build.MODEL)
        report.appendLine("API level: " + Build.VERSION.SDK_INT)
        report.appendLine("OS build: " + Build.DISPLAY)
        report.appendLine("ABIs: " + Build.SUPPORTED_ABIS.joinToString(","))
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE).toString() }
            .getOrElse { "unavailable (" + it.javaClass.simpleName + ")" }
        report.appendLine("Measured memory page size (bytes): " + pageSize)
        report.appendLine("USB Host feature: " +
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
        report.appendLine("Permission state is obtained from UsbManager; no data is transmitted.")
        report.appendLine("")

        val allDevices = manager.deviceList.values.sortedWith(
            compareBy<UsbDevice>({ it.vendorId }, { it.productId }, { it.deviceId })
        )
        val rows = mutableListOf<UsbRow>()
        report.appendLine("Connected USB device count: " + allDevices.size)
        if (allDevices.size > 32) report.appendLine("Only first 32 devices included (bounded report).")
        allDevices.take(32).forEachIndexed { number, device ->
            val vidPid = hex(device.vendorId) + ":" + hex(device.productId)
            val allowed = manager.hasPermission(device)
            rows += UsbRow(
                deviceKey = device.deviceName,
                title = "USB #" + (number + 1) + " — " + vidPid,
                permissionGranted = allowed
            )
            report.appendLine("Device #" + (number + 1) + " VID:PID=" + vidPid)
            report.appendLine("  deviceClass=" + device.deviceClass +
                " subclass=" + device.deviceSubclass + " protocol=" + device.deviceProtocol)
            report.appendLine("  USB permission=" + allowed)
            report.appendLine("  interfaces=" + device.interfaceCount)
            val candidates = mutableListOf<UsbInterface>()
            for (i in 0 until minOf(device.interfaceCount, 16)) {
                val iface = device.getInterface(i)
                report.appendLine("  interface[" + i + "] id=" + iface.id +
                    " alt=" + iface.alternateSetting + " class=" + iface.interfaceClass +
                    " subclass=" + iface.interfaceSubclass + " protocol=" + iface.interfaceProtocol)
                report.appendLine("    endpoints=" + iface.endpointCount)
                for (j in 0 until minOf(iface.endpointCount, 32)) {
                    val ep = iface.getEndpoint(j)
                    report.appendLine("    endpoint[" + j + "] addr=0x" +
                        hex(ep.address) + " " + direction(ep) + " " +
                        transferType(ep.type) + " maxPacket=" + ep.maxPacketSize +
                        " interval=" + ep.interval)
                }
                if (isBidirectionalBulk(iface)) candidates += iface
            }
            if (device.interfaceCount > 16) report.appendLine("  remaining interfaces omitted")
            report.appendLine("  candidate bidirectional BULK interfaces=" + candidates.size)

            if (!allowed) {
                report.appendLine("  open/claim=not attempted (no USB permission)")
                return@forEachIndexed
            }
            val started = SystemClock.elapsedRealtime()
            // Diagnostic-only: read DEVICE descriptor and try to claim a unique interface.
            // No controlTransfer, bulkTransfer, requestQueue or print command is sent.
            try {
                val connection = manager.openDevice(device)
                if (connection == null) {
                    report.appendLine("  open=failed (possibly busy or disconnected)")
                } else {
                    try {
                        report.appendLine("  open=success")
                        val raw = connection.rawDescriptors
                        val safeDeviceDescriptor = UsbReportPolicy.deviceHeader(raw)
                        // Only first 18 bytes of the standard DEVICE descriptor, not
                        // serial string descriptor, print data or complete USB paths.
                        report.appendLine("  device descriptor header=" + safeDeviceDescriptor)
                        when (candidates.size) {
                            0 -> report.appendLine("  claim=not attempted (no bidirectional BULK interface)")
                            1 -> {
                                val iface = candidates.single()
                                val claimed = connection.claimInterface(iface, false)
                                report.appendLine("  claim(interfaceId=" + iface.id + ",force=false)=" + claimed)
                                if (claimed) {
                                    try {
                                        report.appendLine("  claim probe=read-only; no transfer")
                                    } finally {
                                        report.appendLine("  release=" + connection.releaseInterface(iface))
                                    }
                                }
                            }
                            else -> report.appendLine("  claim=not attempted (ambiguous interface selection)")
                        }
                    } finally {
                        connection.close()
                        report.appendLine("  close=completed")
                    }
                }
            } catch (error: Exception) {
                report.appendLine("  probe error=" + error.javaClass.simpleName)
            }
            report.appendLine("  probe duration ms=" + (SystemClock.elapsedRealtime() - started))
        }
        report.appendLine("")
        report.appendLine("CAPT traffic: NONE. Printed pages: NONE.")
        report.appendLine("No serial numbers, complete device paths, document or payload logged.")
        return UsbSnapshot(rows, report.toString())
    }
}
