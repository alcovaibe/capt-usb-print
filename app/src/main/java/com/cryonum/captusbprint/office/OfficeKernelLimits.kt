package com.cryonum.captusbprint.office

import android.app.Application
import android.content.Context
import android.os.Process
import java.io.File
import java.nio.file.Files

/** Irreversible SELF-process limits, only in disposable :office. Not an RSS/disk quota or sandbox. */
internal object OfficeKernelLimits {
    init { System.loadLibrary("capt_office_limits") }
    internal data class Limits(val fileBytes: Long = 64L * 1024 * 1024,
                               val cpuSeconds: Long = 90,
                               val addressGrowthBytes: Long = if (Process.is64Bit()) 1536L * 1024 * 1024 else 768L * 1024 * 1024)
    private const val EVENT = "kernel-limit-event"
    fun install(context: Context, directory: File, limits: Limits = Limits()) {
        check(Application.getProcessName() == "${context.packageName}:office")
        check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile)
        if (installNative(File(directory,EVENT).absolutePath,limits.fileBytes,limits.cpuSeconds,limits.addressGrowthBytes) != 1)
            throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION)
    }
    fun event(directory: File): OfficeFailure? {
        val file = File(directory,EVENT)
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() !in 4..5) return null
        return when (file.readText(Charsets.US_ASCII)) {
            "CPU\n" -> OfficeFailure.CPU_LIMIT
            "FILE\n" -> OfficeFailure.FILE_LIMIT
            else -> null
        }
    }
    fun snapshot(): LongArray = requireNotNull(snapshotNative()).also { check(it.size == 7) }
    fun deniedMapping(bytes: Long): Boolean = probeDeniedMappingNative(bytes)
    private external fun installNative(marker: String, fileBytes: Long, cpuSeconds: Long, addressGrowth: Long): Int
    private external fun snapshotNative(): LongArray?
    private external fun probeDeniedMappingNative(bytes: Long): Boolean
}
