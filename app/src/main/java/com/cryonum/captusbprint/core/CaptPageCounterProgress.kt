package com.cryonum.captusbprint.core

/** Counters are retained after a completed job on the observed Canon LBP2900.
 * The first physical print ended with all counters at 1, not reset to zero.
 * Do not confuse historical completed pages with a currently outstanding job.
 */
data class CaptPageCounters(
    val decoding: Int,
    val printing: Int,
    val out: Int,
    val completed: Int,
    val received: Int?
)

object CaptPageCounterProgress {
    fun snapshot(status: CaptExtendedStatus): CaptPageCounters = CaptPageCounters(
        requireNotNull(status.pageDecoding) { "CAPT decoding counter missing" },
        requireNotNull(status.pagePrinting) { "CAPT printing counter missing" },
        requireNotNull(status.pageOut) { "CAPT shipped counter missing" },
        requireNotNull(status.pageCompleted) { "CAPT completed counter missing" },
        status.pageReceived
    )

    /** All known stage counters agree, engine is not in an auxiliary page phase.
     * This deliberately does not infer that the absolute counts must be zero.
     */
    fun hasNoOutstandingPage(status: CaptExtendedStatus): Boolean {
        val counters = snapshot(status)
        return (status.auxiliaryByte and 0x04) == 0 &&
            counters.decoding == counters.printing &&
            counters.printing == counters.out &&
            counters.out == counters.completed &&
            (counters.received == null || counters.received == counters.completed)
    }

    /** Relative comparison prevents a previous completed sheet from confirming a new job.
     * Fail closed at 16-bit rollover until its behavior is physically established.
     */
    fun nextPageDecoded(initial: CaptPageCounters, current: CaptExtendedStatus): Boolean =
        snapshot(current).decoding > initial.decoding

    fun nextPageCompleted(initial: CaptPageCounters, current: CaptExtendedStatus): Boolean {
        val now = snapshot(current)
        return now.out > initial.out &&
            now.completed > initial.completed &&
            (current.auxiliaryByte and 0x04) == 0
    }
}
