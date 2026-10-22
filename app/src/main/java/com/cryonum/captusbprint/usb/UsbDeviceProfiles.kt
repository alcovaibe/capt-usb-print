package com.cryonum.captusbprint.usb

/**
 * Conservative USB identity table. A VID:PID match identifies a likely device
 * model; it does not prove the active interface, protocol response, or print
 * capability. Those require separate descriptor and hardware checks.
 */
data class UsbDeviceProfile(
    val vendorId: Int,
    val productId: Int,
    val modelName: String,
    val protocolFamily: String
)

object UsbDeviceProfiles {
    private val profiles = listOf(
        UsbDeviceProfile(
            vendorId = 0x04A9,
            productId = 0x2676,
            modelName = "Canon LBP2900",
            protocolFamily = "CAPT"
        )
    )

    fun identify(vendorId: Int, productId: Int): UsbDeviceProfile? =
        profiles.singleOrNull { it.vendorId == vendorId && it.productId == productId }
}
