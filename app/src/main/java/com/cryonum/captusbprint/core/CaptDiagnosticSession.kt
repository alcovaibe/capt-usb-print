package com.cryonum.captusbprint.core

/** Only these four read-only commands can be issued by a diagnostic session. */
enum class CaptDiagnosticCommand(val code: Int) {
    GetBasicStatus(CaptJobProtocol.GET_BASIC_STATUS),
    GetExtendedStatus(CaptJobProtocol.GET_EXTENDED_STATUS),
    GetInputStatus(CaptJobProtocol.GET_INPUT_STATUS),
    GetPrinterInfo(CaptJobProtocol.GET_PRINTER_INFO)
}

data class CaptDiagnosticReply(val summary: String, val reportLine: String,
    val rawFrameHex: String, val decoded: Map<String, Any?>)

class CaptDiagnosticException(message: String, val rawFrameHex: String? = null,
    val stage: String = "connection", val connectionUsable: Boolean = false,
    val transport: Map<String, Any?> = emptyMap()) : Exception(message)

data class CaptDiagnosticResult(val summary: String, val outcome: String,
    val report: Map<String, Any?>)

/** One bounded snapshot, never a print readiness permit or a recovery operation.
 * The connection/lease is held across all exchanges; broken framing stops traffic.
 */
