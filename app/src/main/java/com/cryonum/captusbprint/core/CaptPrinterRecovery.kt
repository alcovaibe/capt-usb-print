package com.cryonum.captusbprint.core

/** Explicit cancellation/maintenance only. Never called to retry a print job. */
object CaptPrinterRecovery {
    fun isPaperOut(s: CaptExtendedStatus): Boolean = s.engineFlags == 0 &&
        s.paperAvailabilityByte == 0 && s.engineCommunicationByte == 0x40 &&
        (s.basicStatusByte and 0x12) == 0x12

    /** Valid only on the same aligned session that returned this reservation. */
    fun cancelOwned(query: (Int, ByteArray) -> ByteArray, jobId: Int, jobStarted: Boolean) {
        require(jobId in 1..65535)
        fun call(command: Int, body: ByteArray) {
            CaptCommandAcks.requireAccepted(command, query(command, body))
        }
        if (jobStarted) {
            call(CaptJobProtocol.SET_LED_STATUS, ByteArray(12))
            call(CaptJobProtocol.SET_JOB_INFO2, CaptJobProtocol.jobInfo(jobId, CaptJobProtocol.ABORT_FLAG))
        }
        call(CaptJobProtocol.RELEASE_UNIT, CaptJobProtocol.pageNumber(jobId))
    }

    /** A rejected sheet can resume only before the engine accepted it. No guessed delivery boundary. */
    fun canResumeAfter(s: CaptExtendedStatus, confirmed: Int): Boolean = isPaperOut(s) &&
        s.pageOut == confirmed && s.pageCompleted == confirmed && s.pagePrinting == confirmed &&
        (s.pageDecoding ?: -1) in confirmed..confirmed + 1 && (s.pageReceived ?: -1) in confirmed..confirmed + 1 &&
        (s.auxiliaryByte and 0x04) == 0

    data class Cleared(val paperReady: Boolean)

    /** User-requested global clear, using pinned Reserve-error clear order.
     * Verify with one new Reserve/Release pair; never release a remembered ID.
     * The probe creates no page, JobInfo START, raster or StartPrint command.
     */
    fun clearAndVerify(query: (Int, ByteArray) -> ByteArray,
                       now: () -> Long, pause: (Long) -> Unit): Cleared {
        fun call(command: Int, body: ByteArray = byteArrayOf()): ByteArray = query(command, body).also {
            CaptCommandAcks.requireAccepted(command, it)
        }
        fun state() = CaptExtendedStatusParser.parse(call(CaptJobProtocol.GET_EXTENDED_STATUS))
        var s = state()
        check(s.engineFlags == 0 && (s.engineCommunicationByte and 0xbf) == 0) {
            "Hardware/communication fault prevents printer clear"
        }
        // Pinned epilogue warns that clearing while Aux.PAPER_DELIVERY is set
        // can interrupt physical sheet delivery. A clear request is not a USB reset.
        val deliveryDeadline = now() + 15_000L
        var deliverySamples = 0
        while ((s.auxiliaryByte and 0x04) != 0 && now() < deliveryDeadline && deliverySamples++ < 126) {
            pause(120); s = state()
            check(s.engineFlags == 0 && (s.engineCommunicationByte and 0xbf) == 0) {
                "Hardware fault while awaiting paper delivery"
            }
        }
        check((s.auxiliaryByte and 0x04) == 0) { "Paper delivery is still active; clear stopped" }
        if ((s.basicStatusByte and 0x10) == 0) call(CaptJobProtocol.GO_OFFLINE, byteArrayOf(0, 0))
        val deadline = now() + 5_000L
        var samples = 0
        while ((s.basicStatusByte and 0x10) == 0 && now() < deadline && samples++ < 42) {
            pause(120)
            s = state()
            check(s.engineFlags == 0) { "Engine fault during printer clear" }
        }
        check((s.basicStatusByte and 0x10) != 0) { "Printer did not confirm offline; clear stopped" }
        call(CaptJobProtocol.DISCARD_DATA)
        call(CaptJobProtocol.CLEAR_MIS_PRINT)
        call(CaptJobProtocol.CLEAR_ERROR)
        call(CaptJobProtocol.GO_ONLINE, CaptJobProtocol.goOnlineSettings())
        fun zeroCounters(s: CaptExtendedStatus) = CaptPageCounterProgress.snapshot(s).let {
            it.decoding == 0 && it.printing == 0 && it.out == 0 && it.completed == 0 && it.received == 0
        }
        var online: CaptExtendedStatus? = null
        val onlineDeadline = now() + 5_000L
        repeat(42) {
            if (online == null && now() <= onlineDeadline) {
                s = state()
                check(s.engineFlags == 0 && (s.engineCommunicationByte and 0xbf) == 0) {
                    "Hardware/communication fault during printer clear"
                }
                if (s.engineCommunicationByte == 0 && (s.basicStatusByte and 0x9e) == 0 &&
                    zeroCounters(s) && (s.auxiliaryByte and 0x06) == 0) online = s
                else if (now() < onlineDeadline) pause(120)
            }
        }
        check(online != null) { "Printer clear was not confirmed by online status/zero counters" }
        val id = CaptJobProtocol.parseReservedJob(call(CaptJobProtocol.RESERVE_UNIT,
            CaptJobProtocol.reserveRequest()))
        call(CaptJobProtocol.RELEASE_UNIT, CaptJobProtocol.pageNumber(id))
        val input = CaptInputStatusParser.parse(call(CaptJobProtocol.GET_INPUT_STATUS))
        s = state()
        check(s.engineFlags == 0 && s.engineCommunicationByte == 0 &&
            (s.basicStatusByte and 0x9e) == 0 && zeroCounters(s) && (s.auxiliaryByte and 0x06) == 0) {
            "Printer changed state after release; clear not confirmed"
        }
        // Engine/input readiness is not a physical paper-tray presence sensor.
        val inputReady = input.size >= 3 && input[0].toInt() == 0 && (input[2].toInt() and 0x40) != 0
        return Cleared(inputReady && s.paperAvailabilityByte == 0x80 && s.engineFlags == 0 &&
            s.engineCommunicationByte == 0 && (s.basicStatusByte and 0x9e) == 0)
    }
}

class CaptPaperOut(val status: CaptExtendedStatus) : IllegalStateException("Printer reports paper-out; user continuation required")
class CaptPrintCancelled : IllegalStateException("Print cancellation requested")
