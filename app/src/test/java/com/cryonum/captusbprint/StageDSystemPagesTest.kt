package com.cryonum.captusbprint

import com.cryonum.captusbprint.printservice.SystemPageSelection
import com.cryonum.captusbprint.printservice.SystemPageSelection.Range
import com.cryonum.captusbprint.printservice.SystemPrintRequest
import com.cryonum.captusbprint.core.DocumentLayout
import com.cryonum.captusbprint.core.Orientation
import org.junit.Assert.*
import org.junit.Test

class StageDSystemPagesTest {
    @Test fun fullOriginalAppliesNoncontiguousSelectionOnce() {
        val s = SystemPageSelection.resolve(11, 11, listOf(Range(1, 2), Range(7, 7)))
        assertEquals(listOf(1, 2, 7), s.pdfIndices)
        assertEquals("SPOOL_RANGES", s.mode)
    }
    @Test fun androidAlreadyReducedAndRenumberedPdfIsNotSelectedAgain() {
        assertEquals(listOf(0,1),SystemPageSelection.resolve(2,2,listOf(Range(0,Int.MAX_VALUE))).pdfIndices)
        assertEquals(listOf(0,1),SystemPageSelection.resolve(2,2,listOf(Range(0,1))).pdfIndices)
    }
    @Test fun reducedPdfUsesSpoolIndicesEvenWhenOriginalIndicesFitInsideIt() {
        val s = SystemPageSelection.resolve(3, 11, listOf(Range(1, 3)))
        assertEquals(listOf(0, 1, 2), s.pdfIndices)
        assertEquals("REDUCED_PDF", s.mode)
    }
    @Test fun reducedPdfWithUpdatedCountAndOutOfBoundsOriginalRangeIsAccepted() {
        assertEquals(listOf(0, 1), SystemPageSelection.resolve(2, 2, listOf(Range(7, 8))).pdfIndices)
    }
    @Test fun unknownCountCanOnlyUseProvableReducedSelectionOrAll() {
        assertEquals(listOf(0, 1), SystemPageSelection.resolve(2, -1, listOf(Range(7, 8))).pdfIndices)
        assertEquals(listOf(0, 1), SystemPageSelection.resolve(2, -1, null).pdfIndices)
        rejects { SystemPageSelection.resolve(11, -1, listOf(Range(1, 2))) }
    }
    @Test fun allPagesSentinelDoesNotExpandMaxInt() {
        assertEquals(1000, SystemPageSelection.resolve(1000, 1000,
            listOf(Range(0, Int.MAX_VALUE))).pdfIndices.size)
    }
    @Test fun overlappingUnorderedAndroidRangesAreNormalized() {
        assertEquals(listOf(1, 2, 3, 5), SystemPageSelection.resolve(8, 8,
            listOf(Range(5, 5), Range(1, 3), Range(2, 2))).pdfIndices)
    }
    @Test fun malformedAndAmbiguousRangesFailInsteadOfGuessing() {
        listOf(emptyList(), listOf(Range(-1, 2)), listOf(Range(3, 2)),
            listOf(Range(0, Int.MAX_VALUE), Range(1, 1)), listOf(Range(1, 1000))).forEach {
            rejects { SystemPageSelection.resolve(11, 11, it) }
        }
        rejects { SystemPageSelection.resolve(4, 11, listOf(Range(1, 2))) }
        rejects { SystemPageSelection.resolve(4, 11, null) }
    }
    @Test fun copiesAreAppliedOnceAfterSpoolMappingAndPlanIsImmutable() {
        val ranges = mutableListOf(Range(7, 8))
        val request = request(ranges = ranges, copies = 2)
        ranges.clear()
        val plan = request.plan(2)
        assertEquals(listOf(0, 1, 0, 1), plan.outputPageOrder)
        assertEquals(DocumentLayout.ANDROID_PDF, plan.layout)
        assertEquals(0, plan.contentRotationDegrees)
    }
    @Test fun arbitraryThousandPageSelectionDoesNotUseUiTextLimit() {
        val ranges = (0 until 1000 step 2).map { Range(it, it) }
        assertEquals(500, request(ranges, declared = 1000).plan(1000).pageIndices.size)
    }
    @Test fun excessiveCopiesAndSheetsAreRejectedWithoutTruncation() {
        rejects { request(copies = 21) }
        rejects { request(copies = 3, declared = 1000).plan(1000) }
    }
    @Test fun unsupportedMediaDpiColorAndDuplexAreRejected() {
        request(width = 8270, height = 11690)
        rejects { request(width = 8500) }
        rejects { request(dpi = 300) }
        rejects { request(color = 2) }
        rejects { request(duplex = 2) }
        assertEquals(Orientation.LANDSCAPE, request(width = 11693, height = 8268).orientation)
    }
    private fun request(ranges: List<Range>? = null, copies: Int = 1, declared: Int = 11,
                        width: Int = 8268, height: Int = 11693, dpi: Int = 600, color: Int = 1, duplex: Int = 1) =
        SystemPrintRequest(declared, ranges, copies, width, height, dpi, dpi, color, duplex,
            listOf(433,433,433,433)).apply { validate() }
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid request accepted") } catch (_: IllegalArgumentException) { }
    }
}