class CaptDiagnosticSession(
    private val id: String,
    private val utcNow: () -> String,
    private val now: () -> Long,
    private val pause: (Long) -> Unit,
    private val cancelled: () -> Boolean,
    private val context: Map<String, Any?>,
    private val progress: (String) -> Unit = {}
) {
    companion object {
        const val MAX_SAMPLES = 3
        const val BUDGET_MS = 15_000L
    }
    private val steps = mutableListOf<Map<String, Any?>>()
    private val observations = mutableListOf<Map<String, Any?>>()
    private val startedUtc = utcNow()
    private val started = now()
    private var stopped: String? = null
    private var connectionError: Map<String, Any?>? = null
    private var assessment = "UNKNOWN"
    private var infoUsable: Boolean? = null

    fun collect(withConnection: (block: ((CaptDiagnosticCommand) -> CaptDiagnosticReply) -> Unit) -> Unit): CaptDiagnosticResult {
        try {
            if (!canContinue()) throw CaptDiagnosticException("Проверка отменена до открытия USB.")
            withConnection { query ->
                for (sample in 1..MAX_SAMPLES) {
                    val basic = execute(CaptDiagnosticCommand.GetBasicStatus, sample, query)
                    val extended = execute(CaptDiagnosticCommand.GetExtendedStatus, sample, query)
                    val b = basic?.decoded
                    val e = extended?.decoded
                    val inputNeeded = ((number(b, "secondaryByte") ?: 0) or
                        (number(e, "secondaryByte") ?: 0)) and 2 != 0
                    assessment = assess(b, e, inputNeeded)
                    observations += linkedMapOf("sample" to sample, "timeUtc" to utcNow(),
                        "assessment" to assessment, "basicStatusByte" to number(b, "statusByte"),
                        "extendedBasicStatusByte" to number(e, "basicStatusByte"),
                        "inputStatusRequested" to inputNeeded)
                    if (inputNeeded) execute(CaptDiagnosticCommand.GetInputStatus, sample, query)
                    else skipped(CaptDiagnosticCommand.GetInputStatus, sample,
                        stopped ?: if (b == null || e == null) "STATUS_UNAVAILABLE" else "NOT_REQUESTED_BY_PRINTER")
                    if (stopped != null || basic == null || extended == null ||
                        assessment !in setOf("STATUS_CONFLICT", "BUSY", "INPUT_STATUS_PENDING") ||
                        sample == MAX_SAMPLES) break
                    if (!canContinue()) break
                    pause(250L)
                }
                val info = execute(CaptDiagnosticCommand.GetPrinterInfo, null, query)
                infoUsable = info?.decoded?.get("transferParametersUsable") as? Boolean
            }
        } catch (error: Exception) {
            if (stopped == null) stopped = if (cancelled() || error is InterruptedException) "CANCELLED" else "CONNECTION_FAILED"
            connectionError = errorDetails(error)
        }
        // Even connection/setup failures have an explicit outcome for every mandatory step.
        for (command in listOf(CaptDiagnosticCommand.GetBasicStatus,
            CaptDiagnosticCommand.GetExtendedStatus, CaptDiagnosticCommand.GetInputStatus,
            CaptDiagnosticCommand.GetPrinterInfo)) {
            if (steps.none { it["action"] == command.name }) skipped(command, null, stopped ?: "NOT_AVAILABLE")
        }
        val failed = steps.any { it["outcome"] == "failed" }
        val outcome = when {
            stopped == "CANCELLED" -> "cancelled"
            stopped != null || failed -> "incomplete"
            else -> "completed"
        }
        val failureReason = (connectionError?.get("message") as? String)
            ?: steps.firstOrNull { it["outcome"] == "failed" }?.get("summary") as? String
        val summary = when {
            outcome == "cancelled" -> "Диагностика отменена. Собранные результаты сохранены в JSON."
            outcome == "incomplete" -> "Диагностика выполнена не полностью" +
                (failureReason?.let { ": " + it.take(300).trimEnd('.') } ?: "") +
                ". Полученные ответы и причины пропусков сохранены в JSON."
            assessment == "STATUS_CONFLICT" -> "Ответы принтера противоречат друг другу. Готовность не подтверждена."
            assessment == "ERROR_OR_NOT_READY" -> "Принтер сообщает ошибку или неготовность. Подробности сохранены в JSON."
            assessment == "BUSY" -> "Принтер сообщает занятость. Подробности сохранены в JSON."
            assessment == "INPUT_STATUS_PENDING" -> "Принтер продолжает запрашивать дополнительный статус. Готовность не подтверждена."
            infoUsable != true -> "Проверка завершена, но параметры передачи не подтверждены."
            assessment == "NO_KNOWN_BLOCKERS" -> "Диагностика завершена: известных признаков блокировки не обнаружено. Это не проверка печати."
            else -> "Диагностика завершена. Данных для оценки готовности недостаточно."
        }
        return CaptDiagnosticResult(summary, outcome, linkedMapOf(
            "sessionSchemaVersion" to 1, "sessionId" to id,
            "startedAtUtc" to startedUtc, "finishedAtUtc" to utcNow(),
            "durationMs" to (now() - started).coerceAtLeast(0),
            "outcome" to outcome, "assessment" to assessment, "summary" to summary,
            "stopReason" to stopped, "connectionError" to connectionError,
            "transferParametersUsable" to infoUsable, "maxSamples" to MAX_SAMPLES,
            "budgetMs" to BUDGET_MS, "context" to context,
            "observations" to observations.toList(), "steps" to steps.toList(),
            "printDataSent" to false, "recoveryCommandsSent" to false,
            "readinessGuarantee" to false))
    }

    private fun canContinue(): Boolean {
        if (stopped != null) return false
        stopped = when {
            cancelled() || Thread.currentThread().isInterrupted -> "CANCELLED"
            now() - started >= BUDGET_MS -> "TIME_BUDGET_EXHAUSTED"
            else -> null
        }
        return stopped == null
    }

    private fun execute(command: CaptDiagnosticCommand, sample: Int?, query: (CaptDiagnosticCommand) -> CaptDiagnosticReply): CaptDiagnosticReply? {
        if (!canContinue()) { skipped(command, sample, stopped!!); return null }
        progress(command.name)
        val time = utcNow()
        val begin = now()
        var reply: CaptDiagnosticReply? = null
        var failure: Exception? = null
        try { reply = query(command) } catch (error: Exception) {
            failure = error
            if (error !is CaptDiagnosticException || !error.connectionUsable) {
                stopped = if (cancelled() || error is InterruptedException) "CANCELLED" else "TRANSPORT_FAILED"
            }
        }
        steps += linkedMapOf("index" to steps.size + 1, "sessionId" to id,
            "sample" to sample, "timeUtc" to time, "finishedAtUtc" to utcNow(),
            "durationMs" to (now() - begin).coerceAtLeast(0), "action" to command.name,
            "commandCode" to command.code, "requestFrameHex" to hex(CaptFrame.encode(command.code)),
            "outcome" to if (failure == null) "success" else "failed",
            "summary" to (reply?.summary ?: failure?.message),
            "rawResponseFrameHex" to (reply?.rawFrameHex ?: (failure as? CaptDiagnosticException)?.rawFrameHex),
            "decoded" to (reply?.decoded ?: emptyMap<String, Any?>()),
            "reportText" to reply?.reportLine, "error" to failure?.let { errorDetails(it) })
        return reply
    }

    private fun skipped(command: CaptDiagnosticCommand, sample: Int?, reason: String) {
        steps += linkedMapOf("index" to steps.size + 1, "sessionId" to id,
            "sample" to sample, "action" to command.name, "outcome" to "skipped",
            "reason" to reason, "requestFrameHex" to null, "rawResponseFrameHex" to null)
    }

    private fun errorDetails(error: Exception): Map<String, Any?> = linkedMapOf(
        "type" to error.javaClass.simpleName,
        "message" to if (error is CaptDiagnosticException) error.message else "Diagnostic operation failed",
        "stage" to (error as? CaptDiagnosticException)?.stage,
        "connectionUsable" to ((error as? CaptDiagnosticException)?.connectionUsable ?: false),
        "transport" to ((error as? CaptDiagnosticException)?.transport ?: emptyMap<String, Any?>()))

    private fun assess(b: Map<String, Any?>?, e: Map<String, Any?>?, inputNeeded: Boolean): String {
        val basic = number(b, "statusByte") ?: return "UNKNOWN"
        val extended = number(e, "basicStatusByte") ?: return "UNKNOWN"
        val engine = number(e, "engineFlags") ?: return "UNKNOWN"
        val communication = number(e, "engineCommunicationByte") ?: return "UNKNOWN"
        val paper = number(e, "paperAvailabilityByte") ?: return "UNKNOWN"
        val auxiliary = number(e, "auxiliaryByte") ?: return "UNKNOWN"
        // Same blocking basic bits as document preflight, plus OFFLINE and unknown bits.
        val basicBlocked = PdfBatchPrinterReadiness.rejection(basic) != null || basic and 0x30 != 0
        val extendedBlocked = extended and 0xbe != 0 || engine != 0 || communication != 0 || paper != 0x80
        if (basicBlocked != extendedBlocked && !inputNeeded && auxiliary and 6 == 0) return "STATUS_CONFLICT"
        if ((basic or extended) and 0xb2 != 0 || engine != 0 || communication != 0 || paper != 0x80)
            return "ERROR_OR_NOT_READY"
        if (inputNeeded) return "INPUT_STATUS_PENDING"
        if ((basic or extended) and 0x0c != 0 || auxiliary and 6 != 0) return "BUSY"
        val counters = listOf("pageDecoding", "pagePrinting", "pageOut", "pageCompleted").map { number(e, it) }
        if (counters.any { it == null }) return "UNKNOWN"
        if (counters.distinct().size != 1 || number(e, "pageReceived")?.let { it != counters[0] } == true) return "BUSY"
        if (auxiliary and 0x79 != 0 || (number(b, "secondaryByte") ?: 0) and 0xfc != 0 ||
            (number(e, "secondaryByte") ?: 0) and 0xfc != 0) return "UNKNOWN"
        return "NO_KNOWN_BLOCKERS"
    }

    private fun number(map: Map<String, Any?>?, key: String) = (map?.get(key) as? Number)?.toInt()
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }
}
