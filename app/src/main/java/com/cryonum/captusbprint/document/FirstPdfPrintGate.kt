package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.core.Paper

/**
 * Deliberately narrow first-hardware-PDF checkpoint. The one physical sheet
 * must match the visible page AND the user-entered plan. Never silently
 * ignore selected page ranges, copies or unsupported orientations.
 */
object FirstPdfPrintGate {
    fun rejection(format: DocumentFormat, job: JobSpecification, visiblePageIndex: Int): String? {
        if (format != DocumentFormat.PDF) return "Экспериментальная печать пока поддерживает только PDF."
        if (job.paper != Paper.A4 || job.orientation != Orientation.PORTRAIT) {
            return "Для первого PDF-отпечатка поддерживается только книжный лист A4."
        }
        if (job.copies != 1 || job.pageIndices != listOf(visiblePageIndex)) {
            return "Для теста выбери диапазон только текущей страницы и одну копию."
        }
        return null
    }
}
