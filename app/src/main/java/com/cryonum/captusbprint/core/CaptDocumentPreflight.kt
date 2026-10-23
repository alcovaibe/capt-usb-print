package com.cryonum.captusbprint.core

import java.util.Locale

/** LBP2900 job admission, shared by documents, the PDF pilot and both tests.
 *
 * The previously working Android paths use ExtendedStatus/InputStatus. The
 * pinned prologue settles input before reservation but does NOT impose our
 * old Basic 0x8e admission mask. Its capt_get_xstatus helper is Basic-first,
 * with conditional Extended refresh; we deliberately use fresh Extended here
 * rather than assume the owner's six-byte Basic reply has that full contract.
 * No value is rewritten or whitelisted; diagnostics retain the Basic reply.
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

    /** Upstream checks status/NOTREADY after SetPowerDown before JobInfo START.
     * This Android policy uses fresh Extended/Input, as in awaitReady, instead
     * of upstream's Basic-first cached helper. The configuration response is
     * not print acceptance; a reservation can already have cleared FREE.
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
