package com.cryonum.captusbprint.core

/**
 * Conservative job lifecycle. USB writes are NOT proof of physical delivery.
 * This is an in-memory state machine; persistence and recovery remain separate.
 */
enum class JobPhase {
    PREPARING,
    WAITING_PERMISSION,
    WAITING_PRINTER,
    TRANSMITTING,
    WAITING_CONFIRMATION,
    COMPLETED,
    CANCELLED,
    ERROR,
    OUTCOME_UNKNOWN
}

class JobLedger {
    private val edges: Map<JobPhase, Set<JobPhase>> = mapOf(
        JobPhase.PREPARING to setOf(JobPhase.WAITING_PERMISSION, JobPhase.WAITING_PRINTER,
            JobPhase.CANCELLED, JobPhase.ERROR),
        JobPhase.WAITING_PERMISSION to setOf(JobPhase.WAITING_PRINTER,
            JobPhase.CANCELLED, JobPhase.ERROR),
        JobPhase.WAITING_PRINTER to setOf(JobPhase.TRANSMITTING,
            JobPhase.CANCELLED, JobPhase.ERROR),
        JobPhase.TRANSMITTING to setOf(JobPhase.WAITING_CONFIRMATION,
            JobPhase.OUTCOME_UNKNOWN, JobPhase.CANCELLED, JobPhase.ERROR),
        JobPhase.WAITING_CONFIRMATION to setOf(JobPhase.COMPLETED,
            JobPhase.OUTCOME_UNKNOWN, JobPhase.CANCELLED, JobPhase.ERROR)
    )

    private var activeJobId: Long? = null
    var phase: JobPhase? = null
        private set

    @Synchronized fun begin(jobId: Long) {
        require(jobId > 0)
        check(activeJobId == null) { "A USB job is already active" }
        activeJobId = jobId
        phase = JobPhase.PREPARING
    }

    @Synchronized fun transition(jobId: Long, next: JobPhase) {
        check(activeJobId == jobId && phase != null) { "Unknown or stale job" }
        check(next in (edges[phase] ?: emptySet())) {
            "Forbidden transition: ${phase} to ${next}"
        }
        phase = next
        // Terminal states never silently resubmit to USB.
        if (next in TERMINAL) activeJobId = null
    }

    @Synchronized fun interrupted(jobId: Long) {
        check(activeJobId == jobId) { "Unknown job" }
        val next = if (phase == JobPhase.TRANSMITTING ||
            phase == JobPhase.WAITING_CONFIRMATION) JobPhase.OUTCOME_UNKNOWN else JobPhase.ERROR
        transition(jobId, next)
    }

    @Synchronized fun hasActiveJob(): Boolean = activeJobId != null

    companion object {
        val TERMINAL = setOf(JobPhase.COMPLETED, JobPhase.CANCELLED,
            JobPhase.ERROR, JobPhase.OUTCOME_UNKNOWN)
    }
}
