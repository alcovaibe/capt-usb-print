package com.cryonum.captusbprint.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.cryonum.captusbprint.core.BulkChannel
import com.cryonum.captusbprint.core.BulkIo
import com.cryonum.captusbprint.BuildConfig
import com.cryonum.captusbprint.core.CaptFrame
import com.cryonum.captusbprint.core.CaptProtocolTrace
import com.cryonum.captusbprint.core.CaptReplyReader
import com.cryonum.captusbprint.engine.EndpointFact
import com.cryonum.captusbprint.engine.InterfaceFact
import com.cryonum.captusbprint.engine.LinkSelection
import com.cryonum.captusbprint.engine.UsbLinkSelector

/**
 * A single-owner, closeable Android USB CAPT session.
 * The Activity's serial executor owns it exclusively for the complete job.
 * Never retries commands with potentially state-changing side effects.
 */
internal class CaptUsbSession private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val cancelled: () -> Boolean,
    private val ownership: CaptUsbOwnership.Lease,
    private val protocol: CaptProtocolTrace
) : AutoCloseable {
    private var writingCommand = 0
    private val channel = object : BulkChannel {
        override fun write(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            val count = connection.bulkTransfer(outEp, data, offset, length, timeoutMillis)
            protocol.transfer("OUT", writingCommand, offset, length, count)
            return count
        }
        override fun read(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
            connection.bulkTransfer(inEp, data, offset, length, timeoutMillis)
    }
    private val writer = BulkIo(channel, cancelled = cancelled)

    fun sendOnly(command: Int, body: ByteArray = byteArrayOf()) {
        check(!cancelled()) { "Cancelled before command" }
        sendFrame(CaptFrame.encode(command, body))
    }

    fun sendFrame(frame: ByteArray) {
        check(!cancelled()) { "Cancelled before CAPT frame" }
        require(frame.size in 4..65535)
        writingCommand = (frame[0].toInt() and 255) or ((frame[1].toInt() and 255) shl 8)
        try { writer.writeExactly(frame, timeoutMillis = 15000, perCallTimeoutMillis = 2000) }
        catch (error: Exception) {
            protocol.event("WRITE_FAILED", mapOf("command" to writingCommand,
                "type" to error.javaClass.simpleName,
                "acceptedBytes" to (error as? com.cryonum.captusbprint.core.TransferUncertainException)?.acceptedBytes))
            throw error
        }
    }

    /** Query sends one command and reads one exact bounded reply, no transparent retry. */
    fun query(command: Int, body: ByteArray = byteArrayOf()): ByteArray {
        protocol.begin(command)
        sendOnly(command, body)
        return CaptReplyReader(channel, protocol, { SystemClock.elapsedRealtime() }, cancelled).read(command)
    }

    override fun close() {
        try {
            val released = connection.releaseInterface(iface)
            protocol.event("USB_INTERFACE_RELEASED", mapOf("released" to released))
        }
        finally {
            try { connection.close() } finally {
                ownership.close()
                protocol.event("USB_CLOSED", mapOf("captReleaseAcknowledgedByClose" to false))
            }
        }
    }

    companion object {
        fun open(context: Context, key: String, cancelled: () -> Boolean,
                 protocol: CaptProtocolTrace = CaptProtocolTrace(), owner: String = "CAPT_PRINT"): CaptUsbSession {
            protocol.context(mapOf("owner" to owner, "sourceRevision" to BuildConfig.SOURCE_REVISION,
                "appVersion" to BuildConfig.VERSION_NAME, "openedAtEpochMillis" to System.currentTimeMillis(),
                "pid" to android.os.Process.myPid(),
                "threadId" to Thread.currentThread().id))
            protocol.event("USB_LEASE_REQUESTED")
            val ownership = try { CaptUsbOwnership.acquire() } catch (error: Exception) {
                protocol.event("USB_LEASE_REJECTED", mapOf("type" to error.javaClass.simpleName))
                throw error
            }
            protocol.event("USB_LEASE_ACQUIRED")
            try {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = usb.deviceList.values.singleOrNull { it.deviceName == key }
                ?: throw IllegalStateException("Canon printer is not connected")
            val profile = UsbDeviceProfiles.identify(device.vendorId, device.productId)
            check(profile?.modelName == "Canon LBP2900" && profile.protocolFamily == "CAPT") {
                "Unknown printer profile; refusing CAPT job"
            }
            check(usb.hasPermission(device)) { "USB access was not granted" }
            check(device.interfaceCount in 1..16) { "Unsupported USB interface count" }
            val facts = (0 until device.interfaceCount).map {
                val iface = device.getInterface(it)
                check(iface.endpointCount in 1..32) { "Too many endpoints" }
                InterfaceFact(iface.id, iface.alternateSetting,
                    (0 until iface.endpointCount).map { j ->
                        val ep = iface.getEndpoint(j)
                        EndpointFact(ep.address, ep.type)
                    })
            }
            val selection = UsbLinkSelector.select(facts) as? LinkSelection.Unique
                ?: throw IllegalStateException("No unique BULK IN/OUT channel")
            check(selection.alternateSetting == 0) { "Unsupported alternate USB setting" }
            val iface = (0 until device.interfaceCount).map { device.getInterface(it) }
                .singleOrNull {
                    it.id == selection.interfaceId && it.alternateSetting == selection.alternateSetting
                } ?: throw IllegalStateException("USB interface is ambiguous")
            check(iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER &&
                iface.interfaceSubclass == 1) { "Not a standard USB printer interface" }
            val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            val out = endpoints.singleOrNull {
                it.address == selection.outAddress &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_OUT
            } ?: throw IllegalStateException("No unambiguous BULK OUT")
            val input = endpoints.singleOrNull {
                it.address == selection.inAddress &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_IN
            } ?: throw IllegalStateException("No unambiguous BULK IN")
            val conn = usb.openDevice(device)
                ?: throw IllegalStateException("Failed to open device: busy or disconnected")
            try {
                check(conn.claimInterface(iface, false)) { "Failed to claim USB without force" }
                protocol.event("USB_INTERFACE_CLAIMED", mapOf("interfaceId" to iface.id,
                    "alternateSetting" to iface.alternateSetting, "inEndpoint" to input.address,
                    "outEndpoint" to out.address, "inMaxPacketSize" to input.maxPacketSize,
                    "outMaxPacketSize" to out.maxPacketSize, "force" to false))
                return CaptUsbSession(conn, iface, input, out, cancelled, ownership, protocol)
            } catch (error: Throwable) {
                conn.close()
                throw error
            }
            } catch (error: Throwable) {
                ownership.close()
                protocol.event("USB_OPEN_FAILED", mapOf("type" to error.javaClass.simpleName))
                throw error
            }
        }
    }
}
