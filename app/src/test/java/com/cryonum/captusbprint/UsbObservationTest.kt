package com.cryonum.captusbprint

import com.cryonum.captusbprint.usb.UsbObservation
import org.junit.Assert.*
import org.junit.Test

class UsbObservationTest {
    @Test fun retainsOnlyBoundedCountsWithoutPaths() {
        UsbObservation.resetForTest()
        UsbObservation.record(0)
        UsbObservation.record(0)
        UsbObservation.record(1)
        assertEquals("Observed USB device count changes while app visible: 0 -> 1",
            UsbObservation.reportLine())
        (0..100).forEach { UsbObservation.record(it) }
        val last = UsbObservation.reportLine().substringAfter(": ").split(" -> ")
        assertEquals(16, last.size)
        assertEquals("100", last.last())
        UsbObservation.resetForTest()
    }
    @Test fun neverAcceptsNegativeCounts() {
        assertThrows(IllegalArgumentException::class.java) { UsbObservation.record(-1) }
    }
}
