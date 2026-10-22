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
import com.cryonum.captusbprint.engine.EndpointFact
import com.cryonum.captusbprint.engine.InterfaceFact
import com.cryonum.captusbprint.engine.LinkSelection
import com.cryonum.captusbprint.engine.UsbLinkSelector
import java.util.Locale

data class UsbRow(
    val deviceKey: String, // Internal only; never included in export
    val title: String,
    val permissionGranted: Boolean,
    val profile: UsbDeviceProfile? = null,
    val vendorId: Int,
    val productId: Int
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
        if (allDevices.isEmpty()) {
            report.appendLine("Android UsbManager.deviceList returned zero devices.")
            report.appendLine("USB permission cannot be requested before OS enumeration.")
            report.appendLine("Root cause unknown; no inference about printer/cable failure.")
        }
        if (allDevices.size > 32) report.appendLine("Only first 32 devices included (bounded report).")
        allDevices.take(32).forEachIndexed { number, device ->
            val vidPid = hex(device.vendorId) + ":" + hex(device.productId)
            val allowed = manager.hasPermission(device)
            val profile = UsbDeviceProfiles.identify(device.vendorId, device.productId)
            rows += UsbRow(
                deviceKey = device.deviceName,
                title = if (profile != null) {
                    profile.modelName + " — USB #" + (number + 1) + " · " + vidPid
                } else {
                    "USB #" + (number + 1) + " — " + vidPid
                },
                permissionGranted = allowed,
                profile = profile,
                vendorId = device.vendorId,
                productId = device.productId
            )
            report.appendLine("Device #" + (number + 1) + " VID:PID=" + vidPid)
            report.appendLine("  known model match=" + (profile?.modelName ?: "none"))
            if (profile != null) {
                report.appendLine("  model match basis=exact VID:PID table entry; USB descriptors and CAPT response still require validation")
            }
            report.appendLine("  deviceClass=" + device.deviceClass +
                " subclass=" + device.deviceSubclass + " protocol=" + device.deviceProtocol)
            report.appendLine("  USB permission=" + allowed)
            report.appendLine("  interfaces=" + device.interfaceCount)
            val inspected = mutableListOf<UsbInterface>()
            val facts = mutableListOf<InterfaceFact>()
            for (i in 0 until minOf(device.interfaceCount, 16)) {
                val iface = device.getInterface(i)
                inspected += iface
                val endpointFacts = mutableListOf<EndpointFact>()
                report.appendLine("  interface[" + i + "] id=" + iface.id +
                    " alt=" + iface.alternateSetting + " class=" + iface.interfaceClass +
                    " subclass=" + iface.interfaceSubclass + " protocol=" + iface.interfaceProtocol)
                report.appendLine("    endpoints=" + iface.endpointCount)
                for (j in 0 until minOf(iface.endpointCount, 32)) {
                    val ep = iface.getEndpoint(j)
                    endpointFacts += EndpointFact(ep.address, ep.type)
                    report.appendLine("    endpoint[" + j + "] addr=0x" +
                        hex(ep.address) + " " + direction(ep) + " " +
                        transferType(ep.type) + " maxPacket=" + ep.maxPacketSize +
                        " interval=" + ep.interval)
                }
                // A truncated descriptor must not look like a uniquely usable link.
                if (iface.endpointCount > 32) endpointFacts += EndpointFact(-1, 2)
                facts += InterfaceFact(iface.id, iface.alternateSetting, endpointFacts)
            }
            if (device.interfaceCount > 16) report.appendLine("  remaining interfaces omitted")
            val selection = if (device.interfaceCount > 16) LinkSelection.Ambiguous
                else UsbLinkSelector.select(facts)
            report.appendLine("  validated BULK link selection=" + when (selection) {
                is LinkSelection.Unique -> "unique interfaceId=" + selection.interfaceId +
                    " alt=" + selection.alternateSetting +
                    " IN=0x" + hex(selection.inAddress) +
                    " OUT=0x" + hex(selection.outAddress)
                LinkSelection.None -> "none"
                LinkSelection.Ambiguous -> "ambiguous (no claim)"
            })

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
                        when (selection) {
                            LinkSelection.None -> report.appendLine(
                                "  claim=not attempted (no bidirectional BULK interface)")
                            LinkSelection.Ambiguous -> report.appendLine(
                                "  claim=not attempted (ambiguous descriptors)")
                            is LinkSelection.Unique -> {
                                val iface = inspected.singleOrNull {
                                    it.id == selection.interfaceId &&
                                        it.alternateSetting == selection.alternateSetting
                                }
                                if (iface == null) {
                                    report.appendLine("  claim=not attempted (interface identity ambiguous)")
                                } else {
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
                            }
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
        report.appendLine("This scan sent no CAPT command. Printing is not implemented; printed pages: NONE.")
        report.appendLine("No serial numbers, complete device paths, document or payload logged.")
        return UsbSnapshot(rows, report.toString())
    }
}
