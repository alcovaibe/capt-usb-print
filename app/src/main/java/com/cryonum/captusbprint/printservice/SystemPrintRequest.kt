package com.cryonum.captusbprint.printservice

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation

/** Immutable main-thread snapshot; excludes label, URI, app identity and document content. */
internal class SystemPrintRequest(
    val declaredPages: Int, ranges: List<SystemPageSelection.Range>?, val copies: Int,
    val widthMils: Int, val heightMils: Int, val horizontalDpi: Int, val verticalDpi: Int,
    val colorMode: Int, val duplexMode: Int, margins: List<Int>
) {
    val ranges = ranges?.toList()
    private val margins = margins.toList()
    val orientation = if (widthMils > heightMils) Orientation.LANDSCAPE else Orientation.PORTRAIT
    init { require(this.ranges == null || this.ranges.size <= 1000); require(this.margins.size == 4) }
    fun validate() {
        require(declaredPages == -1 || declaredPages in 1..JobSpecification.MAX_SOURCE_PAGES) { "UNSUPPORTED_PAGE_COUNT" }
        require(copies in 1..JobSpecification.MAX_COPIES) { "UNSUPPORTED_COPIES" }
        // Android ISO_A4 uses 8270x11690; exact mm-to-mil conversions round to 8268x11693.
        require((minOf(widthMils,heightMils) to maxOf(widthMils,heightMils)) in
            setOf(8270 to 11690, 8268 to 11693)) { "UNSUPPORTED_PAPER" }
        require(horizontalDpi == 600 && verticalDpi == 600) { "UNSUPPORTED_RESOLUTION" }
        require(colorMode == 1 && duplexMode in 0..1) { "UNSUPPORTED_PRINT_MODE" }
        require(this.margins.size == 4 && this.margins.all { it >= 0 })
    }
    fun selection(pdfPages: Int) = SystemPageSelection.resolve(pdfPages, declaredPages, ranges)
    fun plan(pdfPages: Int): JobSpecification {
        validate()
        return JobSpecification.systemPdf(pdfPages, selection(pdfPages).pdfIndices, copies, orientation)
    }
    fun diagnostic() = mapOf("declaredPages" to declaredPages,
        "requestedRanges" to ranges?.map { listOf(it.first, it.last) }, "copies" to copies,
        "mediaMils" to listOf(widthMils, heightMils), "dpi" to listOf(horizontalDpi, verticalDpi),
        "colorMode" to colorMode, "duplexMode" to duplexMode,
        "androidMarginsMils" to margins, "layout" to "ANDROID_PDF", "extraFitOrMargins" to false)
}
