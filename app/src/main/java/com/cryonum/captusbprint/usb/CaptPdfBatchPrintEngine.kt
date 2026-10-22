package com.cryonum.captusbprint.usb

import android.content.Context
import android.os.SystemClock
import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptExtendedStatusParser
import com.cryonum.captusbprint.core.CaptJobProtocol
import com.cryonum.captusbprint.core.CaptPageCounterProgress
import com.cryonum.captusbprint.core.CaptPageCounters
import com.cryonum.captusbprint.core.CaptPrinterInfoParser
import java.util.Locale
import com.cryonum.captusbprint.document.PreparedPdfCaptPage

/**
 * EXPERIMENTAL Stage C: 2..3 already prepared portrait A4 PDF sheets in ONE
 * observed LBP2900 CAPT job. Derives the control sequence from the accepted
 * Stage B two-sheet engine but does not change that implementation.
 *
 * No speculative pipelining: next descriptor follows confirmation of previous
 * shipped/completed counters. No replay, power cycle or recovery on 0x8C.
 */
internal object CaptPdfBatchPrintEngine {
    private const val WAIT_READY_MS = 15_000L
    private const val WAIT_PAGE_MS = 90_000L

    fun run(
        context: Context,
        deviceKey: String,
        prepared: List<PreparedPdfCaptPage>,
        cancelled: () -> Boolean,
        progress: (String) -> Unit = {}
    ): CaptTestPrintResult {
        var stage = "VALIDATING_PDF_BATCH"
        var bytesSent = 0L
        var lastFrame: String? = null
        var confirmedPages: Int? = null
        var sideEffectsStarted = false
        val trace = ArrayList<String>()

        fun note(message: String) {
            if (trace.size < 180) trace.add(message)
            progress(message)
        }
        fun guard() { check(!cancelled()) { "Cancelled; job will not be replayed" } }
        fun hex(raw: ByteArray): String = raw.joinToString(" ") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
        }

