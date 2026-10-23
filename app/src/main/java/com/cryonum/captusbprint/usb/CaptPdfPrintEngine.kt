package com.cryonum.captusbprint.usb

import android.content.Context
import com.cryonum.captusbprint.core.CaptProtocolTrace
import com.cryonum.captusbprint.core.CaptCommandAckFailure
import com.cryonum.captusbprint.core.CaptReplyReadFailure
import android.os.SystemClock
import com.cryonum.captusbprint.core.CaptDocumentPreflight
import com.cryonum.captusbprint.core.CaptPreflightFailure
import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptExtendedStatusParser
import com.cryonum.captusbprint.core.CaptJobProtocol
import com.cryonum.captusbprint.core.CaptPageCounterProgress
import com.cryonum.captusbprint.core.CaptPrinterInfoParser
import com.cryonum.captusbprint.document.PreparedPdfCaptPage
import java.util.Locale

/**
 * Guarded, one-page portrait PDF pilot for observed Canon LBP2900.
 * References ValdikSS/captdriver commit 892f320c019974a5b737e000fcc2c8582264a131
 * lbp3000_job_prologue / lbp2900_page_prologue / page_epilogue.
 *
 * Explicit preview-only experimental action; fail closed on ambiguous replies and NEVER replay a job
 * or run error-recovery commands after an uncertain transfer.
 */
internal object CaptPdfPrintEngine {
    private const val WAIT_READY_MS = 15_000L
    private const val WAIT_PAGE_MS = 90_000L

    fun run(
        context: Context,
        deviceKey: String,
        prepared: PreparedPdfCaptPage,
        cancelled: () -> Boolean,
        progress: (String) -> Unit = {}
    ): CaptTestPrintResult {
        var stage = "PREPARED_PDF_PAGE"
        val protocol = CaptProtocolTrace(phase = { stage }, now = { SystemClock.elapsedRealtime() })
        var bytesSent = 0L
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
            // Entire bounded PDF/HISCOA page prepared BEFORE entering USB.
            check(prepared.bandCount == 110 && prepared.compressedBytes in 1..(4 * 1024 * 1024)) {
                "Unverified or oversized prepared PDF page"
            }
            note("Prepared A4 portrait PDF: 110 strips; no document data or hash logged")
            stage = "PREFLIGHT"

            CaptUsbSession.open(context, deviceKey, cancelled, protocol, "PDF_ONE_PAGE").use { session ->
                fun query(command: Int, data: ByteArray = byteArrayOf()): ByteArray {
                    guard()
                    val reply = session.query(command, data)
                    lastFrame = hex(reply)
                    protocol.reply(command, reply, data)
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
                var status = CaptDocumentPreflight.awaitReady(
                    { query(it) }, ::guard, { SystemClock.elapsedRealtime() },
                    { Thread.sleep(it) }, ::note)
                stage = "RESERVING"
                note("ReserveUnit")
                sideEffectsStarted = true
                val jobId = CaptJobProtocol.parseReservedJob(
                    query(CaptJobProtocol.RESERVE_UNIT, CaptJobProtocol.reserveRequest()))
                protocol.reserved(jobId)
                note("CAPT unit reserved; JobID present")
                stage = "CONFIGURING_JOB"
                query(CaptJobProtocol.SET_POWER_DOWN, CaptJobProtocol.powerDownSettings())
                CaptDocumentPreflight.afterPowerDown(
                    { query(it) }, ::guard, { SystemClock.elapsedRealtime() }, { Thread.sleep(it) }, ::note)
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
                check(initialCounters.decoding == 0 && initialCounters.printing == 0 &&
                    initialCounters.out == 0 && initialCounters.completed == 0 &&
                    initialCounters.received == 0) {
                    "Unverified CAPT initial page counters for first document sheet"
                }
                note("CAPT initial counters: decoding=${initialCounters.decoding}; " +
                    "out=${initialCounters.out}; completed=${initialCounters.completed}")
                stage = "PAGE_DESCRIPTOR"
                note("Sending A4/600dpi page descriptor")
                session.sendFrame(prepared.descriptorFrame())

                stage = "TRANSMITTING"
                var started = false
                // Never assume buffer space remains free for three bands.
                // Read the printer's live BufLevel before EVERY IC_VIDEO_DATA frame.
                // This deliberately favors safe first-page discovery over throughput.
                // No data frame is sent while the device reports zero free slots.
                var observedBand = -1
                prepared.forEachVideoFrame(chunkSize, cancelled = cancelled) { index, frame ->
                    guard()
                    status = safeState()
                    if (status.bufferSlots == 0) {
                        if (!started) {
                            waitState(WAIT_READY_MS) {
                                CaptPageCounterProgress.nextPageDecoded(initialCounters, it)
                            }
                            query(CaptJobProtocol.START_PRINT, CaptJobProtocol.pageNumber(1))
                            started = true
                            note("StartPrint(1) during bounded buffer drain")
                        }
                        waitState(WAIT_READY_MS) { it.bufferSlots >= 1 }
                    }
                    guard()
                    session.sendFrame(frame)
                    bytesSent += frame.size
                    if (index != observedBand) {
                        observedBand = index
                        if (index % 15 == 0) note("Transmitted PDF strip ${index + 1}/110")
                    }
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
                    CaptPageCounterProgress.pagesCompletedSince(initialCounters, it, 1) &&
                        CaptPageCounterProgress.hasNoOutstandingPage(it) && it.pageCompleted == 1
                }
                pages = confirmed.pageCompleted
                protocol.pagesConfirmed(1)
                stage = "FINALIZING"
                query(CaptJobProtocol.SET_JOB_INFO2,
                    CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.END_FLAG))
                query(CaptJobProtocol.RELEASE_UNIT, CaptJobProtocol.pageNumber(jobId))
                stage = "PDF_ONE_PAGE_COUNTER_CONFIRMED"
                note("Printer counters report one completed PDF page; owner must inspect physical sheet")
            }
            return CaptTestPrintResult("printer_counter_confirmed",
                "CAPT подтвердил одну страницу PDF. Проверь физический лист и точность полей.",
                stage, bytesSent, null, pages, lastFrame, trace, protocolDiagnostics = protocol.snapshot())
        } catch (error: Throwable) {
            // JNI linkage/initialization failure must also become a JSON
            // diagnostic, not crash the Activity's serial USB worker.
            val uncertain = sideEffectsStarted || bytesSent > 0L
            val outcome = if (uncertain) "unknown_after_print_command" else "failed_before_print_commands"
            note("Stopped at $stage: ${error.javaClass.simpleName}: ${error.message ?: "no details"}; no automatic resend or recovery")
            return CaptTestPrintResult(outcome,
                if (stage == "CONFIGURING_JOB" || stage.startsWith("RESERVING"))
                    "Подготовка задания остановлена; данные страницы не отправлены. Подробности сохранены в JSON."
                else if (uncertain) "Печать PDF не подтверждена; исход неизвестен. Не запускайте повтор автоматически."
                else "PDF не отправлен: ${error.message ?: error.javaClass.simpleName}",
                stage, bytesSent, null, pages, lastFrame, trace, failureCode = when (error) {
                    is CaptPreflightFailure -> error.code
                    is CaptCommandAckFailure -> "CAPT_COMMAND_REJECTED"
                    is CaptReplyReadFailure -> "CAPT_TRANSPORT_INVALID"
                    else -> null
                }, protocolDiagnostics = protocol.snapshot())
        }
    }
}
