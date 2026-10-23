package com.cryonum.captusbprint.usb

import android.content.Context

/** Two different test patterns, sharing document cancellation and release handling. */
object CaptTwoPagePrintEngine {
    fun run(context: Context, deviceKey: String, cancelled: () -> Boolean,
            progress: (String) -> Unit = {}): CaptTestPrintResult =
        CaptTestPrintEngine.runSheets(context, deviceKey, 2, cancelled, progress)
}
