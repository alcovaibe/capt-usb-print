package com.cryonum.captusbprint.usb

import android.content.Context
import com.cryonum.captusbprint.core.CaptProtocolTrace
import com.cryonum.captusbprint.core.CaptCommandAckFailure
import com.cryonum.captusbprint.core.CaptReplyReadFailure
import android.os.SystemClock
import com.cryonum.captusbprint.core.CaptDocumentPreflight
import com.cryonum.captusbprint.core.CaptPrinterRecovery
import com.cryonum.captusbprint.core.CaptPaperOut
import com.cryonum.captusbprint.core.CaptPrintCancelled
import com.cryonum.captusbprint.core.CaptInputStatusParser
import com.cryonum.captusbprint.core.CaptPreflightFailure
import com.cryonum.captusbprint.core.CaptExtendedStatus
import com.cryonum.captusbprint.core.CaptExtendedStatusParser
import com.cryonum.captusbprint.core.CaptJobProtocol
import com.cryonum.captusbprint.core.CaptPageCounterProgress
import com.cryonum.captusbprint.core.CaptPageCounters
import com.cryonum.captusbprint.core.DocumentPageProgress
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.CaptPrinterInfoParser
import java.util.Locale
import com.cryonum.captusbprint.document.PreparedCaptPage
import com.cryonum.captusbprint.document.OfflinePdfHiscoaPreparation

/**
 * Stage C: bounded disk-prepared A4 document sheets in ONE
 * observed LBP2900 CAPT job. Derives the control sequence from the accepted
 * Stage B two-sheet engine, with shared LBP2900 startup admission.
 *
 * No speculative pipelining: next descriptor follows confirmation of previous
 * shipped/completed counters. No replay, power cycle or recovery on 0x8C.
 */
internal object CaptDocumentPrintEngine {
    private const val WAIT_READY_MS = 15_000L
    private const val WAIT_PAGE_MS = 90_000L

    fun run(
        context: Context,
        deviceKey: String,
        prepared: List<PreparedCaptPage>,
        cancelled: () -> Boolean,
        progress: (String) -> Unit = {},
        checkpoint: (CaptTestPrintResult) -> Unit = {},
        owner: String = "DOCUMENT_QUEUE"
    ): CaptTestPrintResult {
        val generation = CaptPrinterStateStore.generation()
        val stop = { cancelled() || CaptPrinterStateStore.generation() != generation }
        val protocol = CaptProtocolTrace(now = { SystemClock.elapsedRealtime() })
        protocol.lifecycle = { CaptPrinterStateStore.record(context, it) }
        val result = runPrepared(
            prepared, stop, progress, checkpoint,
            openSession = {
                check(!CaptPrinterStateStore.requiresClear(context)) { "PRINTER_CLEAR_REQUIRED" }
                // A soft cancel must not interrupt a reply or a CAPT frame.
                val usb = CaptUsbSession.open(context, deviceKey,
                    { Thread.currentThread().isInterrupted }, protocol, owner)
                object : PdfBatchSession {
                    override fun query(command: Int, payload: ByteArray) = usb.query(command, payload)
                    override fun sendFrame(frame: ByteArray) = usb.sendFrame(frame)
                    override fun sendOnly(command: Int) = usb.sendOnly(command)
                    override fun close() = usb.close()
                }
            },
            now = { SystemClock.elapsedRealtime() },
            pause = { Thread.sleep(it) }, protocol = protocol
        )
        if (result.outcome == "unknown_after_print_command")
            runCatching { CaptPrinterStateStore.requireClear(context, "UNCERTAIN_PRINT_ATTEMPT") }
                .onFailure { protocol.event("LEDGER_WRITE_FAILED", mapOf("failureClass" to it.javaClass.simpleName)) }
        return result.copy(protocolDiagnostics = protocol.snapshot())
    }

