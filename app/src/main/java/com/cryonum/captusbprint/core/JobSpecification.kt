package com.cryonum.captusbprint.core

/**
 * Print intent only, not a claim of verified printer capabilities.
 * A4, printable margins and physical dpi must be verified before the
 * CAPT engine turns this into real device traffic.
 */
enum class Orientation { PORTRAIT, LANDSCAPE }
enum class Paper { A4 }
enum class DocumentLayout { APP_FIT, ANDROID_PDF }

@ConsistentCopyVisibility
data class JobSpecification private constructor(
    val pageIndices: List<Int>,
    val copies: Int,
    val orientation: Orientation,
    val contentRotationDegrees: Int,
    val paper: Paper,
    val layout: DocumentLayout = DocumentLayout.APP_FIT
) {
    val outputPageOrder: List<Int>
        get() = List(copies) { pageIndices }.flatten() // collated copies

    companion object {
        const val MAX_SOURCE_PAGES = 1000
        const val MAX_OUTPUT_PAGES = 2000
        const val MAX_COPIES = 20

        /** Private prepared-spool metadata only; pages are revalidated before any USB operation. */
        internal fun restorePrepared(indices: List<Int>, copies: Int, orientation: Orientation,
                                     rotation: Int, layout: DocumentLayout): JobSpecification {
            val validated = systemPdf(MAX_SOURCE_PAGES, indices, copies, orientation)
            require(rotation == 0 || rotation == 90)
            return JobSpecification(validated.pageIndices, copies, orientation, rotation, Paper.A4, layout)
        }

        /** System PDF already includes the originating application's layout and page selection. */
        fun systemPdf(sourcePageCount: Int, indices: List<Int>, copies: Int,
                      orientation: Orientation): JobSpecification {
            require(sourcePageCount in 1..MAX_SOURCE_PAGES)
            require(copies in 1..MAX_COPIES)
            require(indices.isNotEmpty() && indices.size.toLong() * copies <= MAX_OUTPUT_PAGES)
            require(indices.all { it in 0 until sourcePageCount } && indices.distinct().size == indices.size)
            return JobSpecification(java.util.Collections.unmodifiableList(indices.toList()), copies,
                orientation, 0, Paper.A4, DocumentLayout.ANDROID_PDF)
        }

        fun create(
            sourcePageCount: Int,
            pages: String,
            copies: Int,
            orientation: Orientation = Orientation.PORTRAIT,
            paper: Paper = Paper.A4,
            contentRotationDegrees: Int = 0
        ): JobSpecification {
            require(sourcePageCount in 1..MAX_SOURCE_PAGES) { "Invalid document page count" }
            require(copies in 1..MAX_COPIES)
            require(contentRotationDegrees == 0 || contentRotationDegrees == 90) { "Invalid number of copies" }
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
            return JobSpecification(java.util.Collections.unmodifiableList(selected.toList()), copies, orientation, contentRotationDegrees, paper)
        }
    }
}

