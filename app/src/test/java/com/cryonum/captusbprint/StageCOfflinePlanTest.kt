package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.core.Paper
import org.junit.Assert.*
import org.junit.Test

class StageCOfflinePlanTest {
    @Test fun selectedPagesAndCollatedCopiesHaveExactOutputOrder() {
        val plan = JobSpecification.create(9, "1–3, 5, 8", 2)
        assertEquals(listOf(0, 1, 2, 4, 7), plan.pageIndices)
        assertEquals(listOf(0, 1, 2, 4, 7, 0, 1, 2, 4, 7), plan.outputPageOrder)
        assertEquals(2, plan.copies)
        assertEquals(Paper.A4, plan.paper)
    }

    @Test fun blankRangeSelectsEverySourcePageAndLandscapeIsPlanOnly() {
        val plan = JobSpecification.create(3, "  ", 1, Orientation.LANDSCAPE)
        assertEquals(listOf(0, 1, 2), plan.pageIndices)
        assertEquals(listOf(0, 1, 2), plan.outputPageOrder)
        assertEquals(Orientation.LANDSCAPE, plan.orientation)
    }

    @Test fun repeatedPagesAndOutOfDocumentRangesAreRejected() {
        for (value in listOf("0", "4", "1-4", "3-2", "1,1", "1-2, 2",
            "1,,2", "-1", "1000000000000000000000")) {
            assertThrows("Invalid: $value", IllegalArgumentException::class.java) {
                JobSpecification.create(3, value, 1)
            }
        }
    }

    @Test fun sourceAndOutputBoundsPreventUnboundedPrintPlan() {
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(0, "", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1001, "", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1000, "", 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(4, "", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(4, "", 21)
        }
    }

    @Test fun pageSelectionUsesUserOrderForLaterPhysicalVerification() {
        val spec = JobSpecification.create(7, "7, 2-3, 1", 1)
        assertEquals(listOf(6, 1, 2, 0), spec.outputPageOrder)
    }
}