    /** Same production sequence with injected I/O and clock for bounded fault tests. */
    internal fun runPrepared(
        prepared: List<PreparedCaptPage>,
        cancelled: () -> Boolean,
        progress: (String) -> Unit = {},
        checkpoint: (CaptTestPrintResult) -> Unit = {},
        openSession: () -> PdfBatchSession,
        now: () -> Long,
        pause: (Long) -> Unit,
        protocol: CaptProtocolTrace = CaptProtocolTrace(now = now)
    ): CaptTestPrintResult {
        var stage = "VALIDATING_DOCUMENT_JOB"
        protocol.phase = { stage }
        var bytesSent = 0L
        var lastFrame: String? = null
        var confirmedPages: Int? = null
        var sideEffectsStarted = false
        var ownedJob: Int? = null
        var jobStarted = false
        var wireAligned = true
        var cleanupMode = false
        var cleaned = false
        var pageAnnounced = false
        var paperBoundaryKnown = false
        val trace = ArrayList<String>()

        fun note(message: String) {
            if (trace.size < 180) trace.add(message)
            progress(message)
        }
        fun guard() {
            check(!Thread.currentThread().isInterrupted) { "Worker interrupted; connection state uncertain" }
            if (!cleanupMode && cancelled()) throw CaptPrintCancelled()
        }
        fun hex(raw: ByteArray): String = raw.joinToString(" ") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
        }

