package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.*
import com.cryonum.captusbprint.document.*
import org.junit.Assert.*
import org.junit.Test

class StageCPrintGeometryTest {
    private fun map(m: FloatArray, x: Double, y: Double) = Pair(m[0]*x+m[1]*y+m[2], m[3]*x+m[4]*y+m[5])
    @Test fun physicalLandscapeRotationMatchesTheLogicalPreviewCornersAndBandOffsets() {
        for (orientation in Orientation.entries) for (rotation in listOf(0,90)) {
            val logical = A4PageGeometry.fit(200, 100, orientation, rotation).content
            val m = PrintRasterGeometry.matrix(200,100,orientation,rotation,0)
            val corners = listOf(map(m,0.0,0.0),map(m,200.0,0.0),map(m,0.0,100.0),map(m,200.0,100.0))
            val left = if (orientation == Orientation.PORTRAIT) logical.left else 4960 - logical.bottom
            val top = if (orientation == Orientation.PORTRAIT) logical.top else logical.left
            assertEquals(left, corners.minOf { it.first }, 0.002)
            assertEquals(top, corners.minOf { it.second }, 0.002)
            val strip = PrintRasterGeometry.matrix(200,100,orientation,rotation,128)
            assertEquals(map(m,25.0,70.0).second-128, map(strip,25.0,70.0).second, 0.002)
            corners.forEach { assertTrue(it.first in 0.0..4960.0 && it.second in 0.0..7016.0) }
        }
    }
    @Test fun imageDecodingBudgetIsBoundedForLargePhotosAndPanoramas() {
        for ((w,h) in listOf(50000 to 1, 50000 to 50000, 12000 to 9000, 1 to 50000, 200 to 100)) {
            val sample = PrintRasterGeometry.sampleSize(w,h)
            assertTrue(((w.toLong()+sample-1)/sample)*((h.toLong()+sample-1)/sample) <= 4_000_000L)
        }
    }
    @Test fun generalCountersSupportTenPagesAndRejectOldOrOverflowedCounters() {
        fun s(n: Int) = CaptExtendedStatus(1,0,15,0,0,0,128,0,n,n,n,n,n)
        val baseline = CaptPageCounterProgress.snapshot(s(0))
        assertTrue(DocumentPageProgress.completed(baseline,s(10),10))
        assertFalse(DocumentPageProgress.completed(baseline,s(9),10))
        assertThrows(IllegalArgumentException::class.java) { DocumentPageProgress.completed(baseline,s(2001),2001) }
    }
    @Test fun jobPageSelectionCannotBeMutatedAfterConfirmation() {
        val job = JobSpecification.create(11,"1-3",2)
        assertThrows(UnsupportedOperationException::class.java) { (job.pageIndices as MutableList<Int>)[0] = 7 }
        assertEquals(listOf(0,1,2,0,1,2), job.outputPageOrder)
    }
}
