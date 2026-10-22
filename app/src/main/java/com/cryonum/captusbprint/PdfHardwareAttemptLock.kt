package com.cryonum.captusbprint

import java.util.concurrent.atomic.AtomicBoolean

/** Both one-sheet and batch PDF UI entrypoints share one in-process lease.
 * CAPT USB ownership separately arbitrates other diagnostic/Stage B clients.
 */
internal object PdfHardwareAttemptLock {
    private val occupied = AtomicBoolean(false)

    fun tryAcquire(): AutoCloseable? {
        if (!occupied.compareAndSet(false, true)) return null
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) occupied.set(false)
        }
    }
}
