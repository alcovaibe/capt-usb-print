package com.cryonum.captusbprint.usb

import android.content.Context
import com.cryonum.captusbprint.core.CaptProtocolTrace
import com.cryonum.captusbprint.core.CaptCommandAckFailure
import com.cryonum.captusbprint.core.CaptReplyReadFailure
import android.os.SystemClock
import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptExtendedStatusParser
import com.cryonum.captusbprint.core.CaptJobProtocol
import com.cryonum.captusbprint.core.CaptPageCounterProgress
import com.cryonum.captusbprint.core.CaptPageCounters
import com.cryonum.captusbprint.core.CaptPageProtocol
import com.cryonum.captusbprint.core.CaptPrinterInfoParser
import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.core.HiscoaNative
import java.security.MessageDigest
import java.util.Locale

/**
 * Separate, deliberately sequential two-sheet Stage B experiment.
 * Both different sheets use ONE ReserveUnit and ONE SetJobInfo2(start/end)
 * pair. The verified single-page engine is not changed.
 *
 * The second descriptor is deliberately sent only after the first sheet's
 * completion is confirmed. No speculative pipelining or recovery/replay.
 */
object CaptTwoPagePrintEngine {
    private const val MAX_PAGE_COMPRESSED = 4 * 1024 * 1024
    private const val WAIT_READY_MS = 15_000L
    private const val WAIT_PAGE_MS = 90_000L

    private data class PreparedPage(val bands: List<ByteArray>, val bytes: Int, val sha: String)

    fun run(
        context: Context,
        deviceKey: String,
        cancelled: () -> Boolean,
        progress: (String) -> Unit = {}
    ): CaptTestPrintResult {
        var stage = "PREPARING_TWO_PAGES"
        val protocol = CaptProtocolTrace(phase = { stage }, now = { SystemClock.elapsedRealtime() })
        var bytesSent = 0L
        var combinedSha: String? = null
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
            // Both pages are compressed before USB reservation. No second sheet
            // is synthesized after the printer has accepted the first sheet.
            val prepared = ArrayList<PreparedPage>(2)
            val combinedDigest = MessageDigest.getInstance("SHA-256")
            for (number in 1..2) {
                val digest = MessageDigest.getInstance("SHA-256")
                val bands = ArrayList<ByteArray>(110)
                var compressedBytes = 0
                var y = 0
                while (y < CaptTestPage.HEIGHT) {
                    guard()
                    val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - y)
                    val band = HiscoaNative.encodeBand(
                        CaptTestPage.band(y, lines, number), CaptTestPage.LINE_BYTES,
                        lines, y + lines == CaptTestPage.HEIGHT)
                    require(band.isNotEmpty() && band.size <= CaptTestPage.LINE_BYTES * lines * 2 + 64)
                    compressedBytes += band.size
                    require(compressedBytes <= MAX_PAGE_COMPRESSED) { "Page $number exceeds bounded budget" }
                    bands.add(band)
                    digest.update(band)
                    combinedDigest.update(band)
                    y += lines
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                prepared.add(PreparedPage(bands, compressedBytes, sha))
                note("Offline page $number prepared: bands=${bands.size}, compressedBytes=$compressedBytes, sha256=$sha")
            }
            combinedSha = combinedDigest.digest().joinToString("") {
                "%02x".format(it.toInt() and 255)
            }

            stage = "PREFLIGHT"
            CaptUsbSession.open(context, deviceKey, cancelled, protocol, "TEST_TWO_PAGES").use { session ->
                fun query(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
                    guard()
                    val frame = session.query(command, payload)
                    lastFrame = hex(frame)
                    protocol.reply(command, frame, payload)
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
                check(CaptPageCounterProgress.hasNoOutstandingPage(current)) { "Pending page before two-page job" }
                if ((current.secondaryByte and 0x02) != 0) {
                    query(CaptJobProtocol.GET_INPUT_STATUS)
                    current = safeState()
                    check((current.secondaryByte and 0x02) == 0) { "Pending input status change" }
                }

                stage = "RESERVING_ONE_JOB"
                sideEffectsStarted = true
                val jobId = CaptJobProtocol.parseReservedJob(
                    query(CaptJobProtocol.RESERVE_UNIT, CaptJobProtocol.reserveRequest()))
                protocol.reserved(jobId)
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
                    "Unverified initial page numbering in two-sheet mode"
                }
                note("One reserved job; initial CAPT counters all zero")

                for (number in 1..2) {
                    guard()
                    val page = prepared[number - 1]
                    stage = "PAGE_${number}_DESCRIPTOR"
                    note("Page $number/2: A4/600dpi descriptor, unique raster")
                    session.sendFrame(CaptPageProtocol.beginA4TestPage())

                    stage = "PAGE_${number}_TRANSMITTING"
                    var startSent = false
                    for ((index, band) in page.bands.withIndex()) {
                        guard()
                        for (frame in CaptPageProtocol.videoData(band, chunkSize)) {
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
                        }
                        if (index % 30 == 0) note("Page $number: sent ${index + 1}/${page.bands.size} bands")
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
                    protocol.pagesConfirmed(number)
                    note("Page $number/2: printer counters agree at completed=${observed.pageCompleted}; physical page must be checked by owner")
                    if (number == 1) {
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
                stage = "TWO_PAGES_CONFIRMED_BY_PRINTER"
                note("Two new completed CAPT pages; one job ended/released once; visual result awaits owner")
            }

            return CaptTestPrintResult(
                "printer_counter_confirmed",
                "CAPT подтвердил два листа в одном задании. Проверь оба физических отпечатка.",
                stage, bytesSent, combinedSha, confirmedPages, lastFrame, trace,
                protocolDiagnostics = protocol.snapshot()
            )
        } catch (error: Throwable) {
            val uncertain = sideEffectsStarted || bytesSent > 0L
            val outcome = if (uncertain) "unknown_after_print_command" else "failed_before_print_commands"
            note("Stopped at $stage: ${error.javaClass.simpleName}: ${error.message ?: "no details"}; no automatic retry or recovery")
            return CaptTestPrintResult(
                outcome,
                if (uncertain) "Двухстраничная печать не подтверждена; исход неизвестен. Не запускайте повторно."
                else "Двухстраничное задание не отправлено: ${error.message ?: error.javaClass.simpleName}",
                stage, bytesSent, combinedSha, confirmedPages, lastFrame, trace,
                failureCode = when (error) {
                    is CaptCommandAckFailure -> "CAPT_COMMAND_REJECTED"
                    is CaptReplyReadFailure -> "CAPT_TRANSPORT_INVALID"
                    else -> null
                }, protocolDiagnostics = protocol.snapshot()
            )
        }
    }
}
