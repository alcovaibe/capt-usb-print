package com.cryonum.captusbprint

import org.junit.Assert.*
import org.junit.Test

class PdfHardwareAttemptLockTest {
    @Test fun singleAndBatchEntryPointsCannotOverlap() {
        val first = requireNotNull(PdfHardwareAttemptLock.tryAcquire())
        try {
            assertNull(PdfHardwareAttemptLock.tryAcquire())
        } finally {
            first.close()
            first.close()
        }
        PdfHardwareAttemptLock.tryAcquire()!!.use { assertNull(PdfHardwareAttemptLock.tryAcquire()) }
        PdfHardwareAttemptLock.tryAcquire()!!.close()
    }
}
