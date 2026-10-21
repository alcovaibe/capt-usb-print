package com.cryonum.captusbprint.engine

/** CAPT protocol integration must explicitly confirm physical completion. */
enum class PrintStage {
    CREATED, PREPARING, WAITING_FOR_PRINTER, WAITING_FOR_PERMISSION,
    TRANSFERRING, AWAITING_RESULT, COMPLETED, CANCELLED, FAILED,
    UNKNOWN_AFTER_INTERRUPTION
}
data class JobState(val stage: PrintStage = PrintStage.CREATED) {
    fun advance(to: PrintStage): JobState {
        val allowed = when (stage) {
            PrintStage.CREATED -> setOf(PrintStage.PREPARING, PrintStage.CANCELLED)
            PrintStage.PREPARING -> setOf(PrintStage.WAITING_FOR_PRINTER,
                PrintStage.WAITING_FOR_PERMISSION, PrintStage.TRANSFERRING,
                PrintStage.CANCELLED, PrintStage.FAILED)
            PrintStage.WAITING_FOR_PRINTER, PrintStage.WAITING_FOR_PERMISSION ->
                setOf(PrintStage.PREPARING, PrintStage.CANCELLED, PrintStage.FAILED)
            PrintStage.TRANSFERRING -> setOf(PrintStage.AWAITING_RESULT,
                PrintStage.UNKNOWN_AFTER_INTERRUPTION, PrintStage.CANCELLED, PrintStage.FAILED)
            PrintStage.AWAITING_RESULT -> setOf(PrintStage.COMPLETED,
                PrintStage.UNKNOWN_AFTER_INTERRUPTION, PrintStage.CANCELLED, PrintStage.FAILED)
            PrintStage.COMPLETED, PrintStage.CANCELLED, PrintStage.FAILED,
            PrintStage.UNKNOWN_AFTER_INTERRUPTION -> emptySet()
        }
        require(to in allowed) { "Illegal transition: $stage to $to" }
        return copy(stage = to)
    }
    fun interrupted(): JobState = when (stage) {
        PrintStage.TRANSFERRING, PrintStage.AWAITING_RESULT ->
            advance(PrintStage.UNKNOWN_AFTER_INTERRUPTION)
        PrintStage.PREPARING, PrintStage.WAITING_FOR_PRINTER,
        PrintStage.WAITING_FOR_PERMISSION -> JobState(PrintStage.FAILED)
        else -> this
    }
    // Never auto-resume or resend from persistent history.
    val mayAutoRestart: Boolean get() = false
}

/** 1-based source page selection: no duplicate physical output from overlapping ranges. */
data class PagePlan(val pages: List<Int>, val copies: Int) {
    companion object {
        fun parse(pageCount: Int, range: String, copies: Int): PagePlan {
            require(pageCount in 1..1000) { "Unsupported page count" }
            require(copies in 1..20) { "Invalid copy count" }
            if (range.isBlank()) return PagePlan((1..pageCount).toList(), copies)
            require(range.length <= 300) { "Page range is too long" }
            val selected = LinkedHashSet<Int>()
            range.split(',').forEach { part ->
                val s = part.trim()
                require(s.matches(Regex("[0-9]+(-[0-9]+)?"))) { "Invalid page range" }
                val ends = s.split('-')
                val start = ends[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid start page")
                val end = ends.last().toIntOrNull() ?: throw IllegalArgumentException("Invalid end page")
                require(start in 1..pageCount && end in start..pageCount) { "Page outside document" }
                for (page in start..end) {
                    require(selected.add(page)) { "Overlapping page range" }
                }
            }
            require(selected.isNotEmpty())
            return PagePlan(selected.toList(), copies)
        }
    }
    fun orderedSheets(): Sequence<Int> = sequence {
        repeat(copies) { for (page in pages) yield(page) }
    }
}
