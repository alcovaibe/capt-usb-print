package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.DocumentLayout
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.PreparedPdfLayout
import org.junit.Assert.*
import org.junit.Test

class PreparedPdfLayoutTest {
    @Test fun mixedA4PagesRetainIndividualOrientationsAndCollatedCopies() {
        val layout = PreparedPdfLayout.fromPageSizes(listOf(595 to 842, 842 to 595, 596 to 841))
        val plan = JobSpecification.preparedPdf(layout.orientations, "2-3", 2)
        assertEquals(DocumentLayout.PREPARED_PDF, plan.layout)
        assertEquals(listOf(1, 2, 1, 2), plan.outputPageOrder)
        assertEquals(Orientation.LANDSCAPE, plan.orientationForPage(1))
        assertEquals(Orientation.PORTRAIT, plan.orientationForPage(2))
        assertEquals(0, plan.contentRotationDegrees)
    }

    @Test fun selectionAndOrientationSnapshotDoNotRetainMutableInputs() {
        val input = mutableListOf(Orientation.PORTRAIT, Orientation.LANDSCAPE)
        val plan = JobSpecification.preparedPdf(input, "2,1", 1)
        input[1] = Orientation.PORTRAIT
        assertEquals(listOf(1, 0), plan.pageIndices)
        assertEquals(Orientation.LANDSCAPE, plan.orientationForPage(1))
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.pageOrientations as MutableMap<Int, Orientation>)[1] = Orientation.PORTRAIT
        }
    }

    @Test fun preparedMetadataSurvivesPausedWorkRestore() {
        val original = JobSpecification.preparedPdf(listOf(Orientation.LANDSCAPE, Orientation.PORTRAIT), "", 2)
        val restored = JobSpecification.restorePrepared(original.pageIndices, original.copies,
            original.orientation, 0, original.layout, original.pageOrientations)
        assertEquals(original, restored)
        assertEquals(original.outputPageOrder.map { original.orientationForPage(it) },
            restored.outputPageOrder.map { restored.orientationForPage(it) })
    }

    @Test fun missingExtraOrRotatedOrientationMetadataIsRejected() {
        for (map in listOf(emptyMap(), mapOf(0 to Orientation.PORTRAIT, 1 to Orientation.PORTRAIT))) {
            assertThrows(IllegalArgumentException::class.java) {
                JobSpecification.restorePrepared(listOf(0), 1, Orientation.PORTRAIT, 0,
                    DocumentLayout.PREPARED_PDF, map)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobSpecification.restorePrepared(listOf(0), 1, Orientation.PORTRAIT, 90,
                DocumentLayout.PREPARED_PDF, mapOf(0 to Orientation.PORTRAIT))
        }
    }

    @Test fun nonA4PageAnywhereInTheFileIsRejectedWithoutClampingOrFit() {
        for (bad in listOf(612 to 792, 420 to 595, 0 to 842, Int.MIN_VALUE to 842)) {
            assertThrows(IllegalArgumentException::class.java) {
                PreparedPdfLayout.fromPageSizes(listOf(595 to 842, bad))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { PreparedPdfLayout.fromPageSizes(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PreparedPdfLayout.fromPageSizes(List(1001) { 595 to 842 }) }
    }

    @Test fun ordinaryAndSystemPlansRetainTheirExistingOrientationAndLayoutRules() {
        val direct = JobSpecification.create(2, "", 1, Orientation.LANDSCAPE, contentRotationDegrees = 90)
        assertEquals(DocumentLayout.APP_FIT, direct.layout)
        assertEquals(90, direct.contentRotationDegrees)
        assertEquals(Orientation.LANDSCAPE, direct.orientationForPage(1))
        val system = JobSpecification.systemPdf(2, listOf(1), 1, Orientation.PORTRAIT)
        assertEquals(DocumentLayout.ANDROID_PDF, system.layout)
        assertEquals(Orientation.PORTRAIT, system.orientationForPage(1))
        assertTrue(system.pageOrientations.isEmpty())
    }
}
