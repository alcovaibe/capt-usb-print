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
import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptExtendedStatusParser
import com.cryonum.captusbprint.core.CaptInputStatusParser
import com.cryonum.captusbprint.core.CaptPrinterInfoParser
import com.cryonum.captusbprint.core.CaptStatusFlagDecoder
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
    private const val GET_BASIC_STATUS = CaptBasicStatusParser.GET_BASIC_STATUS
    private const val GET_EXTENDED_STATUS = CaptExtendedStatusParser.GET_EXTENDED_STATUS
    private const val GET_INPUT_STATUS = CaptInputStatusParser.GET_INPUT_STATUS
    private const val GET_PRINTER_INFO = CaptPrinterInfoParser.GET_PRINTER_INFO
    private const val MAX_STATUS_FRAME_BYTES = 1024
    private const val CAPT_HEADER_BYTES = 6
    private const val USB_TIMEOUT_MS = 3000

    fun query(context: Context, deviceKey: String): CaptStatusProbeResult =
        queryCommand(context, deviceKey, GET_BASIC_STATUS)

    fun queryExtended(context: Context, deviceKey: String): CaptStatusProbeResult =
        queryCommand(context, deviceKey, GET_EXTENDED_STATUS)

    fun queryInputStatus(context: Context, deviceKey: String): CaptStatusProbeResult =
        queryCommand(context, deviceKey, GET_INPUT_STATUS)

    fun queryPrinterInfo(context: Context, deviceKey: String): CaptStatusProbeResult =
        queryCommand(context, deviceKey, GET_PRINTER_INFO)

    private fun queryCommand(
        context: Context,
        deviceKey: String,
        commandToSend: Int
    ): CaptStatusProbeResult {
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

            val request = CaptFrame.encode(commandToSend)
            val written = try {
                connection.bulkTransfer(outEndpoint, request, request.size, USB_TIMEOUT_MS)
            } catch (_: Exception) {
                throw CaptStatusProbeException(
                    "Ошибка отправки read-only CAPT запроса 0x${hexCommand(commandToSend)}; повтор не выполнялся."
                )
            }
            if (written != request.size) {
                throw CaptStatusProbeException(
                    "CAPT запрос 0x${hexCommand(commandToSend)} передан не полностью; автоматический повтор отключён."
                )
            }

            val response = readResponseFrame(connection, inEndpoint, commandToSend)
            return when (commandToSend) {
                GET_BASIC_STATUS -> basicResult(response)
                GET_EXTENDED_STATUS -> extendedResult(response)
                GET_INPUT_STATUS -> inputStatusResult(response)
                GET_PRINTER_INFO -> printerInfoResult(response)
                else -> throw CaptStatusProbeException("Неизвестная диагностическая команда CAPT.")
            }
        } finally {
            if (claimed) runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
        }
    }

    private fun printerInfoResult(frame: ByteArray): CaptStatusProbeResult {
        val info = try {
            CaptPrinterInfoParser.parse(frame)
        } catch (_: IllegalArgumentException) {
            throw CaptStatusProbeException("Ответ GetPrinterInfo слишком короткий или имеет неверный формат.")
        }
        val block = hex16(info.blockSize)
        val buffer = hex16(info.bufferSize)
        val safeChunk = info.safeChunkBytes
        val summary = if (info.transferParametersUsable) {
            "GetPrinterInfo: Blk=0x$block (${info.blockSize}), Buf=0x$buffer (${info.bufferSize}); " +
                "ограниченный размер блока для будущей передачи=${safeChunk} байт. Печать не выполнялась."
        } else {
            "GetPrinterInfo: ответ получен, но Blk/Buf не позволяют выбрать безопасные параметры передачи. " +
                "Печать не выполнялась."
        }
        val report = "CAPT GetPrinterInfo: valid reply; totalBytes=${frame.size}; " +
            "payloadBytes=${info.payloadBytes}; Blk=0x$block (${info.blockSize}); " +
            "Buf=0x$buffer (${info.bufferSize}); safeChunkBytes=${safeChunk ?: "unavailable"}; " +
            "transferParametersUsable=${info.transferParametersUsable}; no print/page data sent."
        return CaptStatusProbeResult(summary, report)
    }

    private fun inputStatusResult(frame: ByteArray): CaptStatusProbeResult {
        val payload = try {
            CaptInputStatusParser.parse(frame)
        } catch (_: IllegalArgumentException) {
            throw CaptStatusProbeException("Ответ GetInputStatus имеет неподдерживаемый формат.")
        }
        // The public CAPT driver requests this status when NEED_INPUT_STATUS is set,
        // but does not decode its payload. Preserve the bytes without guessing fields.
        val maxLoggedBytes = 64
        val payloadHex = payload.take(maxLoggedBytes)
            .joinToString(" ") { hex(it.toInt() and 0xff) }
        val truncated = payload.size > maxLoggedBytes
        val summary = "GetInputStatus: корректный ответ; байт данных=${payload.size}. " +
            "Поля пока не расшифрованы; печать не выполнялась."
        val report = "CAPT GetInputStatus: valid reply; totalBytes=${frame.size}; " +
            "rawResponseDataBytes=${payload.size}; rawResponseDataHex=$payloadHex; " +
            "rawResponseDataHexTruncated=$truncated; no document/page data sent."
        return CaptStatusProbeResult(summary, report)
    }

    private fun basicResult(frame: ByteArray): CaptStatusProbeResult {
        val status = try {
            CaptBasicStatusParser.parse(frame)
        } catch (_: IllegalArgumentException) {
            throw CaptStatusProbeException("Ответ GetBasicStatus имеет неподдерживаемый формат.")
        }
        val basic = hex(status.statusByte)
        val secondary = hex(status.secondaryByte)
        val basicNames = CaptStatusFlagDecoder.basic(status.statusByte).joinToString("|")
        val secondaryNames = CaptStatusFlagDecoder.secondary(status.secondaryByte).joinToString("|")
        val summary = "GetBasicStatus: 0x$basic [$basicNames]; secondary 0x$secondary [$secondaryNames]. Печать не выполнялась."
        val report = "CAPT GetBasicStatus: valid reply; totalBytes=${frame.size}; " +
            "statusFlags=0x$basic[$basicNames]; secondaryFlags=0x$secondary[$secondaryNames]; " +
            "bufferLevel=${status.bufferLowByte?.let { hex(it) } ?: "unavailable"}:" +
            "${status.bufferHighByte?.let { hex(it) } ?: "unavailable"}; no print/page data sent."
        return CaptStatusProbeResult(summary, report)
    }

    private fun extendedResult(frame: ByteArray): CaptStatusProbeResult {
        val status = try {
            CaptExtendedStatusParser.parse(frame)
        } catch (_: IllegalArgumentException) {
            throw CaptStatusProbeException(
                "Ответ GetExtendedStatus имеет неподдерживаемый формат или не содержит полей состояния механизма."
            )
        }
        val basic = hex(status.basicStatusByte)
        val secondary = hex(status.secondaryByte)
        val bufferLow = hex(status.bufferLowByte)
        val bufferHigh = hex(status.bufferHighByte)
        val auxiliary = hex(status.auxiliaryByte)
        val cnt = hex(status.engineCommunicationByte)
        val paper = hex(status.paperAvailabilityByte)
        val engine = String.format(Locale.ROOT, "%04X", status.engineFlags and 0xffff)
        val basicNames = CaptStatusFlagDecoder.basic(status.basicStatusByte).joinToString("|")
        val secondaryNames = CaptStatusFlagDecoder.secondary(status.secondaryByte).joinToString("|")
        val auxiliaryNames = CaptStatusFlagDecoder.auxiliary(status.auxiliaryByte).joinToString("|")
        val cntNames = CaptStatusFlagDecoder.engineCommunication(status.engineCommunicationByte).joinToString("|")
        val engineNames = CaptStatusFlagDecoder.engine(status.engineFlags).joinToString("|")
        val paperName = CaptStatusFlagDecoder.paper(status.paperAvailabilityByte)
        val countersAvailable = status.pageDecoding != null &&
            status.pagePrinting != null && status.pageOut != null && status.pageCompleted != null
        val pageCounterReport = if (countersAvailable) {
            "pageCounters=Start:${status.pageDecoding},Printing:${status.pagePrinting}," +
                "Shipped:${status.pageOut},Printed:${status.pageCompleted}; "
        } else {
            "pageCounters=unavailable; "
        }
        val report = "CAPT GetExtendedStatus: valid reply; totalBytes=${frame.size}; " +
            "statusFlags=0x$basic[$basicNames]; secondaryFlags=0x$secondary[$secondaryNames]; " +
            "bufferLevel=0x$bufferLow:0x$bufferHigh; bufferSlots=${status.bufferSlots}; " +
            "auxiliaryFlags=0x$auxiliary[$auxiliaryNames]; " +
            "engineCommunicationFlags=0x$cnt[$cntNames]; " +
            "paperAvailability=0x$paper[$paperName]; engineFlags=0x$engine[$engineNames]; " +
            pageCounterReport + "pageReceived=${status.pageReceived ?: "unavailable"}; " +
            "no print/page data sent."
        val findings = mutableListOf<String>()
        if ((status.basicStatusByte and 0x10) != 0) findings += "установлен флаг OFFLINE"
        if ((status.basicStatusByte and 0x02) != 0) findings += "установлен флаг NOT_READY"
        if ((status.basicStatusByte and 0x04) != 0) findings += "установлен флаг CMD_BUSY"
        if ((status.basicStatusByte and 0x08) != 0) findings += "установлен флаг IM_DATA_BUSY"
        if ((status.basicStatusByte and 0x80) != 0) {
            findings += "установлен ERROR_BIT; причина по известным полям не определена"
        }
        if ((status.basicStatusByte and 0x20) != 0) {
            findings += "установлен неизвестный бит основного статуса 0x20; его значение не интерпретируется"
        }
        if ((status.secondaryByte and 0x02) != 0) {
            findings += "установлен NEED_INPUT_STATUS — следует запросить GetInputStatus"
        }
        if ((status.secondaryByte and 0x01) != 0) {
            findings += "установлен EXTENDED_STATUS_CHANGED"
        }
        if ((status.engineFlags and 0x0100) != 0) findings += "флаг замятия бумаги"
        if ((status.engineFlags and 0x4000) != 0) findings += "флаг открытой дверцы"
        if ((status.engineFlags and 0x2000) != 0) findings += "флаг отсутствующего картриджа"
        if ((status.engineFlags and 0x0002) != 0) findings += "флаг необходимости обслуживания"
        if ((status.engineFlags and 0x0004) != 0) findings += "флаг очистки механизма"
        if ((status.engineFlags and 0x0008) != 0) findings += "флаг недопустимого соединения"
        if ((status.engineCommunicationByte and 0x10) != 0) findings += "ошибка связи с печатным механизмом"
        if ((status.engineCommunicationByte and 0x40) != 0) findings += "механизм отклонил печать"
        if ((status.engineCommunicationByte and 0x08) != 0) findings += "флаг некорректных данных"
        if ((status.engineCommunicationByte and 0x01) != 0) findings += "флаг переполнения обмена"
        if ((status.engineCommunicationByte and 0x02) != 0) findings += "флаг недостатка данных"
        if (status.paperAvailabilityByte == 0) findings += "бумага не отмечена как доступная"
        if ((status.auxiliaryByte and 0x02) != 0) findings += "принтер сообщает о занятости"
        if ((status.auxiliaryByte and 0x04) != 0) findings += "идёт подача бумаги"
        val summary = if (findings.isNotEmpty()) {
            "Расширенное состояние CAPT: " + findings.joinToString("; ") +
                ". Это декодированные флаги устройства; печать не выполнялась."
        } else {
            "Расширенный ответ CAPT получен; известные флаги аппаратной ошибки не установлены. " +
                "Печать не выполнялась."
        }
        return CaptStatusProbeResult(summary, report)
    }

    private fun hexCommand(value: Int): String =
        String.format(Locale.ROOT, "%04X", value and 0xffff)

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
        endpoint: UsbEndpoint,
        expectedCommand: Int
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
        if (command != expectedCommand) {
            throw CaptStatusProbeException(
                "Неожиданный CAPT command ID; expected=${hexCommand(expectedCommand)}; " +
                    "receivedCommand=${hexCommand(command)}; " +
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

    private fun hex16(value: Int): String =
        String.format(Locale.ROOT, "%04X", value and 0xffff)

    private fun hex(value: Int): String =
        String.format(Locale.ROOT, "%02X", value and 0xff)
}
