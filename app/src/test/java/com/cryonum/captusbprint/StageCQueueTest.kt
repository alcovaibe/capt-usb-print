package com.cryonum.captusbprint

import com.cryonum.captusbprint.queue.*
import org.junit.Assert.*
import org.junit.Test

class StageCQueueTest {
    private fun job(id: Long) = QueueRecord(id, "PDF", 10)
    @Test fun fifoAndSingleOwnerIncludePendingCancellation() {
        val q = DocumentQueue()
        q.add(job(1)); q.add(job(2)); q.add(job(3))
        assertEquals(1L, q.next()!!.id)
        assertNull(q.next())
        q.cancel(2)
        q.update(1, QueuePhase.TRANSMITTING, usbStarted = true)
        q.update(1, QueuePhase.COUNTER_CONFIRMED, 10)
        assertEquals(3L, q.next()!!.id)
        assertEquals(QueuePhase.CANCELLED, q.snapshot()[1].phase)
    }
    @Test fun uncertainOutcomePausesSuccessorsAndResumeDoesNotReplayFailedJob() {
        val q = DocumentQueue(); q.add(job(1)); q.add(job(2)); q.next()
        q.update(1, QueuePhase.TRANSMITTING, usbStarted = true)
        q.update(1, QueuePhase.WAITING_CONFIRMATION, 2)
        q.cancel(1)
        assertTrue(q.snapshot()[0].cancelRequested)
        q.update(1, QueuePhase.OUTCOME_UNKNOWN)
        assertTrue(q.paused); assertNull(q.next())
        q.resumeAfterInspection()
        assertEquals(2L, q.next()!!.id)
        assertEquals(2, q.snapshot()[0].confirmed)
        assertThrows(IllegalStateException::class.java) { q.update(1, QueuePhase.PREPARING) }
    }
    @Test fun restartNeverResumesQueuedOrActiveDocuments() {
        val q = DocumentQueue()
        q.restore(listOf(job(1), job(2).copy(phase = QueuePhase.TRANSMITTING,
            usbStarted = true, confirmed = 3), job(3).copy(phase = QueuePhase.COUNTER_CONFIRMED, confirmed = 10)))
        assertEquals(listOf(QueuePhase.ERROR, QueuePhase.OUTCOME_UNKNOWN, QueuePhase.COUNTER_CONFIRMED),
            q.snapshot().map { it.phase })
        assertEquals(3, q.snapshot()[1].confirmed)
        assertTrue(q.paused); q.resumeAfterInspection(); assertNull(q.next())
    }
    @Test fun storageAndHistoryAreBoundedAndCountersCannotRegress() {
        val q = DocumentQueue(); (1L..4L).forEach { q.add(job(it)) }
        assertThrows(IllegalStateException::class.java) { q.add(job(5)) }
        q.next(); q.update(1, QueuePhase.WAITING_CONFIRMATION, 2)
        assertThrows(IllegalArgumentException::class.java) { q.update(1, QueuePhase.WAITING_CONFIRMATION, 1) }
        assertThrows(IllegalArgumentException::class.java) { q.update(1, QueuePhase.WAITING_CONFIRMATION, 11) }
        val history = DocumentQueue()
        (1L..60L).forEach { history.add(job(it)); history.next(); history.update(it, QueuePhase.COUNTER_CONFIRMED, 10) }
        assertEquals(50, history.snapshot().size)
        assertEquals(11L, history.snapshot().first().id)
    }
    @Test fun corruptRestoreIsAtomicAndCannotCreatePhantomSuccess() {
        val q = DocumentQueue()
        assertThrows(IllegalArgumentException::class.java) { q.restore(listOf(job(1), job(-1))) }
        assertTrue(q.snapshot().isEmpty())
        q.restore(listOf(job(1).copy(phase = QueuePhase.ERROR)), wasPaused = true)
        assertTrue(q.paused)
    }
}
