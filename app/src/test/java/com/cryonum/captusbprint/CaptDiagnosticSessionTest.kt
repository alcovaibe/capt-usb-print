package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.*
import org.junit.Assert.*
import org.junit.Test

class CaptDiagnosticSessionTest {
    private class Fixture {
        var clock = 0L
        var cancelled = false
        val commands = mutableListOf<CaptDiagnosticCommand>()
        fun run(answer: (CaptDiagnosticCommand) -> CaptDiagnosticReply = { reply(it) }): CaptDiagnosticResult {
            val session = CaptDiagnosticSession("session-1", { "2026-10-23T10:00:00Z" },
                { clock }, { clock += it }, { cancelled }, emptyMap())
            return session.collect { block -> block { command -> commands += command; clock += 5; answer(command) } }
        }
    }
    companion object {
        private fun reply(command: CaptDiagnosticCommand, basic: Int = 0x41,
            extended: Int = 0x41, secondary: Int = 0, counters: Boolean = true): CaptDiagnosticReply {
            val data = when (command) {
                CaptDiagnosticCommand.GetBasicStatus -> mapOf("statusByte" to basic, "secondaryByte" to secondary)
                CaptDiagnosticCommand.GetExtendedStatus -> mapOf(
                    "basicStatusByte" to extended, "secondaryByte" to secondary,
                    "engineFlags" to 0, "engineCommunicationByte" to 0,
                    "paperAvailabilityByte" to 0x80, "auxiliaryByte" to 0,
                    "pageDecoding" to if (counters) 1 else null,
                    "pagePrinting" to if (counters) 1 else null,
                    "pageOut" to if (counters) 1 else null,
                    "pageCompleted" to if (counters) 1 else null, "pageReceived" to null)
                CaptDiagnosticCommand.GetInputStatus -> mapOf("payloadDecoded" to false)
                CaptDiagnosticCommand.GetPrinterInfo -> mapOf("transferParametersUsable" to true)
            }
            return CaptDiagnosticReply(command.name, "report", "A0 E0 06 00 41 00", data)
        }
        @Suppress("UNCHECKED_CAST")
        private fun steps(result: CaptDiagnosticResult) = result.report["steps"] as List<Map<String, Any?>>
    }

