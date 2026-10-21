package com.cryonum.captusbprint.usb

import java.util.Locale

/**
 * Only the standard 18-byte USB DEVICE descriptor may enter an A1 report.
 * Never include any following string descriptor or whole raw USB data stream.
 */
internal object UsbReportPolicy {
    fun deviceHeader(raw: ByteArray?): String {
        if (raw == null || raw.size < 18 ||
            (raw[0].toInt() and 0xff) != 18 ||
            (raw[1].toInt() and 0xff) != 1
        ) return "unavailable (invalid device descriptor)"
        return raw.take(18).joinToString(" ") {
            String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
        }
    }
}
