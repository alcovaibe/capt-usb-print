package com.cryonum.captusbprint.usb

/**
 * In-memory, bounded diagnostic history of device *counts* only.
 * Deliberately excludes USB paths, serial numbers and printer payload.
 */
object UsbObservation {
    private const val MAX_CHANGES = 16
    private val changes = ArrayDeque<Int>()
    @Synchronized fun record(count: Int) {
        require(count >= 0)
        if (changes.lastOrNull() == count) return
        changes.addLast(count)
        while (changes.size > MAX_CHANGES) changes.removeFirst()
    }
    @Synchronized fun reportLine(): String =
        "Observed USB device count changes while app visible: " +
            if (changes.isEmpty()) "none" else changes.joinToString(" -> ")
    @Synchronized fun resetForTest() = changes.clear()
}