    @Test fun idleCapturesThreeQueriesAndExplicitInputSkip() {
        val f = Fixture(); val result = f.run()
        assertEquals(listOf(CaptDiagnosticCommand.GetBasicStatus, CaptDiagnosticCommand.GetExtendedStatus,
            CaptDiagnosticCommand.GetPrinterInfo), f.commands)
        assertEquals("completed", result.outcome)
        assertEquals("NO_KNOWN_BLOCKERS", result.report["assessment"])
        assertEquals(false, result.report["readinessGuarantee"])
        assertEquals("NOT_REQUESTED_BY_PRINTER", steps(result).single { it["action"] == "GetInputStatus" }["reason"])
        assertTrue(steps(result).filter { it["outcome"] == "success" }.all { it["requestFrameHex"] != null && it["durationMs"] == 5L })
    }
    @Test fun persistent88IsConflictAndNeverPrintsOrRecovers() {
        val f = Fixture(); val result = f.run { reply(it, basic = 0x88) }
        assertEquals("STATUS_CONFLICT", result.report["assessment"])
        assertEquals(3, f.commands.count { it == CaptDiagnosticCommand.GetBasicStatus })
        assertEquals(7, f.commands.size)
        assertEquals(false, result.report["recoveryCommandsSent"])
        assertEquals(false, result.report["printDataSent"])
        assertTrue(CaptDiagnosticCommand.entries.all { it.code in listOf(0xe0a0, 0xa0a8, 0xa0a1, 0xa1a1) })
    }
    @Test fun requestedInputIsReadAndBothStatusesAreResampled() {
        val f = Fixture(); var inputRead = false
        val result = f.run { command ->
            if (command == CaptDiagnosticCommand.GetInputStatus) inputRead = true
            reply(command, secondary = if (inputRead) 0 else 2)
        }
        assertEquals(listOf(CaptDiagnosticCommand.GetBasicStatus, CaptDiagnosticCommand.GetExtendedStatus,
            CaptDiagnosticCommand.GetInputStatus, CaptDiagnosticCommand.GetBasicStatus,
            CaptDiagnosticCommand.GetExtendedStatus, CaptDiagnosticCommand.GetPrinterInfo), f.commands)
        assertEquals("NO_KNOWN_BLOCKERS", result.report["assessment"])
    }
    @Test fun truncatedTransportPreservesPartialBytesAndSkipsRemainingTraffic() {
        val f = Fixture(); val result = f.run {
            throw CaptDiagnosticException("partial", "A0 E0 06", "read_header",
                transport = mapOf("receivedBytes" to 3, "transferReturnCode" to 3))
        }
        assertEquals(1, f.commands.size)
        assertEquals("incomplete", result.outcome)
        assertEquals("A0 E0 06", steps(result).first()["rawResponseFrameHex"])
        assertEquals(3, steps(result).count { it["outcome"] == "skipped" })
        assertEquals("TRANSPORT_FAILED", result.report["stopReason"])
    }
    @Test fun completeButUndecodableReplyDoesNotHidePrinterInfo() {
        val f = Fixture(); val result = f.run {
            if (it == CaptDiagnosticCommand.GetExtendedStatus)
                throw CaptDiagnosticException("short payload", "A8 A0 06 00 00 00", "decode", true)
            reply(it)
        }
        assertTrue(f.commands.contains(CaptDiagnosticCommand.GetPrinterInfo))
        assertEquals("incomplete", result.outcome)
        assertEquals("UNKNOWN", result.report["assessment"])
    }
    @Test fun cancellationStopsBetweenCommandsAndKeepsCollectedReply() {
        val f = Fixture(); val result = f.run { command -> f.cancelled = true; reply(command) }
        assertEquals(1, f.commands.size)
        assertEquals("cancelled", result.outcome)
        assertEquals("success", steps(result).first()["outcome"])
    }
    @Test fun deadlinePreventsFurtherCommandsEvenDuringBusyPolling() {
        val f = Fixture(); val result = f.run { command -> f.clock += 16_000; reply(command) }
        assertEquals(1, f.commands.size)
        assertEquals("TIME_BUDGET_EXHAUSTED", result.report["stopReason"])
        assertEquals("incomplete", result.outcome)
    }
    @Test fun shortExtendedStatusDoesNotClaimReadiness() {
        assertEquals("UNKNOWN", Fixture().run { reply(it, counters = false) }.report["assessment"])
    }
    @Test fun unsupportedStatusBitsRemainUnknownRatherThanInventingAnError() {
        val result = Fixture().run { reply(it, basic = 0x61, extended = 0x61) }
        assertEquals("UNKNOWN", result.report["assessment"])
        assertFalse(result.summary.contains("ошибку"))
    }
    @Test fun ownershipFailureProducesWholeSessionWithSkippedCommands() {
        val session = CaptDiagnosticSession("blocked", { "time" }, { 0 }, {}, { false }, emptyMap())
        val result = session.collect { throw CaptDiagnosticException("busy", stage = "ownership") }
        assertEquals("incomplete", result.outcome)
        assertEquals(4, steps(result).size)
        assertTrue(steps(result).all { it["outcome"] == "skipped" })
        assertEquals("CONNECTION_FAILED", result.report["stopReason"])
    }
    @Test fun wholeSessionRetentionBoundsUtf8BytesWithoutRemovingSteps() {
        val session = mapOf<String, Any?>("sessionId" to 1, "steps" to listOf("я".repeat(20_000)))
        val events = (1..30).map { mapOf<String, Any?>("id" to it, "decoded" to session) }
        val kept = DiagnosticEventRetention.retain(events)
        assertTrue(kept.size < events.size)
        assertEquals(30, kept.last()["id"])
        assertTrue(kept.all { it["decoded"] == session })
        assertTrue(kept.sumOf { DiagnosticJson.stringify(it).toByteArray().size + 1 } <= DiagnosticEventRetention.MAX_EVENTS_BYTES)
    }
    private fun capturedReply(command: CaptDiagnosticCommand, secondary: Int): CaptDiagnosticReply {
        fun bytes(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        if (command == CaptDiagnosticCommand.GetBasicStatus) {
            val raw = bytes("A0 E0 06 00 88 00")
            val b = CaptBasicStatusParser.parse(raw)
            return CaptDiagnosticReply("short", "captured Basic", CaptProtocolTrace.hex(raw), mapOf(
                "statusByte" to b.statusByte, "secondaryByte" to b.secondaryByte,
                "responseForm" to b.responseForm, "payloadBytes" to b.payloadBytes))
        }
        if (command == CaptDiagnosticCommand.GetExtendedStatus) {
            val raw = bytes("A8 A0 38 00 01 88 00 00 0F 00 00 00 00 00 80 00 00 00 02 00 02 00 02 00 02 00 00 00 57 00 00 00 01 00 00 00 00 05 02 00 00 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00")
            // 0x88 and 0x8A were both observed; mutate only the secondary field.
            raw[5] = secondary.toByte()
            val e = CaptExtendedStatusParser.parse(raw)
            return CaptDiagnosticReply("extended", "captured Extended", CaptProtocolTrace.hex(raw), mapOf(
                "basicStatusByte" to e.basicStatusByte, "secondaryByte" to e.secondaryByte,
                "engineFlags" to e.engineFlags, "engineCommunicationByte" to e.engineCommunicationByte,
                "paperAvailabilityByte" to e.paperAvailabilityByte, "auxiliaryByte" to e.auxiliaryByte,
                "pageDecoding" to e.pageDecoding, "pagePrinting" to e.pagePrinting,
                "pageOut" to e.pageOut, "pageCompleted" to e.pageCompleted, "pageReceived" to e.pageReceived))
        }
        return reply(command)
    }

    @Test fun capturedUnknownSecondaryFlagsDoNotSuppressBoundedResampling() {
        for (secondary in listOf(0x88, 0x8a)) {
            val f = Fixture()
            val result = f.run { capturedReply(it, secondary) }
            assertEquals("UNKNOWN", result.report["assessment"])
            assertEquals(3, f.commands.count { it == CaptDiagnosticCommand.GetBasicStatus })
            assertEquals(if (secondary == 0x8a) 3 else 0,
                f.commands.count { it == CaptDiagnosticCommand.GetInputStatus })
            assertEquals(false, result.report["readinessGuarantee"])
            assertEquals(false, result.report["recoveryCommandsSent"])
            assertEquals(false, result.report["printDataSent"])
            assertFalse(result.summary.contains("ошибку"))
        }
    }

    @Test fun inputConsumedWithUnknownBitsStillGetsFreshStatusPair() {
        val f = Fixture()
        var pending = true
        val result = f.run { command ->
            val answer = capturedReply(command, if (pending) 0x8a else 0x88)
            if (command == CaptDiagnosticCommand.GetInputStatus) pending = false
            answer
        }
        assertEquals(1, f.commands.count { it == CaptDiagnosticCommand.GetInputStatus })
        assertEquals(3, f.commands.count { it == CaptDiagnosticCommand.GetExtendedStatus })
        assertEquals("UNKNOWN", result.report["assessment"])
    }

}
