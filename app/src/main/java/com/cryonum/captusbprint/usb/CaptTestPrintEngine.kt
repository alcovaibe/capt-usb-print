package com.cryonum.captusbprint.usb

import android.content.Context
import android.os.SystemClock
import com.cryonum.captusbprint.core.CaptBasicStatusParser
import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptExtendedStatusParser
import com.cryonum.captusbprint.core.CaptJobProtocol
import com.cryonum.captusbprint.core.CaptPageCounterProgress
import com.cryonum.captusbprint.core.CaptPageProtocol
import com.cryonum.captusbprint.core.CaptPrinterInfoParser
import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.core.HiscoaNative
import java.security.MessageDigest
import java.util.Locale

data class CaptTestPrintResult(
    val outcome: String,
    val summary: String,
    val stage: String,
    val sentPrintDataBytes: Long,
    val compressedSha256: String?,
    val printerConfirmedPages: Int?,
    val lastStatusFrameHex: String?,
    val trace: List<String>
)

/**
 * Experimental, explicit one-page test ONLY for observed Canon LBP2900.
 * References ValdikSS/captdriver commit 892f320c019974a5b737e000fcc2c8582264a131
 * lbp3000_job_prologue / lbp2900_page_prologue / page_epilogue.
 *
 * Fail closed on timeouts and ambiguous replies; NEVER automatically replay a job
 * or run error-recovery commands after an uncertain transfer.
 */
object CaptTestPrintEngine {
    private const val MAX_TOTAL_COMPRESSED = 4 * 1024 * 1024
    private const val WAIT_READY_MS = 15_000L
    private const val WAIT_PAGE_MS = 90_000L

    fun run(
        context: Context,
        deviceKey: String,
        cancelled: () -> Boolean,
        progress: (String) -> Unit = {}
    ): CaptTestPrintResult {
        var stage = "PREPARING"
        var bytesSent = 0L
        var shaHex: String? = null
        var lastFrame: String? = null
        var pages: Int? = null
        var sideEffectsStarted = false
        val trace = ArrayList<String>()

        fun note(event: String) {
            if (trace.size < 100) trace.add(event)
            progress(event)
        }
        fun guard() { check(!cancelled()) { "Cancelled; command sequence not replayed" } }
        fun hex(bytes: ByteArray): String = bytes.joinToString(" ") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
        }

        try {
            // Prepare the entire bounded test pattern BEFORE claiming or changing
            // printer state. Encoder failures do not leave a reserved print job.
            val bands = ArrayList<ByteArray>()
            val digest = MessageDigest.getInstance("SHA-256")
            var y = 0
            var total = 0
            while (y < CaptTestPage.HEIGHT) {
                guard()
                val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - y)
                val compressed = HiscoaNative.encodeBand(
                    CaptTestPage.band(y, lines), CaptTestPage.LINE_BYTES,
                    lines, y + lines == CaptTestPage.HEIGHT)
                require(compressed.isNotEmpty())
                require(compressed.size <= CaptTestPage.LINE_BYTES * lines * 2 + 64)
                total += compressed.size
                require(total <= MAX_TOTAL_COMPRESSED) { "Test page exceeds memory budget" }
                bands.add(compressed)
                digest.update(compressed)
                y += lines
            }
            shaHex = digest.digest().joinToString("") {
                String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
            }
            note("HISCOA ready; bands=${bands.size}; totalCompressedBytes=$total")
            stage = "PREFLIGHT"