        try {
            // Every sheet was bounded and compressed by the caller BEFORE USB.
            // Keep at most three 4 MiB page objects live; never a full ARGB page.
            require(prepared.size in 2..3) { "Only two or three PDF sheets are allowed" }
            prepared.forEach { page ->
                check(page.bandCount == 110 &&
                    page.compressedBytes in 1..PreparedPdfCaptPage.MAX_BUFFERED_PAGE_BYTES) {
                    "Invalid prepared PDF sheet"
                }
            }
            val totalPages = prepared.size
            note("Offline PDF batch ready: $totalPages A4 portrait sheets; no content logged")
            stage = "PREFLIGHT"
            CaptUsbSession.open(context, deviceKey, cancelled).use { session ->
                fun query(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
                    guard()
                    val frame = session.query(command, payload)
                    lastFrame = hex(frame)
                    return frame
                }
                fun state(): CaptExtendedStatus =
                    CaptExtendedStatusParser.parse(query(CaptJobProtocol.GET_EXTENDED_STATUS))
                fun safeState(): CaptExtendedStatus {
                    val s = state()
                    if (s.engineFlags != 0 || s.engineCommunicationByte != 0 ||
                        s.paperAvailabilityByte != 0x80 || (s.basicStatusByte and 0x82) != 0) {
                        throw IllegalStateException("Printer reported error/not ready or unavailable paper")
                    }
                    return s
                }
                fun waitState(timeoutMs: Long, accept: (CaptExtendedStatus) -> Boolean): CaptExtendedStatus {
                    val deadline = SystemClock.elapsedRealtime() + timeoutMs
                    do {
                        guard()
                        val s = state()
                        if (s.engineFlags != 0 || s.engineCommunicationByte != 0 ||
                            s.paperAvailabilityByte != 0x80) {
                            throw IllegalStateException("Printer status changed while waiting")
                        }
                        if (accept(s)) return s
                        Thread.sleep(120)
                    } while (SystemClock.elapsedRealtime() < deadline)
                    throw IllegalStateException("Timed out at $stage; result uncertain after job start")
                }

                val info = CaptPrinterInfoParser.parse(query(CaptJobProtocol.GET_PRINTER_INFO))
                check(info.transferParametersUsable) { "Printer transfer limit invalid" }
                val chunkSize = info.safeChunkBytes ?: error("Missing printer chunk limit")
                check(chunkSize in 64..0xFF00)
                note("Printer Info: Blk=${info.blockSize}; Buf=${info.bufferSize}; chunk=$chunkSize")
                var current = safeState()
                check(CaptPageCounterProgress.hasNoOutstandingPage(current)) { "Pending page before PDF batch" }
                if ((current.secondaryByte and 0x02) != 0) {
                    query(CaptJobProtocol.GET_INPUT_STATUS)
                    current = safeState()
                    check((current.secondaryByte and 0x02) == 0) { "Pending input status change" }
                }

                stage = "RESERVING_ONE_JOB"
                sideEffectsStarted = true
                val jobId = CaptJobProtocol.parseReservedJob(
                    query(CaptJobProtocol.RESERVE_UNIT, CaptJobProtocol.reserveRequest()))
                note("ReserveUnit once; JobID present")
                query(CaptJobProtocol.SET_POWER_DOWN, CaptJobProtocol.powerDownSettings())
                query(CaptJobProtocol.SET_JOB_INFO2,
                    CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.START_FLAG))
                current = safeState()
                if ((current.basicStatusByte and 0x10) == 0) {
                    query(CaptJobProtocol.GO_OFFLINE, byteArrayOf(0, 0))
                    waitState(WAIT_READY_MS) { (it.basicStatusByte and 0x10) != 0 }
                }
                query(CaptJobProtocol.DISCARD_DATA)
                query(CaptJobProtocol.CLEAR_MIS_PRINT)
                query(CaptJobProtocol.CLEAR_ERROR)
                query(CaptJobProtocol.GO_ONLINE, CaptJobProtocol.goOnlineSettings())
                val ready = waitState(WAIT_READY_MS) {
                    (it.basicStatusByte and 0x06) == 0 && (it.basicStatusByte and 0x10) == 0
                }
                check(CaptPageCounterProgress.hasNoOutstandingPage(ready)) { "Page still pending after job prologue" }
                val initial: CaptPageCounters = CaptPageCounterProgress.snapshot(ready)
                // Only the post-GoOnline 0/0/0/0 state has been physically observed
                // for a fresh LBP2900 job. Do not guess StartPrint numbering otherwise.
                check(initial.decoding == 0 && initial.printing == 0 &&
                    initial.out == 0 && initial.completed == 0 && initial.received == 0) {
                    "Unverified initial page numbering in PDF batch"
                }
                note("One reserved job; initial CAPT counters all zero")

                for (number in 1..totalPages) {
                    guard()
                    val page = prepared[number - 1]
                    stage = "PAGE_${number}_DESCRIPTOR"
                    note("Page $number/$totalPages: A4/600dpi PDF descriptor")
                    session.sendFrame(page.descriptorFrame())

                    stage = "PAGE_${number}_TRANSMITTING"
                    var startSent = false
                    var observedBand = -1
                    page.forEachVideoFrame(chunkSize, cancelled = cancelled) { index, frame ->
                        guard()
                        current = safeState()
                        if (current.bufferSlots == 0) {
                            if (!startSent) {
                                waitState(WAIT_READY_MS) {
                                    CaptPageCounterProgress.pageDecodedSince(initial, it, number)
                                }
                                query(CaptJobProtocol.START_PRINT, CaptJobProtocol.pageNumber(number))
                                startSent = true
                                note("StartPrint($number) during bounded buffer drain")
                            }
                            waitState(WAIT_READY_MS) { it.bufferSlots >= 1 }
                        }
                        guard()
                        session.sendFrame(frame)
                        bytesSent += frame.size
                        if (index != observedBand) {
                            observedBand = index
                            if (index % 30 == 0) note("Page $number: sent strip ${index + 1}/110")
                        }
                    }

                    stage = "PAGE_${number}_START_PRINT"
                    session.sendOnly(CaptJobProtocol.BLACK_END)
                    if (!startSent) {
                        waitState(WAIT_READY_MS) {
                            CaptPageCounterProgress.pageDecodedSince(initial, it, number)
                        }
                        query(CaptJobProtocol.START_PRINT, CaptJobProtocol.pageNumber(number))
                        note("StartPrint($number) after IC_BLACK_END")
                    }

                    stage = "PAGE_${number}_WAITING_CONFIRMATION"
                    val observed = waitState(WAIT_PAGE_MS) {
                        CaptPageCounterProgress.pagesCompletedSince(initial, it, number) &&
                            CaptPageCounterProgress.hasNoOutstandingPage(it) &&
                            it.pageCompleted == number
                    }
                    confirmedPages = number
                    note("PDF page $number/$totalPages: counters agree at completed=${observed.pageCompleted}; visual check is separate")
                    if (number < totalPages) {
                        // Upstream LBP2900 sends job continuation once after Printed>=1,
                        // between pages, not a second ReserveUnit/GoOnline.
                        stage = "CONTINUING_SAME_JOB"
                        query(CaptJobProtocol.SET_JOB_INFO2,
                            CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.CONTINUE_FLAG))
                        note("SetJobInfo2(flag=2) once, still the same reserved JobID")
                    }
                }

                stage = "FINALIZING_ONE_JOB"
                query(CaptJobProtocol.SET_JOB_INFO2,
                    CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.END_FLAG))
                query(CaptJobProtocol.RELEASE_UNIT, CaptJobProtocol.pageNumber(jobId))
                stage = "PDF_BATCH_COUNTER_CONFIRMED"
                note("$totalPages PDF sheets confirmed by CAPT counters; one job released; owner must check paper")
            }

            return CaptTestPrintResult(
                "printer_counter_confirmed",
                "CAPT подтвердил $totalPages PDF-листа в одном задании. Проверь физические отпечатки.",
                stage, bytesSent, null, confirmedPages, lastFrame, trace
            )
        } catch (error: Throwable) {
            val uncertain = sideEffectsStarted || bytesSent > 0L
            val outcome = if (uncertain) "unknown_after_print_command" else "failed_before_print_commands"
            note("Stopped at $stage: ${error.javaClass.simpleName}: ${error.message ?: "no details"}; no automatic retry or recovery")
            return CaptTestPrintResult(
                outcome,
                if (uncertain) "Многостраничная PDF-печать не подтверждена; возможен частичный выпуск листов. Не повторяйте задание автоматически."
                else "PDF-задание не отправлено: ${error.message ?: error.javaClass.simpleName}",
                stage, bytesSent, null, confirmedPages, lastFrame, trace
            )
        }
    }
}
