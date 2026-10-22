package com.cryonum.captusbprint.queue

internal enum class QueuePhase {
    QUEUED, PREPARING, WAITING_PRINTER, TRANSMITTING, WAITING_CONFIRMATION,
    COUNTER_CONFIRMED, CANCELLED, ERROR, OUTCOME_UNKNOWN;
    val terminal get() = this in setOf(COUNTER_CONFIRMED, CANCELLED, ERROR, OUTCOME_UNKNOWN)
}

internal data class QueueRecord(val id: Long, val format: String, val sheets: Int,
    val phase: QueuePhase = QueuePhase.QUEUED, val confirmed: Int = 0,
    val usbStarted: Boolean = false, val cancelRequested: Boolean = false,
    val failure: String? = null, val ready: Boolean = true)

/** FIFO intent, not automatic replay. Uncertain/failed USB jobs pause the queue. */
internal class DocumentQueue {
    private val records = ArrayList<QueueRecord>()
    var paused = false; private set
    private var active: Long? = null
    @Synchronized fun snapshot() = records.toList()
    @Synchronized fun add(record: QueueRecord) {
        require(record.id > 0 && record.sheets in 1..2000)
        check(records.none { it.id == record.id })
        check(records.count { !it.phase.terminal } < 4) { "Queue full" }
        records.add(record)
        while (records.size > 50) records.removeAt(records.indexOfFirst { it.phase.terminal }.also { check(it >= 0) })
    }
    @Synchronized fun activate(id: Long) {
        val index = records.indexOfFirst { it.id == id }
        check(index >= 0 && records[index].phase == QueuePhase.QUEUED && !records[index].ready)
        records[index] = records[index].copy(ready = true)
    }
    @Synchronized fun next(): QueueRecord? {
        if (paused || active != null) return null
        val record = records.firstOrNull { it.phase == QueuePhase.QUEUED && it.ready } ?: return null
        active = record.id
        return update(record.id, QueuePhase.PREPARING)
    }
    @Synchronized fun update(id: Long, phase: QueuePhase, confirmed: Int? = null,
                             usbStarted: Boolean? = null, failure: String? = null): QueueRecord {
        val at = records.indexOfFirst { it.id == id }
        check(at >= 0)
        val old = records[at]
        check(!old.phase.terminal)
        val allowed = when (old.phase) {
            QueuePhase.QUEUED -> setOf(QueuePhase.PREPARING, QueuePhase.CANCELLED, QueuePhase.ERROR)
            QueuePhase.PREPARING -> setOf(QueuePhase.WAITING_PRINTER, QueuePhase.CANCELLED, QueuePhase.ERROR)
            QueuePhase.WAITING_PRINTER -> setOf(QueuePhase.TRANSMITTING, QueuePhase.CANCELLED, QueuePhase.ERROR)
            QueuePhase.TRANSMITTING -> setOf(QueuePhase.WAITING_CONFIRMATION, QueuePhase.CANCELLED, QueuePhase.ERROR, QueuePhase.OUTCOME_UNKNOWN)
            QueuePhase.WAITING_CONFIRMATION -> setOf(QueuePhase.TRANSMITTING, QueuePhase.WAITING_CONFIRMATION,
                QueuePhase.COUNTER_CONFIRMED, QueuePhase.ERROR, QueuePhase.OUTCOME_UNKNOWN)
            else -> emptySet()
        }
        check(phase in allowed) { "Invalid queue transition" }
        if (phase == QueuePhase.COUNTER_CONFIRMED) check((confirmed ?: old.confirmed) == old.sheets)
        require(confirmed == null || confirmed in old.confirmed..old.sheets)
        val next = old.copy(phase = phase, confirmed = confirmed ?: old.confirmed,
            usbStarted = old.usbStarted || usbStarted == true, failure = failure)
        records[at] = next
        if (phase.terminal) {
            if (active == id) active = null
            if (next.usbStarted && phase != QueuePhase.COUNTER_CONFIRMED) paused = true
        }
        return next
    }
    @Synchronized fun cancel(id: Long) {
        val at = records.indexOfFirst { it.id == id }
        check(at >= 0)
        val old = records[at]
        if (old.phase.terminal) return
        if (old.phase == QueuePhase.QUEUED) update(id, QueuePhase.CANCELLED)
        else records[at] = old.copy(cancelRequested = true)
    }
    @Synchronized fun resumeAfterInspection() { check(active == null); paused = false }
    @Synchronized fun restore(old: List<QueueRecord>, wasPaused: Boolean = false) {
        check(records.isEmpty() && active == null)
        val bounded = old.takeLast(50)
        require(bounded.map { it.id }.distinct().size == bounded.size)
        bounded.forEach { require(it.id in 1 until Long.MAX_VALUE && it.sheets in 1..2000 && it.confirmed in 0..it.sheets) }
        bounded.forEach { require(it.phase != QueuePhase.COUNTER_CONFIRMED || it.confirmed == it.sheets) }
        bounded.forEach { record ->
            records.add(if (record.phase.terminal) record else record.copy(
                phase = if (record.usbStarted) QueuePhase.OUTCOME_UNKNOWN else QueuePhase.ERROR,
                failure = "PROCESS_INTERRUPTED"))
        }
        paused = wasPaused || records.any { it.phase == QueuePhase.OUTCOME_UNKNOWN }
    }
}
