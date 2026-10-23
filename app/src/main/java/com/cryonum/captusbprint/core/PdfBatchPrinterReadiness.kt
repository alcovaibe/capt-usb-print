package com.cryonum.captusbprint.core

import java.util.Locale

/** Candidate flag interpretation used only by read-only diagnostic assessment.
 * This byte mask cannot establish the semantics of a SHORT_UNVERIFIED reply.
 * LBP2900 job admission uses CaptDocumentPreflight's Extended/Input policy.
 * Historical ReserveUnit error bytes have a separate command contract.
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
        return "GetBasicStatus=0x$hex: возможные блокирующие флаги; " +
            "для короткого ответа их смысл не подтверждён."
    }
}