        try {
            // Every sheet was bounded and compressed by the caller BEFORE USB.
            // Disk-backed pages retain metadata only; video frames are read one band at a time.
            require(prepared.size in 1..JobSpecification.MAX_OUTPUT_PAGES) { "Document output exceeds the bounded job limit" }
            prepared.forEach { page ->
                check(page.bandCount == 110 &&
                    page.compressedBytes in 1..OfflinePdfHiscoaPreparation.MAX_COMPRESSED_PAGE_BYTES) {
                    "Invalid prepared document sheet"
                }
            }
            val totalPages = prepared.size
            note("Offline document job ready: $totalPages physical A4 sheets; no content logged")
            stage = "PREFLIGHT"
            guard()
            openSession().use { session ->
                fun query(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
                    guard()
                    wireAligned = false
                    val frame = session.query(command, payload)
                    wireAligned = true
                    lastFrame = hex(frame)
                    protocol.reply(command, frame, payload)
                    return frame
                }
                fun send(frame: ByteArray) {
                    guard(); wireAligned = false
                    session.sendFrame(frame)
                    wireAligned = true
                }
                fun state(): CaptExtendedStatus {
                    var s = CaptExtendedStatusParser.parse(query(CaptJobProtocol.GET_EXTENDED_STATUS))
                    if (CaptPrinterRecovery.isPaperOut(s)) return s
                    if ((s.secondaryByte and 2) != 0) {
                        CaptInputStatusParser.parse(query(CaptJobProtocol.GET_INPUT_STATUS))
                        s = CaptExtendedStatusParser.parse(query(CaptJobProtocol.GET_EXTENDED_STATUS))
                    }
                    return s
                }
                fun safeState(): CaptExtendedStatus {
                    val s = state()
                    if (CaptPrinterRecovery.isPaperOut(s)) throw CaptPaperOut(s)
                    if (s.engineFlags != 0 || s.engineCommunicationByte != 0 ||
                        s.paperAvailabilityByte != 0x80 || (s.basicStatusByte and 0x82) != 0) {
                        throw IllegalStateException("Printer reported error/not ready or unavailable paper")
                    }
                    return s
                }
                fun waitState(timeoutMs: Long, accept: (CaptExtendedStatus) -> Boolean): CaptExtendedStatus {
                    val deadline = now() + timeoutMs
                    do {
                        guard()
                        val s = state()
                        if (CaptPrinterRecovery.isPaperOut(s)) throw CaptPaperOut(s)
                        if (s.engineFlags != 0 || s.engineCommunicationByte != 0 ||
                            s.paperAvailabilityByte != 0x80) {
                            throw IllegalStateException("Printer status changed while waiting")
                        }
                        if (accept(s)) return s
                        pause(120)
                    } while (now() < deadline)
                    throw IllegalStateException("Timed out at $stage; result uncertain after job start")
                }

                try {
                    val info = CaptPrinterInfoParser.parse(query(CaptJobProtocol.GET_PRINTER_INFO))
                    check(info.transferParametersUsable) { "Printer transfer limit invalid" }
                    val chunkSize = info.safeChunkBytes ?: error("Missing printer chunk limit")
                    check(chunkSize in 64..0xFF00)
                    note("Printer Info: Blk=${info.blockSize}; Buf=${info.bufferSize}; chunk=$chunkSize")
                    var current = CaptDocumentPreflight.awaitReady(
                        { query(it) }, ::guard, now, pause, ::note)
                    stage = "RESERVING_ONE_JOB"
                    sideEffectsStarted = true
                    val jobId = CaptJobProtocol.parseReservedJob(
                        query(CaptJobProtocol.RESERVE_UNIT, CaptJobProtocol.reserveRequest()))
                    ownedJob = jobId
                    protocol.reserved(jobId)
                    note("ReserveUnit once; JobID present")
                    stage = "CONFIGURING_JOB"
                    query(CaptJobProtocol.SET_POWER_DOWN, CaptJobProtocol.powerDownSettings())
                    CaptDocumentPreflight.afterPowerDown(
                        { query(it) }, ::guard, now, pause, ::note)
                    query(CaptJobProtocol.SET_JOB_INFO2,
                        CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.START_FLAG))
                    jobStarted = true
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
                        "Unverified initial page numbering in document batch"
                    }
                    note("One reserved job; initial CAPT counters all zero")

                    for (number in 1..totalPages) {
                        guard()
                        val page = prepared[number - 1]
                        stage = "PAGE_${number}_DESCRIPTOR"
                        note("Page $number/$totalPages: A4/600dpi document descriptor")
                        pageAnnounced = true
                        send(page.descriptorFrame())

                        stage = "PAGE_${number}_TRANSMITTING"
                        var startSent = false
                        var observedBand = -1
                        page.forEachVideoFrame(chunkSize, cancelled = cancelled) { index, frame ->
                            guard()
                            current = safeState()
                            if (current.bufferSlots == 0) {
                                if (!startSent) {
                                    waitState(WAIT_READY_MS) {
                                        DocumentPageProgress.decoded(initial, it, number)
                                    }
                                    query(CaptJobProtocol.START_PRINT, CaptJobProtocol.pageNumber(number))
                                    startSent = true
                                    note("StartPrint($number) during bounded buffer drain")
                                }
                                waitState(WAIT_READY_MS) { it.bufferSlots >= 1 }
                            }
                            guard()
                            send(frame)
                            bytesSent += frame.size
                            if (index != observedBand) {
                                observedBand = index
                                if (index % 30 == 0) note("Page $number: sent strip ${index + 1}/110")
                            }
                        }

                        stage = "PAGE_${number}_START_PRINT"
                        guard(); wireAligned = false
                        session.sendOnly(CaptJobProtocol.BLACK_END)
                        wireAligned = true
                        if (!startSent) {
                            waitState(WAIT_READY_MS) {
                                DocumentPageProgress.decoded(initial, it, number)
                            }
                            query(CaptJobProtocol.START_PRINT, CaptJobProtocol.pageNumber(number))
                            note("StartPrint($number) after IC_BLACK_END")
                        }

                        stage = "PAGE_${number}_WAITING_CONFIRMATION"
                        val observed = waitState(WAIT_PAGE_MS) {
                            DocumentPageProgress.completed(initial, it, number)
                        }
                        confirmedPages = number
                        protocol.pagesConfirmed(number)
                        note("document page $number/$totalPages: counters agree at completed=${observed.pageCompleted}; visual check is separate")
                        // Persist each confirmed sheet BEFORE another printer command.
                        // A crash can retain a lower bound, never imply the whole job succeeded.
                        checkpoint(CaptTestPrintResult("in_progress", "document page counter checkpoint",
                            stage, bytesSent, null, confirmedPages, lastFrame, trace.toList(),
                            protocolDiagnostics = protocol.snapshot()))
                        if (number == 1 && totalPages > 1) {
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
                    stage = "DOCUMENT_COUNTER_CONFIRMED"
                    note("$totalPages document sheets confirmed by CAPT counters; one job released; owner must check paper")
                } catch (stop: Throwable) {
                    if (stop is CaptPaperOut) paperBoundaryKnown = !pageAnnounced ||
                        CaptPrinterRecovery.canResumeAfter(stop.status, confirmedPages ?: 0)
                    val softStop = stop is CaptPrintCancelled || stop is CaptPaperOut ||
                        (stop is java.util.concurrent.CancellationException && cancelled())
                    if (softStop && wireAligned && !Thread.currentThread().isInterrupted) {
                        cleanupMode = true
                        stage = "CANCELLING_JOB"
                        try {
                            val id = ownedJob
                            if (id != null) {
                                if (confirmedPages == totalPages) {
                                    if (protocol.snapshot()["endAcknowledged"] != true)
                                        query(CaptJobProtocol.SET_JOB_INFO2,
                                            CaptJobProtocol.jobInfo(id, CaptJobProtocol.END_FLAG))
                                    if (protocol.snapshot()["releaseAcknowledged"] != true)
                                        query(CaptJobProtocol.RELEASE_UNIT, CaptJobProtocol.pageNumber(id))
                                } else CaptPrinterRecovery.cancelOwned(::query, id, jobStarted)
                                val deadline = now() + WAIT_READY_MS
                                var samples = 0
                                var settled = state()
                                while ((settled.auxiliaryByte and 0x04) != 0 && now() < deadline && samples++ < 126) {
                                    pause(120); settled = state()
                                }
                                check(settled.engineFlags == 0 && (settled.auxiliaryByte and 0x04) == 0 &&
                                    (settled.engineCommunicationByte == 0 || CaptPrinterRecovery.isPaperOut(settled))) {
                                    "Cancellation release/delivery not settled"
                                }
                            }
                            cleaned = true
                            protocol.event("OWNED_JOB_CLEANED", mapOf("paperOut" to (stop is CaptPaperOut)))
                        } catch (cleanup: Throwable) {
                            protocol.event("CLEANUP_FAILED", mapOf("type" to cleanup.javaClass.simpleName))
                            note("Cancellation/clear not acknowledged; no guessed release or replay")
                        }
                    }
                    throw stop
                }
            }

            return CaptTestPrintResult(
                "printer_counter_confirmed",
                "CAPT подтвердил $totalPages листа в одном задании. Проверь физические отпечатки.",
                stage, bytesSent, null, confirmedPages, lastFrame, trace,
                protocolDiagnostics = protocol.snapshot()
            )
        } catch (error: Throwable) {
            val uncertain = sideEffectsStarted || bytesSent > 0L
            val outcome = when {
                error is CaptPaperOut && cleaned && paperBoundaryKnown -> "waiting_for_paper"
                (error is CaptPrintCancelled || error is java.util.concurrent.CancellationException) &&
                    (cleaned || !uncertain) -> "cancelled"
                uncertain -> "unknown_after_print_command"
                else -> "failed_before_print_commands"
            }
            note("Stopped at $stage: ${error.javaClass.simpleName}: ${error.message ?: "no details"}; no automatic retry or recovery")
            return CaptTestPrintResult(
                outcome,
                if (outcome == "waiting_for_paper") "Бумага закончилась. Отправка остановлена; подтверждено ${confirmedPages ?: 0} лист(а). Загрузите бумагу."
                else if (outcome == "cancelled") "Задание отменено. Уже принятый лист может выйти."
                else if ((confirmedPages ?: 0) > 0) "CAPT подтвердил $confirmedPages лист(а); дальнейший исход задания не подтверждён. Автоматического повтора нет."
                else if (stage == "CONFIGURING_JOB" || stage.startsWith("RESERVING"))
                    "Подготовка задания остановлена; данные страницы не отправлены. Подробности сохранены в JSON."
                else if (uncertain) "Печать документа не подтверждена; возможен частичный выпуск листов. Не повторяйте задание автоматически."
                else "Задание не отправлено: ${error.message ?: error.javaClass.simpleName}",
                stage, bytesSent, null, confirmedPages, lastFrame, trace,
                failureCode = when (error) {
                    is CaptPaperOut -> if (cleaned && paperBoundaryKnown) "PAPER_OUT" else if (cleaned) "PAPER_BOUNDARY_UNKNOWN" else "PRINTER_CLEAR_REQUIRED"
                    is CaptPrintCancelled -> if (cleaned || !uncertain) null else "PRINTER_CLEAR_REQUIRED"
                    is CaptPreflightFailure -> error.code
                    is CaptCommandAckFailure -> "CAPT_COMMAND_REJECTED"
                    is CaptReplyReadFailure -> "CAPT_TRANSPORT_INVALID"
                    else -> null
                }, protocolDiagnostics = protocol.snapshot()
            )
        }
    }
}
