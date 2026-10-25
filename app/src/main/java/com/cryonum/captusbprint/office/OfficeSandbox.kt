package com.cryonum.captusbprint.office

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/** The UI owns a file broker; only the disposable :office installs seccomp. */
internal object OfficeSandbox {
    init { System.loadLibrary("capt_office_sandbox") }
    class Broker(private val handle: Long, val descriptor: ParcelFileDescriptor) : Closeable {
        fun attach(pid: Int) = attachNative(handle, pid)
        fun diagnostic() = deniedNative(handle)
        override fun close() {
            try { descriptor.close() } finally { stopNative(handle) }
        }
    }
    fun start(context: Context, directory: File): Broker {
        check(Application.getProcessName() == context.packageName)
        val values = startNative(directory.absolutePath, context.packageCodePath,
            context.applicationInfo.nativeLibraryDir)
            ?: throw OfficeConversionFailure(OfficeFailure.SANDBOX_UNAVAILABLE)
        return Broker(values[0], ParcelFileDescriptor.adoptFd(values[1].toInt()))
    }
    fun install(context: Context, descriptor: ParcelFileDescriptor, directory: File) {
        check(Application.getProcessName() == "${context.packageName}:office")
        // The descriptor lives until this one-shot process exits. Reusing it is forbidden.
        val fd = descriptor.detachFd()
        if (installNative(fd, directory.absolutePath) != 1)
            throw OfficeConversionFailure(OfficeFailure.SANDBOX_UNAVAILABLE)
    }
    private external fun startNative(directory: String, apk: String, libraries: String): LongArray?
    private external fun stopNative(handle: Long)
    private external fun attachNative(handle: Long, pid: Int)
    private external fun deniedNative(handle: Long): Int
    private external fun installNative(socket: Int, directory: String): Int
}
