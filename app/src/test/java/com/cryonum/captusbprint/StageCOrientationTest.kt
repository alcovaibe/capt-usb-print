package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.A4PageGeometry
import com.cryonum.captusbprint.document.PageOrientationChoice
import org.junit.Assert.*
import org.junit.Test

class StageCOrientationTest {
    @Test fun automaticOrientationDependsOnFirstPageNotRotationOfPhone() {
        assertEquals(Orientation.PORTRAIT, PageOrientationChoice.AUTO.resolve(595, 841))
        assertEquals(Orientation.LANDSCAPE, PageOrientationChoice.AUTO.resolve(842, 595))
        assertEquals(Orientation.PORTRAIT, PageOrientationChoice.AUTO.resolve(600, 600))
        assertEquals(Orientation.PORTRAIT, PageOrientationChoice.PORTRAIT.resolve(842, 595))
        assertEquals(Orientation.LANDSCAPE, PageOrientationChoice.LANDSCAPE.resolve(595, 842))
    }

    @Test fun contentRotationChangesFitWithoutChangingSheetOrientation() {
        val small = A4PageGeometry.fit(595, 841, Orientation.LANDSCAPE)
        val rotated = A4PageGeometry.fit(595, 841, Orientation.LANDSCAPE, 90)
        assertEquals(small.paperWidth, rotated.paperWidth)
        assertEquals(small.paperHeight, rotated.paperHeight)
        assertEquals(90, rotated.contentRotationDegrees)
        assertTrue(rotated.content.width > small.content.width)
        assertTrue(rotated.content.height <= small.content.height + 0.001)
        assertEquals(841.0 / 595.0,
            rotated.content.width / rotated.content.height, 0.00001)
        val preview = rotated.preview(1000, 1450)
        assertTrue(preview.content.right <= preview.width + 0.001)
        assertTrue(preview.content.bottom <= preview.height + 0.001)
    }

    @Test fun rotationIsRetainedInImmutableFutureJobPlan() {
        val job = JobSpecification.create(11, "1-3,5", 2,
            Orientation.LANDSCAPE, contentRotationDegrees = 90)
        assertEquals(90, job.contentRotationDegrees)
        assertEquals(8, job.outputPageOrder.size)
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.create(1, "", 1, contentRotationDegrees = 180)
        }
        assertThrows(IllegalArgumentException::class.java) {
            A4PageGeometry.fit(595, 841, Orientation.PORTRAIT, -90)
        }
    }
}
