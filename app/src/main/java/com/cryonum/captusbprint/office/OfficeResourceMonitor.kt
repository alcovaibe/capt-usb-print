package com.cryonum.captusbprint.office

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

internal data class OfficeMemorySample(val total: Long, val available: Long, val threshold: Long, val low: Boolean)

/** Cross-root admission/observation. A changing system cannot promise a hard RAM/disk quota. */
internal class OfficeResourceMonitor(
    context: Context,
    private val requestedWorkerRss: Long = OfficeWorkerClient.DEFAULT_BUDGET.maxRssBytes,
    private val sample: () -> OfficeMemorySample = {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        OfficeMemorySample(memory.totalMem, memory.availMem, memory.threshold, memory.lowMemory)
    },
    private val freeBytes: () -> Long = { context.noBackupFilesDir.usableSpace },
    private val ownedBudget: OfficeWorkBudget = OfficeWorkBudget(120_000, Long.MAX_VALUE,
        OfficeResourcePolicy.OWNED_DISK_LIMIT, OfficeResourcePolicy.DISK_HEADROOM, 16_384)
) {
    private val roots = arrayOf(File(context.noBackupFilesDir, "office-work"),
        File(context.noBackupFilesDir, "office-ui"), File(context.cacheDir, "stage-c-inspection"),
        File(context.noBackupFilesDir, "print-queue"))
    private var observedAt = Long.MIN_VALUE
    private var ownedBytes = 0L

    fun before(growth: Long, cancelled: () -> Boolean = { false }) {
        check(0, cancelled, force = true)
        try {
            OfficeResourcePolicy.checkSpace(freeBytes(), growth)
            if (growth > ownedBudget.maxDiskBytes - ownedBytes)
                throw OfficeWorkBudget.Exceeded(OfficeWorkBudget.Limit.DISK)
        }
        catch (error: OfficeWorkBudget.Exceeded) { throw classified(error) }
    }

    fun check(workerRss: Long, cancelled: () -> Boolean, force: Boolean = false) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
        val now = SystemClock.elapsedRealtime()
        if (!force && observedAt != Long.MIN_VALUE && now - observedAt < 500) return
        try {
            val memory = sample()
            val mainRss = File("/proc/${Process.myPid()}/status").inputStream().use { stream ->
                val bytes = ByteArray(16 * 1024)
                val count = stream.read(bytes)
                if (count <= 0) throw IOException()
                OfficeWorkBudget.parseRss(String(bytes, 0, count, Charsets.US_ASCII))
            }
            OfficeResourcePolicy.checkMemory(memory.total, memory.available, memory.threshold, memory.low,
                mainRss, workerRss, requestedWorkerRss)
            ownedBytes = ownedBudget.checkDisk(roots, freeBytes()) { cancelled() }
            observedAt = now
        } catch (error: OfficeWorkBudget.Exceeded) { throw classified(error) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION) }
    }

    private fun classified(error: OfficeWorkBudget.Exceeded) =
        OfficeConversionFailure(OfficeWorkerProtocol.failure(error))
}
