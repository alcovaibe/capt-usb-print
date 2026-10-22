package com.cryonum.captusbprint.usb

import android.content.Context
import com.cryonum.captusbprint.document.PreparedPdfCaptPage

internal interface PdfBatchSession : AutoCloseable {
    fun query(command: Int, payload: ByteArray): ByteArray
    fun sendFrame(frame: ByteArray)
    fun sendOnly(command: Int)
}

/** Retained pilot entry point; production document jobs use the single queue. */
internal object CaptPdfBatchPrintEngine {
    fun run(context: Context, deviceKey: String, prepared: List<PreparedPdfCaptPage>,
            cancelled: () -> Boolean, progress: (String) -> Unit = {},
            checkpoint: (CaptTestPrintResult) -> Unit = {}): CaptTestPrintResult {
        require(prepared.size in 2..3)
        return CaptDocumentPrintEngine.run(context, deviceKey, prepared, cancelled, progress, checkpoint)
    }

    internal fun runPrepared(prepared: List<PreparedPdfCaptPage>, cancelled: () -> Boolean,
        progress: (String) -> Unit = {}, checkpoint: (CaptTestPrintResult) -> Unit = {},
        openSession: () -> PdfBatchSession, now: () -> Long, pause: (Long) -> Unit): CaptTestPrintResult {
        require(prepared.size in 2..3)
        return CaptDocumentPrintEngine.runPrepared(prepared, cancelled, progress, checkpoint,
            openSession, now, pause)
    }
}
