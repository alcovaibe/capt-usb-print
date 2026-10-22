package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.core.Paper

/**
 * First real multi-sheet PDF pilot is deliberately limited to 2 or 3 sheets
 * in one job, in the exact collated order of the selected immutable plan.
 * This is not the general 2000-sheet queue and does not imply landscape.
 */
object PdfBatchGate {
    fun rejection(format: DocumentFormat, job: JobSpecification, visiblePageIndex: Int): String? {
        if (format != DocumentFormat.PDF) return "Пока доступна только печать PDF."
        if (job.paper != Paper.A4 || job.orientation != Orientation.PORTRAIT) {
            return "Многостраничный тест доступен только для книжного листа A4."
        }
        val order = job.outputPageOrder
        if (order.size !in 2..3) return "Для теста выбери ровно два или три итоговых листа."
        if (order.first() != visiblePageIndex) {
            return "Сначала открой предпросмотр первой страницы выбранного диапазона."
        }
        return null
    }
}
