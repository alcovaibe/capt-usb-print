package com.cryonum.captusbprint.printservice

import com.cryonum.captusbprint.core.JobSpecification

/** Android ranges refer to the original document; the delivered PDF may already be reduced. */
internal object SystemPageSelection {
    data class Range(val first: Int, val last: Int)
    data class Selection(val pdfIndices: List<Int>, val mode: String)

    fun resolve(pdfPages: Int, declaredPages: Int, ranges: List<Range>?): Selection {
        val limit = JobSpecification.MAX_SOURCE_PAGES
        require(pdfPages in 1..limit)
        require(declaredPages == -1 || declaredPages in 1..limit)
        require(ranges == null || ranges.size in 1..limit)
        if (ranges == null || ranges == listOf(Range(0, Int.MAX_VALUE))) {
            require(declaredPages == -1 || declaredPages == pdfPages) { "PAGE_COUNT_MISMATCH" }
            return Selection((0 until pdfPages).toList(), "ALL_PDF")
        }
        val selected = sortedSetOf<Int>()
        ranges.forEach {
            require(it.first in 0 until limit && it.last in it.first until limit) { "INVALID_PAGE_RANGES" }
            selected.addAll(it.first..it.last)
        }
        // Full original PDF: apply the selection exactly once.
        if (declaredPages == pdfPages && selected.last() < pdfPages)
            return Selection(selected.toList(), "FULL_PDF")
        // Reduced PDF: page zero is the first selected source page, not source page zero.
        if (pdfPages == selected.size)
            return Selection((0 until pdfPages).toList(), "REDUCED_PDF")
        // Unknown metadata cannot establish a mapping for a partially cached/superset PDF.
        throw IllegalArgumentException("AMBIGUOUS_PAGE_RANGES")
    }
}
