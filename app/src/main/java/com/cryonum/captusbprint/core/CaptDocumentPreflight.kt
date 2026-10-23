package com.cryonum.captusbprint.core

import java.util.Locale

/** A bounded, read-only status reconciliation before the first ReserveUnit.
 *
 * The pinned upstream's lbp3000_job_prologue settles NEED_INPUT_STATUS before
 * reservation. A single stale/busy sample must not substitute for that phase.
 * Conflicting basic/extended replies remain a stop, never permission to reset
 * or bypass the basic-status gate. Stage B's accepted engines are unchanged.
 */
object CaptDocumentPreflight {
    private const val POLL_BUDGET_MS = 5_000L
    private const val INTERVAL_MS = 250L
    private const val MAX_SAMPLES = 21

    fun awaitReady(
        query: (Int) -> ByteArray,
        guard: () -> Unit,
        now: () -> Long,
        pause: (Long) -> Unit,
        note: (String) -> Unit
    ): CaptExtendedStatus {
        val deadline = now() + POLL_BUDGET_MS
        var reason = "PRINTER_STATUS"
        // The sample cap also bounds work if an injected/broken clock stalls.
        repeat(MAX_SAMPLES) { index ->
            guard()
            fun read(command: Int, label: String): ByteArray {
                guard()
                val raw = query(command)
                val hex = raw.take(64).joinToString(" ") {
                    String.format(Locale.ROOT, "%02X", it.toInt() and 255)
                }
                note("Preflight ${index + 1} $label bytes=${raw.size}: $hex" +
                    if (raw.size > 64) " [truncated]" else "")
                return raw
            }
            val basicRaw = read(CaptJobProtocol.GET_BASIC_STATUS, "GetBasicStatus")
            val basic = parse { CaptBasicStatusParser.parse(basicRaw) }
            val extendedRaw = read(CaptJobProtocol.GET_EXTENDED_STATUS, "GetExtendedStatus")
            val extended = parse { CaptExtendedStatusParser.parse(extendedRaw) }
            val idle = parse { CaptPageCounterProgress.hasNoOutstandingPage(extended) }

            if (extended.engineFlags != 0 || extended.engineCommunicationByte != 0 ||
                extended.paperAvailabilityByte != 0x80 || (extended.basicStatusByte and 0x82) != 0) {
                throw CaptPreflightFailure("PRINTER_STATUS",
                    "Extended CAPT status reports not-ready/error or unavailable paper; ReserveUnit not sent")
            }
            val inputPending = ((basic.secondaryByte or extended.secondaryByte) and 0x02) != 0
            val basicBlocked = PdfBatchPrinterReadiness.rejection(basic.statusByte) != null
            val extendedBusy = (extended.basicStatusByte and 0x0c) != 0 ||
                (extended.auxiliaryByte and 0x06) != 0
            if (!inputPending && !basicBlocked && !extendedBusy && idle) {
                guard()
                note("Read-only CAPT preflight settled after ${index + 1} sample(s); no recovery commands")
                return extended
            }
            reason = if (!inputPending && basicBlocked && !extendedBusy && idle)
                "CAPT_STATUS_CONFLICT" else "PRINTER_STATUS"
            if (inputPending) {
                val inputRaw = read(CaptJobProtocol.GET_INPUT_STATUS, "GetInputStatus")
                parse { CaptInputStatusParser.parse(inputRaw) }
                // Re-read BOTH replies; the input query is not itself proof of readiness.
            }
            guard()
            val remaining = deadline - now()
            if (remaining <= 0 || index == MAX_SAMPLES - 1) {
                throw CaptPreflightFailure(reason,
                    "CAPT preflight did not settle: $reason; ReserveUnit not sent; no automatic recovery")
            }
            pause(minOf(INTERVAL_MS, remaining))
        }
        error("Unreachable preflight sample limit")
    }

    private fun <T> parse(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) {
        throw CaptPreflightFailure("CAPT_STATUS_INVALID", "Invalid/incomplete CAPT status; ReserveUnit not sent")
    }
}

class CaptPreflightFailure(val code: String, message: String) : IllegalStateException(message)