            CaptUsbSession.open(context, deviceKey, cancelled).use { session ->
                fun query(command: Int, data: ByteArray = byteArrayOf()): ByteArray {
                    guard()
                    val reply = session.query(command, data)
                    lastFrame = hex(reply)
                    return reply
                }
                fun state(): CaptExtendedStatus =
                    CaptExtendedStatusParser.parse(query(CaptJobProtocol.GET_EXTENDED_STATUS))
                fun safeState(): CaptExtendedStatus {
                    val status = state()
                    if (status.engineFlags != 0 || status.engineCommunicationByte != 0 ||
                        status.paperAvailabilityByte != 0x80 ||
                        (status.basicStatusByte and 0x82) != 0
                    ) {
                        throw IllegalStateException(
                            "Printer reported an error/not-ready or paper is unavailable; no retry")
                    }
                    return status
                }
                fun waitState(timeoutMs: Long, predicate: (CaptExtendedStatus) -> Boolean): CaptExtendedStatus {
                    val deadline = SystemClock.elapsedRealtime() + timeoutMs
                    do {
                        guard()
                        val s = state()
                        if (predicate(s)) return s
                        if (s.engineFlags != 0 || s.engineCommunicationByte != 0 ||
                            s.paperAvailabilityByte != 0x80) {
                            throw IllegalStateException("Printer status/error changed while waiting")
                        }
                        Thread.sleep(120)
                    } while (SystemClock.elapsedRealtime() < deadline)
                    throw IllegalStateException("Timed out waiting for required CAPT counter/flag")
                }

                val info = CaptPrinterInfoParser.parse(query(CaptJobProtocol.GET_PRINTER_INFO))
                check(info.transferParametersUsable) { "GetPrinterInfo supplied no valid transfer limits" }
                val chunkSize = info.safeChunkBytes ?: error("No safe chunk limit")
                check(chunkSize in 64..0xFF00) { "Unusable CAPT chunk size" }
                note("GetPrinterInfo: Blk=${info.blockSize}; Buf=${info.bufferSize}; chunk=$chunkSize")
                var status = safeState()
                check(CaptPageCounterProgress.hasNoOutstandingPage(status)) {
                    "Printer's page stage counters disagree; refusing an outstanding page/job"
                }
                if ((status.secondaryByte and 0x02) != 0) {
                    query(CaptJobProtocol.GET_INPUT_STATUS)
                    status = safeState()
                    check((status.secondaryByte and 0x02) == 0) {
                        "Input status change has not cleared"
                    }
                }
                stage = "RESERVING"
                note("ReserveUnit")
                sideEffectsStarted = true
                val jobId = CaptJobProtocol.parseReservedJob(
                    query(CaptJobProtocol.RESERVE_UNIT, CaptJobProtocol.reserveRequest()))
                note("CAPT unit reserved; JobID present")
                query(CaptJobProtocol.SET_POWER_DOWN, CaptJobProtocol.powerDownSettings())
                query(CaptJobProtocol.SET_JOB_INFO2,
                    CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.START_FLAG))
                // LBP2900 uses the LBP3000 job prologue. OFFLINE is expected in
                // the physical 0x31 response and is not a USB disconnection.
                status = safeState()
                if ((status.basicStatusByte and 0x10) == 0) {
                    query(CaptJobProtocol.GO_OFFLINE, byteArrayOf(0, 0))
                    waitState(WAIT_READY_MS) { (it.basicStatusByte and 0x10) != 0 }
                }
                // Exactly the upstream initialization order; no recovery loops.
                query(CaptJobProtocol.DISCARD_DATA)
                query(CaptJobProtocol.CLEAR_MIS_PRINT)
                query(CaptJobProtocol.CLEAR_ERROR)
                query(CaptJobProtocol.GO_ONLINE, CaptJobProtocol.goOnlineSettings())
                val ready = waitState(WAIT_READY_MS) {
                    (it.basicStatusByte and 0x06) == 0 &&
                        (it.basicStatusByte and 0x10) == 0
                }
                // The first observed successful job left every stage counter at 1.
                // Capture the baseline AFTER the prologue in case a new job resets it.
                check(CaptPageCounterProgress.hasNoOutstandingPage(ready)) {
                    "Printer has a pending page after job initialization; no page data sent"
                }
                val initialCounters = CaptPageCounterProgress.snapshot(ready)
                check(initialCounters.completed < 0xFFFF) {
                    "CAPT page counters would wrap; behavior not yet verified"
                }
                note("CAPT initial counters: decoding=${initialCounters.decoding}; " +
                    "out=${initialCounters.out}; completed=${initialCounters.completed}")
                stage = "PAGE_DESCRIPTOR"
                note("Sending A4/600dpi page descriptor")
                session.sendFrame(CaptPageProtocol.beginA4TestPage())

