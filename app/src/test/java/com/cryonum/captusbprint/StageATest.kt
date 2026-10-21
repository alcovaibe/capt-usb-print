package com.cryonum.captusbprint

import com.cryonum.captusbprint.usb.UsbReportPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StageATest {
    @Test fun reportIncludesOnlyEighteenByteDeviceDescriptor() {
        val header = byteArrayOf(
            18, 1, 0, 2, 0, 0, 0, 64, 0xA9.toByte(), 4, 0x55, 0x26,
            0, 1, 1, 2, 3, 1
        )
        val forbiddenStringDescriptor = byteArrayOf(4, 3, 0x53, 0)
        val value = UsbReportPolicy.deviceHeader(header + forbiddenStringDescriptor)
        assertEquals(header.joinToString(" ") {
            "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xff)
        }, value)
        assertEquals(18, value.split(" ").size)
        assertFalse(value.contains("53 00"))
    }

    @Test fun reportRejectsShortOrWrongTypeDescriptors() {
        assertEquals("unavailable (invalid device descriptor)", UsbReportPolicy.deviceHeader(null))
        assertEquals("unavailable (invalid device descriptor)", UsbReportPolicy.deviceHeader(byteArrayOf(18, 1, 0)))
        assertEquals("unavailable (invalid device descriptor)", UsbReportPolicy.deviceHeader(ByteArray(18)))
    }
}
