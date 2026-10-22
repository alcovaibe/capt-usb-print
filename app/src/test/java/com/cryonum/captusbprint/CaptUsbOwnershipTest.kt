package com.cryonum.captusbprint

import com.cryonum.captusbprint.usb.CaptUsbOwnership
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CaptUsbOwnershipTest {
    @Test fun refusesSecondOwnerUntilFirstClosesAndDoubleCloseIsHarmless() {
        val first = CaptUsbOwnership.acquire()
        try {
            assertThrows(IllegalStateException::class.java) { CaptUsbOwnership.acquire() }
        } finally {
            first.close()
            first.close()
        }
        CaptUsbOwnership.acquire().use { assertNotNull(it) }
    }

    @Test fun releasesAfterOperationThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptUsbOwnership.withExclusive<Unit> { throw IllegalArgumentException("fail") }
        }
        CaptUsbOwnership.withExclusive { assertTrue(true) }
    }

    @Test fun racingSecondThreadFailsWithoutWaitingOrReplaying() {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val owner = Thread {
            try {
                CaptUsbOwnership.withExclusive {
                    held.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            } catch (e: Throwable) { failure.set(e) }
        }
        owner.start()
        try {
            assertTrue(held.await(3, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { CaptUsbOwnership.acquire() }
        } finally {
            release.countDown()
            owner.join(3000)
        }
        assertFalse(owner.isAlive)
        assertNull(failure.get())
        CaptUsbOwnership.acquire().close()
    }
}
