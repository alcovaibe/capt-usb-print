package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.core.Paper
import org.junit.Assert.*
import org.junit.Test

class StageCJobPlanTest {
    @Test fun collatedCopiesPreserveSelectedPageOrderAndOrientation() {
        val job = JobSpecification.create(8, "1–3, 5, 8", 2, Orientation.LANDSCAPE)
        assertEquals(listOf(0, 1, 2, 4, 7), job.pageIndices)
        assertEquals(listOf(0, 1, 2, 4, 7, 0, 1, 2, 4, 7), job.outputPageOrder)
        assertEquals(Orientation.LANDSCAPE, job.orientation)
        assertEquals(Paper.A4, job.paper)
        assertEquals(10, job.outputPageOrder.size)
    }

    @Test fun onePageImageCanRequestCopiesWithoutDuplicatingSourcePage() {
        val job = JobSpecification.create(1, "", 3)
        assertEquals(listOf(0), job.pageIndices)
        assertEquals(listOf(0, 0, 0), job.outputPageOrder)
        assertEquals(Orientation.PORTRAIT, job.orientation)
    }

    @Test fun userSpecifiedSequenceAndSpacesAreRespected() {
        assertEquals(listOf(4, 0, 1), JobSpecification.create(5, "5, 1 - 2", 1).pageIndices)
        assertEquals(listOf(0, 1, 2), JobSpecification.create(3, "   ", 1).pageIndices)
    }

    @Test fun invalidPageRangesAndCopiesFailClosed() {
        for (selection in listOf("0", "9", "2-1", "1-3,3", "1,,2", "1-", "abc",
                "99999999999999", "1-2,2-3")) {
            assertThrows("Invalid: $selection", IllegalArgumentException::class.java) {
                JobSpecification.create(5, selection, 1)
            }
        }
        for (count in listOf(0, -1, 21)) {
            assertThrows(IllegalArgumentException::class.java) {
                JobSpecification.create(5, "", count)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1000, "", 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1001, "", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1, "1".repeat(257), 1)
        }
    }

    @Test fun maximumOutputIsBoundedAndNoPrintOperationExistsInPlan() {
        val job = JobSpecification.create(1000, "", 2)
        assertEquals(2000, job.outputPageOrder.size)
        assertEquals(0, job.outputPageOrder.first())
        assertEquals(999, job.outputPageOrder[999])
        assertEquals(0, job.outputPageOrder[1000])
        assertEquals(999, job.outputPageOrder.last())
    }
}
