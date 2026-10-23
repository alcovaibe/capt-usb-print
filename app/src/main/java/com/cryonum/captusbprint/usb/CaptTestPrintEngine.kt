package com.cryonum.captusbprint.usb

import android.content.Context
import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.document.PreparedPdfCaptPageBuilder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

data class CaptTestPrintResult(
    val outcome: String,
    val summary: String,
    val stage: String,
    val sentPrintDataBytes: Long,
    val compressedSha256: String?,
    val printerConfirmedPages: Int?,
    val lastStatusFrameHex: String?,
    val trace: List<String>,
    val failureCode: String? = null,
    val protocolDiagnostics: Map<String, Any?> = emptyMap()
)

/** Test sheets use the document engine's single reservation and cancellation lifecycle. */
object CaptTestPrintEngine {
    fun run(context: Context, deviceKey: String, cancelled: () -> Boolean,
            progress: (String) -> Unit = {}): CaptTestPrintResult =
        runSheets(context, deviceKey, 1, cancelled, progress)

    internal fun runSheets(context: Context, deviceKey: String, count: Int,
                           cancelled: () -> Boolean, progress: (String) -> Unit): CaptTestPrintResult {
        val generation = CaptPrinterStateStore.generation()
        val stop = { cancelled() || CaptPrinterStateStore.generation() != generation }
        val preparation = try {
            require(count in 1..2)
            val digest = MessageDigest.getInstance("SHA-256")
            val prepared = (1..count).map { number ->
                PreparedPdfCaptPageBuilder.prepare(produceBands = { accept ->
                    var y = 0
                    while (y < CaptTestPage.HEIGHT) {
                        if (stop()) throw CancellationException()
                        val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - y)
                        accept(y, CaptTestPage.band(y, lines, number))
                        y += lines
                    }
                }, encode = { pixels, stride, lines, last ->
                    HiscoaNative.encodeBand(pixels, stride, lines, last).also { digest.update(it) }
                }, cancelled = stop)
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            prepared to sha
        } catch (error: Throwable) {
            return CaptTestPrintResult(if (stop()) "cancelled" else "failed_before_print_commands",
                if (stop()) "Тест отменён до отправки в принтер." else "Не удалось подготовить тестовую страницу.",
                "PREPARING", 0, null, null, null, listOf(error.javaClass.simpleName))
        }
        return CaptDocumentPrintEngine.run(context, deviceKey, preparation.first, stop, progress,
            owner = if (count == 1) "TEST_ONE_PAGE" else "TEST_TWO_PAGES").copy(compressedSha256 = preparation.second)
    }
}
