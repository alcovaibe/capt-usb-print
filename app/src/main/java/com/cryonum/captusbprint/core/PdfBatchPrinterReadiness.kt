package com.cryonum.captusbprint.core

import java.util.Locale

/**
 * Read-only pre-reservation gate specific to the new PDF batch pilot.
 * Previous owner diagnostic captured BasicStatus 0x88 (IM_DATA_BUSY and
 * ERROR_BIT) while ReserveUnit returned 0x8C on every subsequent attempt.
 * We do not claim to repair or fully decode that engine state.
 */
object PdfBatchPrinterReadiness {
    private const val NOT_READY = 0x02
    private const val CMD_BUSY = 0x04
    private const val IMAGE_DATA_BUSY = 0x08
    private const val ERROR_BIT = 0x80
    private val blockingBits = NOT_READY or CMD_BUSY or IMAGE_DATA_BUSY or ERROR_BIT

    fun rejection(statusByte: Int): String? {
        require(statusByte in 0..255)
        if ((statusByte and blockingBits) == 0) return null
        val hex = String.format(Locale.ROOT, "%02X", statusByte)
        return "GetBasicStatus=0x$hex: принтер сообщает занятость или ошибку. " +
            "ReserveUnit не отправлялся. Проверь состояние принтера; автоматического сброса нет."
    }
}
