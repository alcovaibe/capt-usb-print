package com.cryonum.captusbprint.usb

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local, fail-fast owner of the Canon USB interface. All direct CAPT
 * sessions, read-only probes and claim diagnostics must take this lease.
 * Never queue behind a possibly uncertain print or silently replay a job.
 *
 * This protects in-process entry points only. It is NOT an OS-wide USB lock;
 * the Android non-forced claim and exact profile validation remain required.
 */
internal object CaptUsbOwnership {
    private var occupied = false

    @Synchronized fun isOccupied() = occupied

    @Synchronized
    fun acquire(): Lease {
        check(!occupied) { "Canon USB interface is in use by another operation" }
        occupied = true
        return Lease()
    }

    fun <T> withExclusive(action: () -> T): T = acquire().use { action() }

    class Lease internal constructor() : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                synchronized(CaptUsbOwnership) {
                    check(occupied) { "USB ownership was already released" }
                    occupied = false
                }
            }
        }
    }
}
