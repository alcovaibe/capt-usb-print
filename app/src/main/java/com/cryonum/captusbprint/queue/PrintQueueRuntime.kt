package com.cryonum.captusbprint.queue

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.AtomicFile
import com.cryonum.captusbprint.BuildConfig
import com.cryonum.captusbprint.FirstPdfPrintAttempt
import com.cryonum.captusbprint.core.DiagnosticJson
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.DocumentLayout
import com.cryonum.captusbprint.core.DocumentContinuation
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.usb.CaptPrinterMaintenance
import com.cryonum.captusbprint.usb.CaptPrinterStateStore
import com.cryonum.captusbprint.document.DocumentSession
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.SystemSpoolReader
import com.cryonum.captusbprint.document.StoredCaptPage
import com.cryonum.captusbprint.printservice.SystemPrintRequest
import com.cryonum.captusbprint.usb.CaptDocumentPrintEngine
import com.cryonum.captusbprint.usb.CaptTestPrintResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Process-wide ownership of document sources, persistent metadata and one executor's jobs. */
internal object PrintQueueRuntime {
    private const val MAX_DISK_BYTES = 256L * 1024 * 1024
    private const val HEADROOM = 8L * 1024 * 1024
    private val ids = AtomicLong(System.currentTimeMillis())
    private val queue = DocumentQueue()
    private val sources = HashMap<Long, Work>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false
    private val allocations = HashMap<Long, Long>()
    private val intakes = HashSet<Long>()
    private var cancelVersion = 0L
    private var orphanBytes = 0L
    private var maintenanceBusy = false
    private var maintenanceFailure: String? = null
    private lateinit var app: Context
    private data class Work(val source: DocumentSession?, val plan: JobSpecification,
                            val deviceKey: String, val directory: File,
                            val systemMetadata: Map<String, Any?>? = null,
                            var prepared: Map<Int, StoredCaptPage>? = null)
    private fun root() = File(app.noBackupFilesDir, "print-queue").apply { mkdirs() }
    private fun history() = File(app.noBackupFilesDir, "print-history.json")

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        app = context.applicationContext
        val f = history()
        try {
            if (f.isFile) {
                check(f.length() <= 1024 * 1024)
                val json = JSONObject(AtomicFile(f).openRead().bufferedReader().use { it.readText() })
                if (json.optBoolean("maintenancePending"))
                    CaptPrinterStateStore.requireClear(app, "MAINTENANCE_INTERRUPTED")
                val items = json.getJSONArray("jobs")
                check(items.length() <= 50)
                val records = (0 until items.length()).map { n ->
                    val o = items.getJSONObject(n)
                    QueueRecord(o.getLong("id"), o.getString("format"), o.getInt("sheets"),
                        QueuePhase.valueOf(o.getString("phase")), o.getInt("confirmed"),
                        o.getBoolean("usbStarted"), failure = if (o.isNull("failure")) null else o.getString("failure"))
                }
                val resumable = records.filter { it.phase == QueuePhase.WAITING_PAPER && it.confirmed < it.sheets }
                    .mapNotNull { record ->
                        runCatching { readWork(record) }.getOrNull()?.let { work ->
                            sources[record.id] = work
                            allocations[record.id] = work.directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            record.id
                        }
                    }.toSet()
                check(allocations.values.sum() <= MAX_DISK_BYTES)
                queue.restore(records, json.optBoolean("paused"), resumable)
            }
        } catch (_: Exception) {
            // Corrupt metadata must not prevent manual diagnostics or imply a successful print.
            sources.clear(); allocations.clear()
            queue.restore(listOf(QueueRecord(ids.incrementAndGet(), "UNKNOWN", 1,
                QueuePhase.OUTCOME_UNKNOWN, failure = "HISTORY_UNREADABLE")))
        }
        // Only an explicitly paused, cleanly released paper-out segment may survive.
        // It remains paused until the user presses Continue; CRC is checked before USB.
        root().listFiles()?.filter { it.name.toLongOrNull() !in sources.keys }?.forEach { it.deleteRecursively() }
        orphanBytes = root().listFiles()?.filter { it.name.toLongOrNull() !in sources.keys }
            ?.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } } ?: 0
        ids.set(maxOf(ids.get(), queue.snapshot().maxOfOrNull { it.id } ?: 0))
        initialized = true
        persist()
    }

    private fun readWork(record: QueueRecord): Work {
        val directory = File(root(), record.id.toString())
        val manifest = File(directory, "resume.json")
        check(manifest.isFile && manifest.length() in 1..128 * 1024)
        val json = JSONObject(AtomicFile(manifest).openRead().bufferedReader().use { it.readText() })
        check(json.getBoolean("resumeAllowed") && json.getInt("confirmed") == record.confirmed)
        val pages = json.getJSONArray("pages")
        check(pages.length() in 1..JobSpecification.MAX_SOURCE_PAGES)
        val indices = (0 until pages.length()).map { pages.getInt(it) }
        val plan = JobSpecification.restorePrepared(indices, json.getInt("copies"),
            Orientation.valueOf(json.getString("orientation")), json.getInt("rotation"),
            DocumentLayout.valueOf(json.getString("layout")))
        check(plan.outputPageOrder.size == record.sheets)
        val sizes = json.getJSONObject("compressedBytes")
        val prepared = indices.associateWith { StoredCaptPage.open(File(directory, "page-$it.bin"), sizes.getInt(it.toString())) }
        val metadata = json.optJSONObject("systemPrint")?.let { mapOf("restoredPausedSystemJob" to true) }
        return Work(null, plan, "", directory, metadata, prepared)
    }
    private fun writeWork(work: Work, confirmed: Int, resumeAllowed: Boolean) {
        atomicWrite(File(work.directory, "resume.json"), mapOf("schemaVersion" to 1,
            "resumeAllowed" to resumeAllowed, "confirmed" to confirmed,
            "pages" to work.plan.pageIndices, "copies" to work.plan.copies,
            "orientation" to work.plan.orientation.name, "rotation" to work.plan.contentRotationDegrees,
            "layout" to work.plan.layout.name, "systemPrint" to work.systemMetadata,
            "compressedBytes" to work.prepared!!.mapKeys { it.key.toString() }.mapValues { it.value.compressedBytes }))
    }

    private fun atomicWrite(file: File, data: Map<String, Any?>) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write((DiagnosticJson.stringify(data) + "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun persist() {
        atomicWrite(history(), mapOf("schemaVersion" to 2, "paused" to queue.paused,
            "maintenancePending" to maintenanceBusy,
            "jobs" to queue.snapshot().map { r -> mapOf("id" to r.id, "format" to r.format,
                "sheets" to r.sheets, "phase" to r.phase.name, "confirmed" to r.confirmed,
                "usbStarted" to r.usbStarted, "failure" to r.failure) }))
        main.post { listeners.forEach { it() } }
    }
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    @Synchronized fun snapshot() = queue.snapshot()
    @Synchronized fun isPaused() = queue.paused || maintenanceBusy || CaptPrinterStateStore.requiresClear(app)
    @Synchronized fun hasRunnableWork() = !maintenanceBusy && !CaptPrinterStateStore.requiresClear(app) && queue.hasRunnableWork()
    @Synchronized fun hasWork() = queue.snapshot().any { !it.phase.terminal }
    @Synchronized fun hasRoom() = !maintenanceBusy && queue.snapshot().count { !it.phase.terminal } + intakes.size < 4
    @Synchronized fun cancelGeneration() = cancelVersion

    /** Worker-thread only; caller retains its preview source and owns no queue files. */
    @Synchronized fun enqueue(context: Context, source: DocumentSession,
        plan: JobSpecification, deviceKey: String): Long {
        initialize(context)
        check(hasRoom()) { "Queue full" }
        require(plan.pageIndices.all { it in 0 until source.overview.pageCount })
        val id = ids.incrementAndGet()
        val directory = File(root(), id.toString()).apply { check(mkdir()) }
        allocations[id] = 0
        var copied: DocumentSession? = null
        try {
            copied = source.copyForPrint(directory) { reserve(id, it) }
            queue.add(QueueRecord(id, source.overview.format.name, plan.outputPageOrder.size, ready = false))
            sources[id] = Work(copied, plan, deviceKey, directory)
            persist()
            return id
        } catch (error: Throwable) {
            copied?.close()
            sources.remove(id)
            if (directory.deleteRecursively()) allocations.remove(id)
            if (queue.snapshot().any { it.id == id && !it.phase.terminal }) queue.update(id, QueuePhase.ERROR)
            throw error
        }
    }

    /** Own the delivered PDF directly, sharing the same quota/FIFO; no content URI or second copy. */
    fun enqueueSystem(context: Context, descriptor: ParcelFileDescriptor, request: SystemPrintRequest,
                      deviceKey: String, cancelled: () -> Boolean): Long {
        var id: Long? = null
        var directory: File? = null
        var source: DocumentSession? = null
        var generation = 0L
        try {
            synchronized(this) {
                initialize(context)
                check(hasRoom()) { "Queue full" }
                id = ids.incrementAndGet()
                directory = File(root(), id.toString()).apply { check(mkdir()) }
                allocations[id!!] = 0
                intakes.add(id!!)
                generation = cancelVersion
            }
            val ownedId = id!!
            val ownedDirectory = directory!!
            val file = File(ownedDirectory, "source.bin")
            val bytes = SystemSpoolReader.copy(descriptor, file,
                { synchronized(this) { reserve(ownedId, it) } },
                { cancelled() || cancelGeneration() != generation })
            source = DocumentSession(file, DocumentInspector.inspectPdf(file, bytes))
            val plan = request.plan(source!!.overview.pageCount)
            val metadata = request.diagnostic() + mapOf("receivedPdfPages" to source!!.overview.pageCount,
                "rangeMapping" to request.selection(source!!.overview.pageCount).mode,
                "spoolPageNumbers" to plan.pageIndices.map { it + 1 })
            synchronized(this) {
                if (cancelled() || generation != cancelVersion) throw CancellationException()
                queue.add(QueueRecord(ownedId, "SYSTEM_PDF", plan.outputPageOrder.size, ready = false))
                sources[ownedId] = Work(source!!, plan, deviceKey, ownedDirectory, metadata)
                persist()
                intakes.remove(ownedId)
            }
            return ownedId
        } catch (error: Throwable) {
            synchronized(this) {
                source?.close()
                id?.let { value ->
                    intakes.remove(value)
                    sources.remove(value)
                    if (directory?.deleteRecursively() == true) allocations.remove(value)
                    if (queue.snapshot().any { it.id == value && !it.phase.terminal }) {
                        queue.update(value, QueuePhase.ERROR, failure = "SYSTEM_INTAKE_FAILED")
                        runCatching { persist() }
                    }
                }
            }
            throw error
        } finally { runCatching { descriptor.close() } }
    }
    private fun reserve(id: Long, extra: Long) {
        require(extra in 0..MAX_DISK_BYTES)
        val used = orphanBytes + allocations.values.sum()
        check(used + extra <= MAX_DISK_BYTES && root().usableSpace >= extra + HEADROOM) {
            "Private queue storage limit or insufficient disk space"
        }
        allocations[id] = allocations.getValue(id) + extra
    }
    /** Publish after the foreground-service request or the durable Android PrintJob tag handoff. */
    @Synchronized fun activate(id: Long) {
        try { queue.activate(id); persist() }
        catch (error: Throwable) {
            // take() uses this same monitor, so a failed marker cannot race a USB start.
            queue.cancel(id); cleanup(id)
            runCatching { persist() }
            throw error
        }
    }
    @Synchronized fun cancel(id: Long) {
        if (queue.snapshot().none { it.id == id }) return
        queue.cancel(id)
        if (queue.snapshot().firstOrNull { it.id == id }?.phase == QueuePhase.CANCELLED) cleanup(id)
        persist()
    }
    @Synchronized fun resumeAfterInspection() {
        check(!maintenanceBusy && !CaptPrinterStateStore.requiresClear(app)) { "PRINTER_CLEAR_REQUIRED" }
        queue.resumeAfterInspection(); persist()
    }
    @Synchronized fun owns(id: Long) = sources.containsKey(id)
    @Synchronized fun maintenanceState(): String = when {
        maintenanceBusy -> "BUSY"
        maintenanceFailure == "PAPER_NOT_READY" -> "PAPER_NOT_READY"
        CaptPrinterStateStore.requiresClear(app) -> "REQUIRED"
        CaptPrinterStateStore.snapshot(app).optString("state") == "CLEAR_VERIFIED" -> "CLEARED"
        else -> ""
    }
    @Synchronized fun isMaintenanceBusy() = maintenanceBusy
    @Synchronized private fun beginMaintenance() {
        check(!maintenanceBusy)
        CaptPrinterStateStore.beginMaintenance()
        maintenanceBusy = true; maintenanceFailure = null
    }
    /** UI-thread request: stop new work and signal ALL active print paths before the serial barrier. */
    @Synchronized fun requestClear() {
        beginMaintenance()
        cancelVersion++
        CaptPrinterStateStore.requestCancellation()
        queue.snapshot().filter { !it.phase.terminal }.forEach { record ->
            queue.cancel(record.id)
            if (queue.snapshot().first { it.id == record.id }.phase == QueuePhase.CANCELLED) cleanup(record.id)
        }
        persist()
    }
    /** Worker only, after the active job has attempted its acknowledged cancellation. */
    fun clearRequested() {
        try {
            synchronized(this) {
                check(maintenanceBusy)
                queue.snapshot().filter { it.phase.terminal }.forEach { cleanup(it.id) }
                queue.clearTerminal()
                File(app.noBackupFilesDir, "print-reports").listFiles()?.forEach { it.delete() }
                persist()
            }
            val result = CaptPrinterMaintenance.run(app)
            synchronized(this) { maintenanceFailure = result.failure }
        } finally { finishMaintenance() }
    }
    @Synchronized fun requestPaperContinuation(id: Long) {
        check(queue.snapshot().any { it.id == id && it.phase == QueuePhase.WAITING_PAPER })
        beginMaintenance()
        CaptPrinterStateStore.requestCancellation()
        persist()
    }
    fun continuePaper(id: Long) {
        try {
            val work = synchronized(this) { sources[id] ?: error("RESUME_SOURCE_MISSING") }
            work.prepared!!.values.forEach { it.validate { cancelled(id) } }
            val result = CaptPrinterMaintenance.run(app)
            synchronized(this) {
                maintenanceFailure = result.failure ?: if (!result.paperReady) "PAPER_NOT_READY" else null
                if (result.cleared && result.paperReady && queue.snapshot().any {
                        it.id == id && it.phase == QueuePhase.WAITING_PAPER }) {
                    queue.resumePaper(id)
                    persist()
                }
            }
        } catch (_: CancellationException) { /* User cancelled the paused job. */ }
        catch (_: Throwable) {
            synchronized(this) {
                if (queue.snapshot().any { it.id == id && it.phase == QueuePhase.WAITING_PAPER }) {
                    queue.update(id, QueuePhase.ERROR, failure = "RESUME_SOURCE_INVALID")
                    cleanup(id); persist()
                }
            }
        } finally { finishMaintenance() }
    }
    @Synchronized fun maintenanceStartFailed() {
        maintenanceFailure = "SERVICE_START_FAILED"
        finishMaintenance()
    }
    @Synchronized private fun finishMaintenance() {
        maintenanceBusy = false
        CaptPrinterStateStore.endMaintenance()
        persist()
    }
    @Synchronized private fun cancelled(id: Long) = queue.snapshot().firstOrNull { it.id == id }?.let { it.cancelRequested || it.phase == QueuePhase.CANCELLED } ?: true
    @Synchronized private fun change(id: Long, phase: QueuePhase, confirmed: Int? = null,
        usbStarted: Boolean? = null, failure: String? = null) {
        queue.update(id, phase, confirmed, usbStarted, failure); persist()
    }
    @Synchronized private fun cleanup(id: Long) {
        sources.remove(id)?.let {
            it.source?.close()
            if (it.directory.deleteRecursively()) allocations.remove(id)
        }
    }
    @Synchronized fun stopServiceJobs(directOnly: Boolean = false) {
        if (!directOnly) cancelVersion++ // Also invalidate in-flight system pipes and pending intake.
        queue.snapshot().filter { !it.phase.terminal &&
            (!directOnly || sources[it.id]?.systemMetadata == null) }.forEach {
            queue.cancel(it.id)
            if (it.phase in setOf(QueuePhase.QUEUED, QueuePhase.WAITING_PAPER)) cleanup(it.id)
        }
        persist()
    }
    @Synchronized fun serviceFailure(directOnly: Boolean = false) {
        if (!directOnly) cancelVersion++
        queue.snapshot().filter { !it.phase.terminal &&
            (!directOnly || sources[it.id]?.systemMetadata == null) }.forEach {
            queue.update(it.id, if (it.usbStarted) QueuePhase.OUTCOME_UNKNOWN else QueuePhase.ERROR,
                failure = "SERVICE_INTERRUPTED")
            cleanup(it.id)
        }
        runCatching { persist() }
    }
    fun reportFile(id: Long) = File(app.noBackupFilesDir, "print-reports/$id.json")
    @Synchronized private fun take(): Pair<QueueRecord, Work>? {
        if (maintenanceBusy || CaptPrinterStateStore.requiresClear(app)) return null
        val record = queue.next() ?: return null
        persist() // Durable PREPARING before work starts.
        return record to (sources[record.id] ?: error("No owned document source"))
    }

    /** Called only from the shared serial PrintQueueWorker. */
    fun drain(progress: (QueueRecord) -> Unit) {
        while (true) {
            val (record, work) = take() ?: break
            val id = record.id
            var usb = false
            var result: CaptTestPrintResult? = null
            var failure: String? = null
            fun report(phase: String) {
                val data = linkedMapOf<String, Any?>(
                    "schemaVersion" to 2, "jobId" to id, "phase" to phase,
                    "appVersion" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE,
                    "sourceRevision" to BuildConfig.SOURCE_REVISION, "generatedAtMillis" to System.currentTimeMillis(),
                    "phoneModel" to Build.MODEL, "apiLevel" to Build.VERSION.SDK_INT,
                    "osBuild" to Build.DISPLAY, "abis" to Build.SUPPORTED_ABIS.toList(),
                    "pageSize" to Os.sysconf(android.system.OsConstants._SC_PAGESIZE),
                    "format" to record.format, "sourcePageNumbers" to work.plan.pageIndices.map { it + 1 },
                    "copies" to work.plan.copies, "requestedSheets" to work.plan.outputPageOrder.size,
                    "orientation" to work.plan.orientation.name, "rotation" to work.plan.contentRotationDegrees,
                    "paper" to "A4", "printerVidPid" to "04A9:2676", "automaticRetry" to false,
                    "documentLayout" to work.plan.layout.name, "systemPrint" to work.systemMetadata,
                    "confirmedBeforeAttempt" to record.confirmed, "manualContinuation" to (record.confirmed > 0),
                    "failureClass" to failure, "result" to result?.let { mapOf(
                        "outcome" to it.outcome, "stage" to it.stage, "sentPrintDataBytes" to it.sentPrintDataBytes,
                        "printerConfirmedPages" to it.printerConfirmedPages, "lastStatusFrameHex" to it.lastStatusFrameHex,
                        "protocolDiagnostics" to it.protocolDiagnostics, "trace" to it.trace, "failureCode" to it.failureCode) })
                val directory = File(app.noBackupFilesDir, "print-reports").apply { mkdirs() }
                atomicWrite(reportFile(id), data)
                atomicWrite(FirstPdfPrintAttempt.reportFile(app), data)
                directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
            }
            try {
                report("PREPARING")
                progress(record.copy(phase = QueuePhase.PREPARING))
                val cache = work.prepared ?: HashMap<Int, StoredCaptPage>().also { prepared ->
                    for (page in work.plan.pageIndices) {
                        if (cancelled(id)) throw CancellationException()
                        prepared[page] = StoredCaptPage.write(File(work.directory, "page-$page.bin"),
                            produce = { accept -> requireNotNull(work.source).forEachPrintBand(page, work.plan.orientation,
                                work.plan.contentRotationDegrees, { cancelled(id) }, accept,
                                preservePdfLayout = work.plan.layout == DocumentLayout.ANDROID_PDF) },
                            encode = { pixels, stride, lines, last -> HiscoaNative.encodeBand(pixels, stride, lines, last) },
                            cancelled = { cancelled(id) }, reserve = { synchronized(this) { reserve(id, it) } })
                    }
                    work.prepared = prepared
                }
                cache.values.forEach { it.validate { cancelled(id) } }
                writeWork(work, record.confirmed, false) // Invalidate old resume intent before a new USB segment.
                if (cancelled(id)) throw CancellationException()
                change(id, QueuePhase.WAITING_PRINTER)
                report("USB_ATTEMPT_STARTED") // Hard precondition before any stateful USB operation.
                change(id, QueuePhase.TRANSMITTING, usbStarted = true)
                usb = true
                result = CaptDocumentPrintEngine.run(app, CaptPrinterMaintenance.deviceKey(app),
                    DocumentContinuation.remaining(work.plan, record.confirmed).map { cache.getValue(it) }, { cancelled(id) },
                    progress = { stage ->
                        val phase = if (stage.contains("counters agree")) QueuePhase.WAITING_CONFIRMATION
                                    else QueuePhase.TRANSMITTING
                        progress(snapshot().first { it.id == id }.copy(phase = phase))
                    }, checkpoint = { observed ->
                        result = observed.copy(printerConfirmedPages = DocumentContinuation.totalConfirmed(
                            record.confirmed, observed.printerConfirmedPages, record.sheets))
                        report("USB_ATTEMPT_STARTED")
                        change(id, QueuePhase.WAITING_CONFIRMATION, result!!.printerConfirmedPages)
                        progress(record.copy(phase = QueuePhase.WAITING_CONFIRMATION,
                            confirmed = result!!.printerConfirmedPages ?: record.confirmed))
                    })
                result = result!!.copy(printerConfirmedPages = DocumentContinuation.totalConfirmed(
                    record.confirmed, result!!.printerConfirmedPages, record.sheets))
                val final = when (result!!.outcome) {
                    "printer_counter_confirmed" -> QueuePhase.COUNTER_CONFIRMED
                    "cancelled" -> QueuePhase.CANCELLED
                    "waiting_for_paper" -> QueuePhase.WAITING_PAPER
                    "failed_before_print_commands" -> if (cancelled(id)) QueuePhase.CANCELLED else QueuePhase.ERROR
                    else -> QueuePhase.OUTCOME_UNKNOWN
                }
                failure = when {
                    final in setOf(QueuePhase.COUNTER_CONFIRMED, QueuePhase.CANCELLED) -> null
                    result!!.failureCode != null -> result!!.failureCode
                    result!!.trace.any { it.contains("not connected") } -> "PRINTER_DISCONNECTED"
                    result!!.trace.any { it.contains("not granted") } -> "USB_PERMISSION"
                    result!!.trace.any { it.contains("in use") } -> "USB_BUSY"
                    result!!.trace.any { it.contains("GetBasicStatus=0x") } -> "PRINTER_STATUS"
                    result!!.trace.any { it.contains("Timed out") } -> "USB_TIMEOUT"
                    else -> "PRINTER_ERROR"
                }
                if (final == QueuePhase.WAITING_PAPER) writeWork(work, result!!.printerConfirmedPages ?: record.confirmed, true)
                report(final.name)
                change(id, final, result!!.printerConfirmedPages, failure = failure)
            } catch (error: Throwable) {
                failure = error.javaClass.simpleName
                val phase = if (usb) QueuePhase.OUTCOME_UNKNOWN
                            else if (error is CancellationException) QueuePhase.CANCELLED else QueuePhase.ERROR
                runCatching { report(phase.name) }
                runCatching { change(id, phase, result?.printerConfirmedPages, failure = failure) }
            } finally {
                if (snapshot().firstOrNull { it.id == id }?.phase != QueuePhase.WAITING_PAPER) cleanup(id)
            }
        }
    }
}
