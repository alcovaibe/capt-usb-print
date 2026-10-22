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
        // Read at least one full USB packet so a 10-byte CAPT reply is not
        // truncated by asking the host controller for only the 6-byte prefix.
        val frame = ByteArray(MAX_STATUS_FRAME_BYTES)
        val initialRequest = maxOf(64, endpoint.maxPacketSize).coerceAtMost(MAX_STATUS_FRAME_BYTES)
        val firstCount = try {
            connection.bulkTransfer(endpoint, frame, 0, initialRequest, USB_TIMEOUT_MS)
        } catch (_: Exception) {
            throw CaptStatusProbeException("Ошибка чтения ответа CAPT.")
        }
        if (firstCount !in 6..initialRequest) {
            throw CaptStatusProbeException("Ответ CAPT не получен полностью.")
        }
        val command = (frame[0].toInt() and 0xff) or
            ((frame[1].toInt() and 0xff) shl 8)
        if (command != GET_BASIC_STATUS) {
            throw CaptStatusProbeException("Принтер вернул неожиданный CAPT command ID.")
        }

        val candidates = CaptFrame.lengthCandidates(frame, MAX_STATUS_FRAME_BYTES)
            .filter { it >= 10 }
        val maximum = candidates.maxOrNull()
            ?: throw CaptStatusProbeException("Длина ответа CAPT некорректна или превышает лимит.")
        if (firstCount > maximum) {
            throw CaptStatusProbeException("USB-пакет длиннее объявленного ответа CAPT.")
        }
        var received = firstCount
        val deadline = SystemClock.elapsedRealtime() + USB_TIMEOUT_MS

        while (true) {
            if (CaptFrame.headerLengthMatches(frame, received)) {
                if (received < 10) {
                    throw CaptStatusProbeException("Ответ CAPT короче ожидаемого status-пакета.")
                }
                return frame.copyOf(received)
            }
            if (received >= maximum) {
                throw CaptStatusProbeException("Длина ответа CAPT не совпала с заголовком.")
            }
            val remaining = (deadline - SystemClock.elapsedRealtime()).toInt()
            if (remaining <= 0) {
                throw CaptStatusProbeException("Истёк тайм-аут чтения ответа CAPT.")
            }
            val requested = minOf(maximum - received, frame.size - received)
            val count = try {
                connection.bulkTransfer(
                    endpoint, frame, received, requested, minOf(1000, remaining)
                )
            } catch (_: Exception) {
                throw CaptStatusProbeException("Ошибка чтения ответа CAPT.")
            }
            if (count <= 0 || count > requested) {
                throw CaptStatusProbeException("Ответ CAPT получен не полностью.")
            }
            received += count
        }
    }

    private fun hex(value: Int): String =
        String.format(Locale.ROOT, "%02X", value and 0xff)
}
