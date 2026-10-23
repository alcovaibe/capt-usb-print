package com.cryonum.captusbprint.core

import java.util.Locale

/** LBP2900 job admission, shared by documents, the PDF pilot and both tests.
 *
 * The pinned lbp3000_job_prologue uses ExtendedStatus/InputStatus at startup;
 * LBP2900 get_status is capt_get_xstatus. The owner's six-byte Basic reply has
 * no verified status contract and is retained by diagnostics, not interpreted
 * as an additional job-admission veto. No value is rewritten or whitelisted.
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
    ): CaptExtendedStatus = awaitExtended(query, guard, now, pause, note, "Preflight")

    /** SetPowerDown is power configuration, not print acceptance. Upstream checks
     * status after it and waits for NOTREADY to clear before SetJobInfo2(START).
     * The reservation is already owned here; do not require a FREE flag.
     */
    fun afterPowerDown(
        query: (Int) -> ByteArray,
        guard: () -> Unit,
        now: () -> Long,
        pause: (Long) -> Unit,
        note: (String) -> Unit
    ): CaptExtendedStatus = awaitExtended(query, guard, now, pause, note, "JobSetup")

    private fun awaitExtended(
        query: (Int) -> ByteArray,
        guard: () -> Unit,
        now: () -> Long,
        pause: (Long) -> Unit,
        note: (String) -> Unit,
        phase: String
    ): CaptExtendedStatus {
        val deadline = now() + POLL_BUDGET_MS
        // The sample cap also bounds work if an injected/broken clock stalls.
        repeat(MAX_SAMPLES) { index ->
            guard()
            fun read(command: Int, label: String): ByteArray {
                guard()
                val raw = query(command)
                val hex = raw.take(64).joinToString(" ") {
                    String.format(Locale.ROOT, "%02X", it.toInt() and 255)
                }
                note("$phase ${index + 1} $label bytes=${raw.size}: $hex" +
                    if (raw.size > 64) " [truncated]" else "")
                return raw
            }
            val extended = parse(phase) {
                CaptExtendedStatusParser.parse(read(CaptJobProtocol.GET_EXTENDED_STATUS, "GetExtendedStatus"))
            }
            val idle = parse(phase) {
                requireNotNull(extended.pageReceived) { "Missing received-page counter" }
                CaptPageCounterProgress.hasNoOutstandingPage(extended)
            }
            if (extended.engineFlags != 0 || extended.engineCommunicationByte != 0 ||
                extended.paperAvailabilityByte != 0x80 || (extended.basicStatusByte and 0x80) != 0) {
                throw CaptPreflightFailure("PRINTER_STATUS",
                    "$phase: extended CAPT error or unavailable paper; no page data sent")
            }
            val inputPending = (extended.secondaryByte and 0x02) != 0
            val busy = (extended.basicStatusByte and 0x0e) != 0 ||
                (extended.auxiliaryByte and 0x06) != 0
            if (!inputPending && !busy && idle) {
                guard()
                // OFFLINE is expected before GoOnline; reservation acceptance
                // is established by ReserveUnit's own Error/JobID contract.
                note("$phase: LBP2900 ExtendedStatus/InputStatus settled after ${index + 1} sample(s)")
                return extended
            }
            if (inputPending) {
                parse(phase) { CaptInputStatusParser.parse(read(CaptJobProtocol.GET_INPUT_STATUS, "GetInputStatus")) }
                // Only a fresh ExtendedStatus can show that the request cleared.
            }
            guard()
            val remaining = deadline - now()
            if (remaining <= 0 || index == MAX_SAMPLES - 1) {
                throw CaptPreflightFailure("PRINTER_STATUS",
                    "$phase did not settle: inputPending=$inputPending, busy=$busy, idle=$idle; no page data sent")
            }
            pause(minOf(INTERVAL_MS, remaining))
        }
        error("Unreachable preflight sample limit")
    }

    private fun <T> parse(phase: String, block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) {
        throw CaptPreflightFailure("CAPT_STATUS_INVALID", "$phase: invalid/incomplete CAPT status; no page data sent")
    }
}

class CaptPreflightFailure(val code: String, message: String) : IllegalStateException(message)
