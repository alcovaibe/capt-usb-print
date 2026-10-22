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
import com.cryonum.captusbprint.core.CaptFrame
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
    private val cancelled: () -> Boolean
) : AutoCloseable {
    private val channel = object : BulkChannel {
        override fun write(data: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
            connection.bulkTransfer(outEp, data, offset, length, timeoutMillis)
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
        writer.writeExactly(frame, timeoutMillis = 15000, perCallTimeoutMillis = 2000)
    }

    /** Query sends one command and reads one exact bounded reply, no transparent retry. */
    fun query(command: Int, body: ByteArray = byteArrayOf()): ByteArray {
        sendOnly(command, body)
        return readReply(command)
    }

    /**
     * The measured LBP2900 replies use little-endian lengths. Some historic
     * frames also have a *different* plausible BCD length (e.g. LE 56 vs BCD 38).
     * Prefer LE and NEVER accept the BCD interpretation in the middle of a valid
     * LE reply, which would truncate an otherwise successful 56-byte answer.
     */
    private fun readReply(expectedCommand: Int): ByteArray {
        val first = readExactly(6, 5000)
        val actualCommand = (first[0].toInt() and 0xff) or
            ((first[1].toInt() and 0xff) shl 8)
        check(actualCommand == expectedCommand) { "Unexpected CAPT response command" }
        val length = CaptFrame.preferredReplyLength(first, 1024)
        check(length >= 6 && length <= 1024)
        val reply = if (length == 6) first
            else first + readExactly(length - 6, 6000)
        CaptFrame.parseReply(expectedCommand, reply)
        return reply
    }

    private fun readExactly(length: Int, totalTimeoutMs: Long): ByteArray {
        require(length in 1..1024)
        val buf = ByteArray(length)
        val deadline = SystemClock.elapsedRealtime() + totalTimeoutMs
        var got = 0
        while (got < length) {
            check(!cancelled()) { "Cancelled while waiting for CAPT reply" }
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Timed out reading CAPT response ($got/$length)" }
            val requested = length - got
            val count = connection.bulkTransfer(
                inEp, buf, got, requested, minOf(2000L, remaining).toInt())
            check(count in 1..requested) { "Incomplete CAPT reply ($got/$length)" }
            got += count
        }
        return buf
    }

    override fun close() {
        try { connection.releaseInterface(iface) } finally { connection.close() }
    }

    companion object {
        fun open(context: Context, key: String, cancelled: () -> Boolean): CaptUsbSession {
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
                return CaptUsbSession(conn, iface, input, out, cancelled)
            } catch (error: Exception) {
                conn.close()
                throw error
            }
        }
    }
}
