package com.cryonum.captusbprint

import com.cryonum.captusbprint.office.OfficeKind
import com.cryonum.captusbprint.office.OfficeOptions
import org.junit.Assert.*
import org.junit.Test

class OfficeOptionsTest {
    @Test fun selectionIsFrozenInWorkbookOrder() {
        val selected = mutableListOf(2, 0)
        val options = OfficeOptions(OfficeKind.XLS, selected)
        selected.clear()
        assertEquals(listOf(0, 2), options.selectedSheets)
        assertThrows(UnsupportedOperationException::class.java) {
            (options.selectedSheets as MutableList<Int>).clear()
        }
    }
    @Test fun selectionRejectsEmptyDuplicatesAndInvalidIndices() {
        for (kind in listOf(OfficeKind.XLS, OfficeKind.XLSX)) {
            for (indices in listOf(emptyList(), listOf(-1), listOf(256), listOf(0, 0)))
                assertThrows(IllegalArgumentException::class.java) { OfficeOptions(kind, indices) }
        }
    }
    @Test fun wordCannotSelectWorkbookSheets() {
        for (kind in listOf(OfficeKind.DOC, OfficeKind.DOCX)) {
            assertNull(OfficeOptions(kind).selectedSheets)
            assertThrows(IllegalArgumentException::class.java) { OfficeOptions(kind, listOf(0)) }
        }
    }
}
