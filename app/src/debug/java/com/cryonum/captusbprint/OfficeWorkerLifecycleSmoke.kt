package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.cryonum.captusbprint.office.OfficeConversionFailure
import com.cryonum.captusbprint.office.OfficeFailure
import com.cryonum.captusbprint.office.OfficeKind
import com.cryonum.captusbprint.office.OfficeOptions
import com.cryonum.captusbprint.office.OfficeWorkBudget
import com.cryonum.captusbprint.office.OfficeWorkerClient
import com.cryonum.captusbprint.office.OfficePrivateFiles
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android processes, fake renderer: lifecycle/resource tests, not Office compatibility. */
class OfficeWorkerLifecycleSmoke : Instrumentation() {
    private class UnexpectedFailure(val expected: String, val actual: String) : RuntimeException()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        // Instrumentation.start may race callApplicationOnCreate on older Android.
        waitForIdleSync()
        var stage = "start"
        var code = Activity.RESULT_CANCELED
        val result = Bundle()
        val context = targetContext
        val input = File(context.cacheDir, "office-worker-fault.bin")
        val previewMarker = File(context.cacheDir, "stage-c-inspection/live-preview-marker").apply {
            parentFile!!.mkdirs(); writeText("synthetic live preview")
        }
        val root = File(context.noBackupFilesDir, "office-work")
        val previous = root.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val workerPids = mutableSetOf<Int>()
        var readonlyWorkspace: File? = null
        val cleanupProbe = File(context.cacheDir,"office-cleanup-symlink-check")
        fun budget(time: Long = 12_000, rss: Long = 768L * 1024 * 1024,
                   disk: Long = 192L * 1024 * 1024) = OfficeWorkBudget(time, rss, disk, 8L * 1024 * 1024, 8192)
        fun run(mode: Int, cancelled: () -> Boolean = { false }, limit: OfficeWorkBudget = budget(),
                onStart: (Int) -> Unit = {}) = OfficeWorkerClient.prepare(context,
            input.apply { writeBytes(byteArrayOf(mode.toByte())) }, OfficeOptions(OfficeKind.DOCX), cancelled, limit,
            { pid -> check(pid != Process.myPid()); workerPids.add(pid); onStart(pid) }, OfficeFaultWorkerService::class.java)
        fun clean() {
            check((root.listFiles()?.map { it.name }?.toSet() ?: emptySet()) == previous)
            check(previewMarker.isFile)
            for (pid in workerPids) {
                val gone = try { Os.kill(pid, 0); false }
                catch (error: ErrnoException) { error.errno == OsConstants.ESRCH }
                check(gone) // Independently verify OS exit, not just a Binder notification.
            }
        }
        fun failure(expected: OfficeFailure, action: () -> Unit) {
            val error = runCatching(action).exceptionOrNull() ?: error("Expected failure")
            if (error !is OfficeConversionFailure || error.reason != expected)
                throw UnexpectedFailure(expected.name, (error as? OfficeConversionFailure)?.reason?.name ?: error.javaClass.simpleName)
            check(!error.toString().contains("PRIVATE_FAULT_DETAIL")); clean()
        }
        try {
            stage = "private-cleanup-symlinks"
            check(OfficePrivateFiles.deleteTree(cleanupProbe))
            val outside = File(cleanupProbe,"outside").apply { check(mkdirs()) }
            val sentinel = File(outside,"keep").apply { writeText("synthetic sentinel") }
            val owned = File(cleanupProbe,"owned").apply { check(mkdir()) }
            Os.symlink(outside.absolutePath,File(owned,"directory-link").absolutePath)
            Os.symlink(sentinel.absolutePath,File(owned,"file-link").absolutePath)
            check(OfficePrivateFiles.deleteTree(owned) && sentinel.readText()=="synthetic sentinel")
            check(OfficePrivateFiles.deleteTree(cleanupProbe))
            stage = "cancel-before-bind"
            failure(OfficeFailure.CANCELLED) { run(1, { true }).close() }
            stage = "success-and-independent-copy"
            run(6).use { prepared ->
                stage = "success-page-layout"
                check(prepared.session.overview.pageCount == 1 && prepared.session.preparedPdfLayout != null)
                stage = "success-memory-sample"
                check(prepared.peakObservedRssBytes > 0)
                stage = "success-owned-copy"
                clean() // Work directory is gone, while the prepared preview copy is still alive.
                stage = "success-preview"
                prepared.session.renderPreparedPrintPreview(0) { false }.recycle()
            }
            stage = "cancel-running"
            val cancel = AtomicBoolean(false)
            failure(OfficeFailure.CANCELLED) { run(1, { cancel.get() }, onStart = { cancel.set(true) }).close() }
            stage = "interrupt-running"
            failure(OfficeFailure.CANCELLED) {
                try { run(1, onStart = { Thread.currentThread().interrupt() }).close() }
                finally { Thread.interrupted() }
            }
            stage = "timeout"
            val started = SystemClock.elapsedRealtime()
            failure(OfficeFailure.TIME_LIMIT) { run(1, limit = budget(time = 4000)).close() }
            check(SystemClock.elapsedRealtime() - started < 10_000)
            stage = "worker-crash"
            failure(OfficeFailure.WORKER_CRASHED) { run(2).close() }
            stage = "memory-budget"
            failure(OfficeFailure.MEMORY_LIMIT) { run(1, limit = budget(rss = 1)).close() }
            stage = "disk-budget"
            failure(OfficeFailure.DISK_LIMIT) { run(3, limit = budget(disk = 1024 * 1024)).close() }
            stage = "fast-result-between-disk-observations"
            failure(OfficeFailure.DISK_LIMIT) {
                run(9, limit = budget(disk = 1024*1024), onStart = { Thread.sleep(2500) }).close()
            }
            stage = "free-space-reserve"
            failure(OfficeFailure.NO_SPACE) {
                run(6, limit = OfficeWorkBudget(12_000, 768L * 1024 * 1024,
                    192L * 1024 * 1024, Long.MAX_VALUE, 8192)).close()
            }
            stage = "engine-error-privacy"
            failure(OfficeFailure.ENGINE_FAILED) { run(4).close() }
            stage = "invalid-output"
            failure(OfficeFailure.INVALID_RESULT) { run(5).close() }
            stage = "non-A4-output"
            failure(OfficeFailure.INVALID_RESULT) { run(7).close() }
            stage = "cleanup-failure-with-unpublished-pdf"
            val inspected = File(context.cacheDir,"stage-c-inspection")
            val inspectedBefore = inspected.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val cleanupError = runCatching { run(8).use { check(it.session.overview.pageCount==1) } }.exceptionOrNull()
            readonlyWorkspace = root.listFiles()?.single { it.name !in previous }
            check(cleanupError is OfficeConversionFailure && cleanupError.reason==OfficeFailure.RESOURCE_OBSERVATION)
            check((inspected.listFiles()?.map { it.name }?.toSet() ?: emptySet())==inspectedBefore)
            Os.chmod(requireNotNull(readonlyWorkspace).absolutePath,0b111000000)
            check(OfficePrivateFiles.deleteTree(requireNotNull(readonlyWorkspace))); readonlyWorkspace=null
            clean()
            stage = "new-job-after-failures"
            run(6).use { check(it.session.overview.pageCount == 1) }
            clean(); check(workerPids.size >= 8)
            result.putString("stream", "CAPT_OFFICE_WORKER_LIFECYCLE_PASSED (fault backend, no USB)")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", "OFFICE_WORKER_LIFECYCLE_FAILED:$stage:${if (error is UnexpectedFailure) error.expected + ":" + error.actual else error.javaClass.simpleName}")
        } finally {
            readonlyWorkspace?.let { folder ->
                runCatching { Os.chmod(folder.absolutePath,0b111000000) }
                OfficePrivateFiles.deleteTree(folder)
            }
            OfficePrivateFiles.deleteTree(cleanupProbe)
            Thread.interrupted(); input.delete(); previewMarker.delete()
        }
        finish(code, result)
    }
}
