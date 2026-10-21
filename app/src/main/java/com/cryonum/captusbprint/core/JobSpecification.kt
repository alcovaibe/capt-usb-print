package com.cryonum.captusbprint.core

/**
 * Print intent only, not a claim of verified printer capabilities.
 * A4, printable margins and physical dpi must be verified before the
 * CAPT engine turns this into real device traffic.
 */
enum class Orientation { PORTRAIT, LANDSCAPE }
enum class Paper { A4 }

data class JobSpecification private constructor(
    val pageIndices: List<Int>,
    val copies: Int,
    val orientation: Orientation,
    val paper: Paper
) {
    val outputPageOrder: List<Int>
        get() = List(copies) { pageIndices }.flatten() // collated copies

    companion object {
        const val MAX_SOURCE_PAGES = 1000
        const val MAX_OUTPUT_PAGES = 2000
        const val MAX_COPIES = 20

        fun create(
            sourcePageCount: Int,
            pages: String,
            copies: Int,
            orientation: Orientation = Orientation.PORTRAIT,
            paper: Paper = Paper.A4
        ): JobSpecification {
            require(sourcePageCount in 1..MAX_SOURCE_PAGES) { "Invalid document page count" }
            require(copies in 1..MAX_COPIES) { "Invalid number of copies" }
            require(pages.length <= 256) { "Page selection too long" }
            val selected = if (pages.isBlank()) {
                (0 until sourcePageCount).toList()
            } else {
                val seen = mutableSetOf<Int>()
                val result = ArrayList<Int>()
                pages.split(',').forEach { token ->
                    val trimmed = token.trim().replace('–', '-').replace('—', '-')
                    require(trimmed.isNotEmpty())
                    val range = Regex("([0-9]+)(?:\\s*-\\s*([0-9]+))?").matchEntire(trimmed)
                        ?: throw IllegalArgumentException("Invalid page range")
                    val first = range.groupValues[1].toIntOrNull()
                        ?: throw IllegalArgumentException("Page number overflow")
                    val last = if (range.groupValues[2].isEmpty()) first else
                        range.groupValues[2].toIntOrNull()
                            ?: throw IllegalArgumentException("Page number overflow")
                    require(first in 1..sourcePageCount && last in first..sourcePageCount) {
                        "Page range outside document"
                    }
                    for (page in first..last) {
                        require(seen.add(page)) { "Duplicate page" }
                        result.add(page - 1)
                    }
                }
                result
            }
            require(selected.isNotEmpty() && selected.size.toLong() * copies <= MAX_OUTPUT_PAGES) {
                "Too many output pages"
            }
            return JobSpecification(selected.toList(), copies, orientation, paper)
        }
    }
}
