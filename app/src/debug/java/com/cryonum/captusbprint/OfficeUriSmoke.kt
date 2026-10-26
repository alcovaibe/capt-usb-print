package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.cryonum.captusbprint.office.*
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Separate helper APK/UID, real Binder/provider/pipe/PID lifecycle; no USB or Office renderer claim. */
class OfficeUriSmoke : Instrumentation() {
    private var ownerDeath = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); ownerDeath = arguments?.getString("ownerDeath") == "true"; start() }
    override fun onStart() {
        waitForIdleSync()
        val context = targetContext
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var stage = "start"
        val authority = Uri.parse("content://com.cryonum.captusbprint.urifixture")
        val root = File(context.noBackupFilesDir, "office-work")
        fun work() = root.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val baseline = work()
        val grants = context.contentResolver.persistedUriPermissions.map { it.uri to it.isReadPermission }
        val queue = PrintQueueRuntime.snapshot()
        val pids = mutableSetOf<Int>()
        val uris = mutableSetOf<Uri>()
        var preparation: OfficePreviewPreparation? = null
        val uiDirectory = File(context.noBackupFilesDir, "office-ui/uri-synthetic-test")
        fun control(method: String, uri: Uri? = null): Bundle =
            requireNotNull(context.contentResolver.call(authority, method, uri?.toString(), null))
        fun granted(name: String): Uri = Uri.withAppendedPath(authority, name).also {
            uris.add(it); control("grant", it)
        }
        fun budget(time: Long = 12_000, rss: Long = 256L shl 20, disk: Long = (64L shl 20)+4096,
                   reserve: Long = 8L shl 20) = OfficeWorkBudget(time, rss, disk, reserve, 8)
        fun import(uri: Uri, cancelled: () -> Boolean = { false }, limits: OfficeWorkBudget = budget(),
                   onStart: (Int) -> Unit = {}) = OfficeWorkerClient.importSource(context, uri, cancelled, limits, {
            check(it != Process.myPid()); pids.add(it); onStart(it)
        })
        fun clean() {
            check(work() == baseline)
            check(PrintQueueRuntime.snapshot() == queue)
            check(context.contentResolver.persistedUriPermissions.map { it.uri to it.isReadPermission } == grants)
            for (pid in pids) {
                check(try { Os.kill(pid, 0); false } catch (error: ErrnoException) { error.errno == OsConstants.ESRCH })
            }
        }
        fun failure(expected: OfficeFailure, block: () -> Unit) {
            val error = runCatching(block).exceptionOrNull()
            check(error is OfficeConversionFailure && error.reason == expected) {
                "Expected ${expected.name}, got ${(error as? OfficeConversionFailure)?.reason?.name ?: error?.javaClass?.simpleName}"
            }
            check(!error.toString().contains("CAPT_URI_PRIVATE_DETAIL_57C3")); clean()
        }
        fun waitOpen(previous: Int) {
            val until = SystemClock.elapsedRealtime()+5000
            while (control("state").getInt("opens") == previous) {
                check(SystemClock.elapsedRealtime()<until); Thread.sleep(20)
            }
        }
        try {
            if (ownerDeath) {
                val previous = control("state").getInt("opens")
                import(granted("read-block"), onStart = { pid ->
                    waitOpen(previous)
                    File(context.filesDir, "office-uri-owner-pids.txt").writeText("${Process.myPid()} $pid")
                }).close()
                error("Owner-death probe must be stopped externally")
            }
            stage = "separate-uid"
            check(control("state").getInt("uid") != Process.myUid())
            stage = "main-thread-guard"
            runOnMainSync { check(runCatching { import(authority) }.exceptionOrNull() is IllegalStateException) }
            stage = "no-grant"
            val denied = Uri.withAppendedPath(authority, "word.docx")
            control("revoke", denied)
            failure(OfficeFailure.SOURCE_ACCESS) { import(denied).close() }
            stage = "content-detection-unknown-length-pipes"
            for (kind in OfficeKind.entries) {
                val name = "${if (kind.spreadsheet) "workbook" else "word"}.${kind.extension}"
                import(granted(name)).use {
                    check(it.importedKind == kind && it.workerPid in pids)
                    // Only fixed small test fixtures are read into RAM here.
                    check(it.file.readBytes().contentEquals(context.assets.open("office-fixtures/$name").use { input -> input.readBytes() }))
                }
                clean()
            }
            stage = "asset-offset-length"
            import(granted("slice")).use { check(it.importedKind == OfficeKind.DOCX) }; clean()
            stage = "revoked-grant"
            val revoked = granted("word.docx"); control("revoke", revoked)
            failure(OfficeFailure.SOURCE_ACCESS) { import(revoked).close() }
            stage = "provider-failure-privacy"
            failure(OfficeFailure.SOURCE_FAILED) { import(granted("failure")).close() }
            stage = "empty"
            failure(OfficeFailure.BAD_INPUT) { import(granted("empty")).close() }
            stage = "corrupt"
            failure(OfficeFailure.UNSUPPORTED_DOCUMENT) { import(granted("corrupt")).close() }
            stage = "strict-source-cap"
            failure(OfficeFailure.DOCUMENT_LIMIT) { import(granted("oversized")).close() }
            stage = "cancel-before-bind"
            val before = control("state").getInt("opens")
            failure(OfficeFailure.CANCELLED) { import(granted("word.docx"), { true }).close() }
            check(control("state").getInt("opens") == before)
            for (mode in listOf("open-block", "read-block")) {
                stage = "cancel-$mode"
                val cancel = AtomicBoolean(false)
                val previous = control("state").getInt("opens")
                val started = SystemClock.elapsedRealtime()
                failure(OfficeFailure.CANCELLED) {
                    import(granted(mode), { cancel.get() }, onStart = { waitOpen(previous); cancel.set(true) }).close()
                }
                check(SystemClock.elapsedRealtime()-started < 10_000)
                control("reset")
                stage = "timeout-$mode"
                val timed = SystemClock.elapsedRealtime()
                failure(OfficeFailure.TIME_LIMIT) { import(granted(mode), limits = budget(time = 4000)).close() }
                check(SystemClock.elapsedRealtime()-timed < 10_000)
                control("reset")
            }
            stage = "interrupt"
            failure(OfficeFailure.CANCELLED) {
                try { import(granted("read-block"), onStart = { Thread.currentThread().interrupt() }).close() }
                finally { Thread.interrupted() }
            }
            control("reset")
            stage = "worker-death"
            failure(OfficeFailure.WORKER_CRASHED) {
                import(granted("read-block"), onStart = { Process.killProcess(it) }).close()
            }
            control("reset")
            stage = "rss-before-provider"
            failure(OfficeFailure.MEMORY_LIMIT) { import(granted("word.docx"), limits = budget(rss=1)).close() }
            stage = "reserve-before-provider"
            failure(OfficeFailure.NO_SPACE) { import(granted("word.docx"), limits = budget(reserve=Long.MAX_VALUE)).close() }
            stage = "disk-cap"
            failure(OfficeFailure.DISK_LIMIT) { import(granted("word.docx"), limits = budget(disk=1)).close() }
            stage = "single-slot"
            import(granted("word.docx"), onStart = {
                val busy = runCatching { import(granted("word.docx")).close() }.exceptionOrNull()
                check(busy is OfficeConversionFailure && busy.reason == OfficeFailure.BUSY)
            }).close(); clean()
            stage = "unsupported-uri-scheme"
            failure(OfficeFailure.BAD_INPUT) { import(Uri.parse("file:///does-not-exist")).close() }
            stage = "private-snapshot-reuse-after-revoke"
            val uri = granted("workbook.xls")
            val state = AtomicReference<LatestPreparation.State<OfficePreviewMetadata, OfficeFailure>>()
            val workflow = OfficePreviewPreparation(context, OfficeKind.XLS, uiDirectory,
                { error("URI must not use the asset opener") }, OfficePreviewTestWorkerService::class.java, uri) { state.set(it) }
            preparation = workflow
            fun ready(version: Long) {
                val until = SystemClock.elapsedRealtime()+15_000
                while (true) {
                    val current = state.get()
                    if (current?.version == version && current.phase == LatestPreparation.Phase.READY) return
                    check(current?.phase != LatestPreparation.Phase.FAILED) { current?.failure?.name ?: "FAILED" }
                    check(SystemClock.elapsedRealtime()<until); Thread.sleep(20)
                }
            }
            ready(workflow.prepare(listOf(2,0), true))
            control("revoke", uri)
            val importedOpens = control("state").getInt("opens")
            ready(workflow.prepare(listOf(0), false))
            check(control("state").getInt("opens") == importedOpens)
            workflow.close(); preparation = null
            val until = SystemClock.elapsedRealtime()+5000
            while (uiDirectory.exists()) { check(SystemClock.elapsedRealtime()<until); Thread.sleep(20) }
            clean()
            stage = "success-after-failures"
            import(granted("word.doc")).use { check(it.importedKind == OfficeKind.DOC) }; clean()
            result.putString("stream", "CAPT_OFFICE_URI_LIFECYCLE_PASSED (separate UID, grants/pipes/cancellation/resources, no USB)")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "OFFICE_URI_FAILED:$stage:${(error as? OfficeConversionFailure)?.reason?.name ?: error.javaClass.simpleName}")
        } finally {
            preparation?.close()
            for (uri in uris) runCatching { control("revoke", uri) }
            runCatching { control("reset") }
            Thread.interrupted()
        }
        finish(code, result)
    }
}
