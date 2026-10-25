package com.cryonum.captusbprint.office

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.cryonum.captusbprint.document.PrivateDocumentImport
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One native job per process. Separate UID/filesystem isolation is NOT claimed. */
open class OfficeWorkerService : Service() {
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != OfficeWorkerProtocol.HELLO && code != OfficeWorkerProtocol.START)
                return super.onTransact(code, data, reply, flags)
            require(Binder.getCallingUid() == Process.myUid())
            data.enforceInterface(OfficeWorkerProtocol.DESCRIPTOR)
            if (code == OfficeWorkerProtocol.HELLO) {
                requireNotNull(reply).apply {
                    writeNoException(); writeInt(Process.myPid()); writeInt(Process.myUid())
                    writeString(Application.getProcessName())
                }
            } else {
                val id = requireNotNull(data.readString())
                val callback = requireNotNull(data.readStrongBinder())
                val source = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                var sandbox: ParcelFileDescriptor? = null
                try {
                    val channel = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    sandbox = channel
                    val options = OfficeOptions.read(data)
                    require(data.dataAvail() == 0)
                    if (!started.compareAndSet(false, true)) {
                        source.close(); channel.close(); report(callback, id, OfficeFailure.BUSY, emptyList())
                    } else executor.execute { runConversion(id, source, channel, options, callback) }
                } catch (_: Throwable) {
                    source.close(); sandbox?.close(); report(callback, id, OfficeFailure.BAD_INPUT, emptyList())
                }
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        check(Application.getProcessName() == "$packageName:office")
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private fun runConversion(id: String, source: ParcelFileDescriptor, sandbox: ParcelFileDescriptor, options: OfficeOptions,
                              callback: IBinder) {
        var failure: OfficeFailure? = null
        var sheets = emptyList<String>()
        try {
            val directory = OfficeWorkerProtocol.directory(this, id)
            check(directory.isDirectory)
            installKernelLimits(directory)
            val imported = PrivateDocumentImport.copy(directory,
                { ParcelFileDescriptor.AutoCloseInputStream(source) }, { false }, { directory.usableSpace })
            val input = File(directory, "source.${options.kind.extension}")
            check(imported.renameTo(input))
            validateInput(input, options)
            val backend = newBackend()
            OfficeSandbox.prepareEnvironment(this, directory)
            // Only trusted code/resources are loaded here; no native document import yet.
            backend.preload()
            OfficeSandbox.install(this, sandbox, directory)
            sheets = backend.convert(this, input, File(directory, "result.pdf"), directory, options)
            check(sheets.size <= 256 && sheets.all { it.length <= 256 })
        } catch (error: Throwable) {
            failure = OfficeWorkerProtocol.failure(error)
        } finally {
            runCatching { source.close() }
            runCatching { sandbox.close() }
        }
        report(callback, id, failure, sheets)
    }

    internal open fun installKernelLimits(directory: File) = OfficeKernelLimits.install(this,directory)

    internal open fun validateInput(input: File, options: OfficeOptions) {
        val actual = OfficeAdmission.inspect(input) { Thread.currentThread().isInterrupted }
        if (actual.name != options.kind.name)
            throw OfficeConversionFailure(OfficeFailure.UNSUPPORTED_DOCUMENT)
    }

    internal open fun newBackend(): OfficeWorkerBackend =
        Class.forName(OfficeWorkerProtocol.BACKEND).getDeclaredConstructor().newInstance() as OfficeWorkerBackend

    private fun report(callback: IBinder, id: String, failure: OfficeFailure?, sheets: List<String>) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(OfficeWorkerProtocol.DESCRIPTOR)
            data.writeString(id); data.writeInt(failure?.ordinal ?: -1)
            data.writeInt(if (failure == null) sheets.size else 0)
            if (failure == null) sheets.forEach { data.writeString(it) }
            // Synchronous callback retains a verifiable Binder calling PID.
            callback.transact(OfficeWorkerProtocol.RESULT, data, reply, 0)
        } catch (_: Throwable) { /* No document/provider exception text in logs. */ }
        finally { data.recycle(); reply.recycle() }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
        // Native LOK is deliberately never reused after a completed/failed/cancelled job.
        if (Application.getProcessName() == "$packageName:office") Process.killProcess(Process.myPid())
    }
}
