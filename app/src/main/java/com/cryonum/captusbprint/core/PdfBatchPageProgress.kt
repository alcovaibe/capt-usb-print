package com.cryonum.captusbprint.core

/** Bounded Stage C counters; the accepted two-sheet Stage B helpers stay unchanged. */
internal object PdfBatchPageProgress {
    private fun target(initial: Int, page: Int): Int {
        require(page in 1..3) { "Only the two/three-sheet PDF pilot is supported" }
        require(initial in 0..0xffff - page) { "Unverified CAPT counter rollover" }
        return initial + page
    }

    fun decoded(initial: CaptPageCounters, current: CaptExtendedStatus, page: Int): Boolean =
        CaptPageCounterProgress.snapshot(current).decoding == target(initial.decoding, page)

    fun completed(initial: CaptPageCounters, current: CaptExtendedStatus, page: Int): Boolean {
        val expectedOut = target(initial.out, page)
        val expectedCompleted = target(initial.completed, page)
        val now = CaptPageCounterProgress.snapshot(current)
        return now.out == expectedOut && now.completed == expectedCompleted &&
            CaptPageCounterProgress.hasNoOutstandingPage(current)
    }
}
