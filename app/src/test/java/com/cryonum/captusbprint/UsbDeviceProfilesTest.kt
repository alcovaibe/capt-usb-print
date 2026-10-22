package com.cryonum.captusbprint

import com.cryonum.captusbprint.usb.UsbDeviceProfiles
import org.junit.Assert.*
import org.junit.Test

class UsbDeviceProfilesTest {
    @Test fun identifiesCanonLbp2900ByExactUsbId() {
        val profile = UsbDeviceProfiles.identify(0x04A9, 0x2676)
        assertNotNull(profile)
        assertEquals("Canon LBP2900", profile!!.modelName)
        assertEquals("CAPT", profile.protocolFamily)
        assertEquals(0x04A9, profile.vendorId)
        assertEquals(0x2676, profile.productId)
    }

    @Test fun unknownIdsAreNeverGuessedAsCanonLbp2900() {
        assertNull(UsbDeviceProfiles.identify(0x04A9, 0x0001))
        assertNull(UsbDeviceProfiles.identify(0x0000, 0x2676))
        assertNull(UsbDeviceProfiles.identify(0xFFFF, 0xFFFF))
    }
}