                stage = "TRANSMITTING"
                var started = false
                // Never assume buffer space remains free for three bands.
                // Read the printer's live BufLevel before EVERY IC_VIDEO_DATA frame.
                // This deliberately favors safe first-page discovery over throughput.
                // No data frame is sent while the device reports zero free slots.
                for ((index, band) in bands.withIndex()) {
                    guard()
                    for (frame in CaptPageProtocol.videoData(band, chunkSize)) {
                        guard()
                        status = safeState()
                        if (status.bufferSlots == 0) {
                            if (!started) {
                                waitState(WAIT_READY_MS) { CaptPageCounterProgress.nextPageDecoded(initialCounters, it) }
                                query(CaptJobProtocol.START_PRINT, CaptJobProtocol.pageNumber(1))
                                started = true
                                note("StartPrint(1) to drain full CAPT buffer")
                            }
                            val available = waitState(WAIT_READY_MS) { it.bufferSlots >= 1 }
                            // This state is checked immediately before transfer.
                            check(available.bufferSlots >= 1)
                        }
                        guard()
                        session.sendFrame(frame)
                        bytesSent += frame.size
                    }
                    if (index % 15 == 0) note("Transmitted ${index + 1}/${bands.size} bands")
                }

                stage = "STARTING_PRINT"
                session.sendOnly(CaptJobProtocol.BLACK_END)
                if (!started) {
                    waitState(WAIT_READY_MS) { CaptPageCounterProgress.nextPageDecoded(initialCounters, it) }
                    query(CaptJobProtocol.START_PRINT, CaptJobProtocol.pageNumber(1))
                    note("StartPrint(1) after IC_BLACK_END")
                }

                stage = "WAITING_CONFIRMATION"
                note("Waiting for printer's Shipped/Printed counters")
                val confirmed = waitState(WAIT_PAGE_MS) {
                    CaptPageCounterProgress.nextPageCompleted(initialCounters, it)
                }
                pages = confirmed.pageCompleted
                stage = "FINALIZING"
                query(CaptJobProtocol.SET_JOB_INFO2,
                    CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.END_FLAG))
                query(CaptJobProtocol.RELEASE_UNIT, CaptJobProtocol.pageNumber(jobId))
                stage = "CONFIRMED_BY_PRINTER"
                note("Printer counters report at least one completed page; physical sheet not visually verified")
            }
            return CaptTestPrintResult("printer_counter_confirmed",
                "Принтер подтвердил завершение страницы счётчиками CAPT. Проверь реальный лист.",
                stage, bytesSent, shaHex, pages, lastFrame, trace)
        } catch (error: Throwable) {
            // JNI linkage/initialization failure must also become a JSON
            // diagnostic, not crash the Activity's serial USB worker.
            val uncertain = sideEffectsStarted || bytesSent > 0L
            val outcome = if (uncertain) "unknown_after_print_command" else "failed_before_print_commands"
            note("Stopped at $stage: ${error.javaClass.simpleName}: ${error.message ?: "no details"}; no automatic resend or recovery")
            return CaptTestPrintResult(outcome,
                if (uncertain) "Печать не подтверждена; исход неизвестен. Повтор автоматически запрещён."
                else "Задание не отправлено: ${error.message ?: error.javaClass.simpleName}",
                stage, bytesSent, shaHex, pages, lastFrame, trace)
        }
    }
}
