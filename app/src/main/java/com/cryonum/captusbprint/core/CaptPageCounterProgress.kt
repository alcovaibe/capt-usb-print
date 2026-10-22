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

    /** The CAPT decoder must acknowledge this job's current page, not an old one.
     * No rollover behavior is assumed. Used by two-page StartPrint(1)/(2).
     */
    fun pageDecodedSince(
        initial: CaptPageCounters, current: CaptExtendedStatus, page: Int
    ): Boolean {
        require(page in 1..2) { "Only the bounded two-sheet test is supported" }
        require(initial.decoding in 0..0xffff - page) { "Unverified decoding counter rollover" }
        return snapshot(current).decoding >= initial.decoding + page
    }

    /** Offline-tested two-page checkpoint. Never counts a previous job's pages.
     * This is not called by the physical printer path until repeat-job testing.
     */
    fun pagesCompletedSince(
        initial: CaptPageCounters, current: CaptExtendedStatus, expected: Int
    ): Boolean {
        require(expected in 1..2) { "Only the two-page Stage B plan is supported" }
        require(initial.completed in 0..0xffff - expected &&
            initial.out in 0..0xffff - expected) { "Unverified counter rollover" }
        val now = snapshot(current)
        return now.out >= initial.out + expected &&
            now.completed >= initial.completed + expected &&
            (current.auxiliaryByte and 0x04) == 0
    }

    fun nextPageCompleted(initial: CaptPageCounters, current: CaptExtendedStatus): Boolean {
        val now = snapshot(current)
        return now.out > initial.out &&
            now.completed > initial.completed &&
            (current.auxiliaryByte and 0x04) == 0
    }
}
