package com.cryonum.captusbprint.usb

import android.content.Context
import com.cryonum.captusbprint.document.PreparedPdfCaptPage

/** The PDF pilot shares the document engine's owned-job cleanup. */
internal object CaptPdfPrintEngine {
    fun run(context: Context, deviceKey: String, prepared: PreparedPdfCaptPage,
            cancelled: () -> Boolean, progress: (String) -> Unit = {}): CaptTestPrintResult =
        CaptDocumentPrintEngine.run(context, deviceKey, listOf(prepared), cancelled, progress,
            owner = "PDF_ONE_PAGE")
}
