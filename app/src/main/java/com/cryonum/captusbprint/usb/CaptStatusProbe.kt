package com.cryonum.captusbprint.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.cryonum.captusbprint.core.CaptBasicStatusParser
import com.cryonum.captusbprint.core.CaptFrame
import com.cryonum.captusbprint.engine.EndpointFact
import com.cryonum.captusbprint.engine.InterfaceFact
import com.cryonum.captusbprint.engine.LinkSelection
import com.cryonum.captusbprint.engine.UsbLinkSelector
import java.util.Locale

data class CaptStatusProbeResult(
    val summary: String,
    val reportLine: String
)

class CaptStatusProbeException(message: String) : Exception(message)

/**
 * One user-triggered, read-only CAPT GetBasicStatus query.
 * It is deliberately not called by scanning and never sends page/print data.
 */
object CaptStatusProbe {
    private const val GET_BASIC_STATUS = 0xE0A0
    private const val MAX_STATUS_FRAME_BYTES = 1024
    private const val CAPT_HEADER_BYTES = 6
    private const val USB_TIMEOUT_MS = 3000

    fun query(context: Context, deviceKey: String): CaptStatusProbeResult {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw CaptStatusProbeException("USB Manager недоступен.")
        val device = manager.deviceList.values.singleOrNull { it.deviceName == deviceKey }
            ?: throw CaptStatusProbeException("Устройство отключено. Обнови список USB.")
        val profile = UsbDeviceProfiles.identify(device.vendorId, device.productId)
            ?: throw CaptStatusProbeException("Для этого USB ID профиль CAPT не подтверждён.")
        if (profile.protocolFamily != "CAPT") {
            throw CaptStatusProbeException("Для устройства не настроен профиль CAPT.")
        }
        if (!manager.hasPermission(device)) {
            throw CaptStatusProbeException("Нет разрешения Android на доступ к USB.")
        }
        if (device.interfaceCount !in 1..16) {
            throw CaptStatusProbeException("Число USB-интерфейсов вне поддерживаемых границ.")
        }

        val interfaces = mutableListOf<UsbInterface>()
        val facts = mutableListOf<InterfaceFact>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.endpointCount > 32) {
                throw CaptStatusProbeException("Слишком много endpoints; безопасный выбор невозможен.")
            }
            interfaces += iface
            facts += InterfaceFact(
                iface.id,
                iface.alternateSetting,
                (0 until iface.endpointCount).map { j ->
                    val endpoint = iface.getEndpoint(j)
                    EndpointFact(endpoint.address, endpoint.type)
                }
            )
        }

        val selection = UsbLinkSelector.select(facts)
        val unique = selection as? LinkSelection.Unique
            ?: throw CaptStatusProbeException(
                when (selection) {
                    LinkSelection.None -> "Не найдена пара BULK IN/OUT."
                    LinkSelection.Ambiguous -> "USB-интерфейс неоднозначен; запрос не отправлен."
                    is LinkSelection.Unique -> "Не удалось выбрать USB-интерфейс."
                }
            )
        if (unique.alternateSetting != 0) {
            throw CaptStatusProbeException("Поддерживается только alternate setting 0; запрос не отправлен.")
        }
        val iface = interfaces.singleOrNull {
            it.id == unique.interfaceId && it.alternateSetting == unique.alternateSetting
        } ?: throw CaptStatusProbeException("Интерфейс не удалось однозначно сопоставить.")
        val inEndpoint = findEndpoint(iface, unique.inAddress, UsbConstants.USB_DIR_IN)
        val outEndpoint = findEndpoint(iface, unique.outAddress, UsbConstants.USB_DIR_OUT)

        val connection = manager.openDevice(device)
            ?: throw CaptStatusProbeException("Не удалось открыть USB-устройство; оно может быть занято.")
        var claimed = false
        try {
            if (!connection.claimInterface(iface, false)) {
                throw CaptStatusProbeException("USB-интерфейс занят или не удалось его захватить.")
            }
            claimed = true

            val request = CaptFrame.encode(GET_BASIC_STATUS)
            val written = try {
                connection.bulkTransfer(outEndpoint, request, request.size, USB_TIMEOUT_MS)
            } catch (_: Exception) {
                throw CaptStatusProbeException("Ошибка отправки запроса CAPT; повтор не выполнялся.")
            }
            if (written != request.size) {
                throw CaptStatusProbeException(
                    "Запрос CAPT передан не полностью; автоматический повтор отключён."
                )
            }

            val response = readResponseFrame(connection, inEndpoint)
            val status = try {
                CaptBasicStatusParser.parse(response)
            } catch (_: IllegalArgumentException) {
                throw CaptStatusProbeException("Ответ CAPT имеет неподдерживаемый формат.")
            }
            val statusHex = hex(status.statusByte)
            val secondaryHex = hex(status.secondaryByte)
            val summary = "Ответ CAPT получен. Флаги: 0x$statusHex / 0x$secondaryHex. Печать не выполнялась."
            val reportLine = "CAPT GetBasicStatus: valid reply; totalBytes=${response.size}; " +
                "statusFlags=0x$statusHex; secondaryFlags=0x$secondaryHex; " +
                "no print/page data sent."
            return CaptStatusProbeResult(summary, reportLine)
        } finally {
            if (claimed) runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
        }
    }

    private fun findEndpoint(iface: UsbInterface, address: Int, direction: Int): UsbEndpoint {
        val matches = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.filter {
            it.address == address &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                it.direction == direction
        }
        return matches.singleOrNull()
            ?: throw CaptStatusProbeException("BULK endpoint не удалось однозначно сопоставить.")
    }

    private fun readResponseFrame(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint
    ): ByteArray {
        // CAPT's documented exchange reads the 6-byte command/length header first,
        // then reads only the remaining bytes declared by that header.
        val frame = ByteArray(MAX_STATUS_FRAME_BYTES)
        val firstCount = try {
            connection.bulkTransfer(endpoint, frame, 0, CAPT_HEADER_BYTES, USB_TIMEOUT_MS)
        } catch (_: Exception) {
            throw CaptStatusProbeException("Ошибка чтения заголовка ответа CAPT.")
        }
        if (firstCount != CAPT_HEADER_BYTES) {
            throw CaptStatusProbeException(
                "Заголовок CAPT получен не полностью; receivedBytes=$firstCount; " +
                    headerDetails(frame, firstCount)
            )
        }
        val command = (frame[0].toInt() and 0xff) or
            ((frame[1].toInt() and 0xff) shl 8)
        if (command != GET_BASIC_STATUS) {
            throw CaptStatusProbeException(
                "Неожиданный CAPT command ID; expected=E0A0; " +
                    "receivedCommand=${hex(frame[1].toInt() and 0xff)}${hex(frame[0].toInt() and 0xff)}; " +
                    headerDetails(frame, firstCount)
            )
        }

        // GetBasicStatus replies can be as short as 6 total bytes (4-byte header
        // plus two status bytes). Do not discard this valid protocol shape.
        val candidates = CaptFrame.lengthCandidates(frame, MAX_STATUS_FRAME_BYTES)
        val maximum = candidates.maxOrNull()
            ?: throw CaptStatusProbeException(
                "Длина ответа CAPT не распознана или превышает лимит; " +
                    headerDetails(frame, firstCount)
            )
        var received = firstCount
        val deadline = SystemClock.elapsedRealtime() + USB_TIMEOUT_MS

        while (true) {
            if (CaptFrame.headerLengthMatches(frame, received)) {
                return frame.copyOf(received)
            }
            if (received >= maximum) {
                throw CaptStatusProbeException(
                    "Фактическая длина ответа CAPT не совпала с заголовком; " +
                        "receivedBytes=$received; " + headerDetails(frame, received)
                )
            }
            val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
            if (remaining <= 0) {
                throw CaptStatusProbeException(
                    "Истёк тайм-аут чтения ответа CAPT; receivedBytes=$received; " +
                        headerDetails(frame, received)
                )
            }
            val requested = minOf(maximum - received, frame.size - received)
            val count = try {
                connection.bulkTransfer(
                    endpoint, frame, received, requested, minOf(1000, remaining)
                )
            } catch (_: Exception) {
                throw CaptStatusProbeException(
                    "Ошибка чтения продолжения ответа CAPT; receivedBytes=$received; " +
                        headerDetails(frame, received)
                )
            }
            if (count <= 0 || count > requested) {
                throw CaptStatusProbeException(
                    "Ответ CAPT получен не полностью; chunkBytes=$count; " +
                        "receivedBytes=$received; " + headerDetails(frame, received)
                )
            }
            received += count
        }
    }

    /**
     * Logs only the four framing bytes and derived lengths, never the status
     * payload, printer identifiers, document data, or USB device path.
     */
    private fun headerDetails(frame: ByteArray, received: Int): String {
        if (received < 4) return "headerHex=unavailable"
        val headerHex = frame.take(4).joinToString(" ") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
        }
        val lo = frame[2].toInt() and 0xff
        val hi = frame[3].toInt() and 0xff
        val littleEndian = lo or (hi shl 8)
        val nibbles = intArrayOf((hi ushr 4) and 0x0f, hi and 0x0f, (lo ushr 4) and 0x0f, lo and 0x0f)
        val bcd = if (nibbles.all { it <= 9 }) {
            nibbles[0] * 1000 + nibbles[1] * 100 + nibbles[2] * 10 + nibbles[3]
        } else {
            null
        }
        val candidates = CaptFrame.lengthCandidates(frame, MAX_STATUS_FRAME_BYTES)
        return "headerHex=$headerHex; declaredLengthLE=$littleEndian; " +
            "declaredLengthBCD=${bcd ?: "invalid"}; " +
            "boundedLengthCandidates=${candidates.joinToString(prefix = "[", postfix = "]")}"
    }

    private fun hex(value: Int): String =
        String.format(Locale.ROOT, "%02X", value and 0xff)
}
