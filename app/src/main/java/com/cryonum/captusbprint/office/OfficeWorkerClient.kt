package com.cryonum.captusbprint.office

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.PrivateDocumentImport
import java.util.concurrent.CancellationException

/** Internal only: no SAF/Share route until the engine's external-resource policy is verified. */
internal object OfficeWorkerClient {
    val DEFAULT_BUDGET = OfficeWorkBudget(120_000, 768L * 1024 * 1024,
        192L * 1024 * 1024, 8L * 1024 * 1024, 8192)
    private val slot = Semaphore(1)

    fun prepare(context: Context, privateSource: File, options: OfficeOptions,
                cancelled: () -> Boolean = { false }, budget: OfficeWorkBudget = DEFAULT_BUDGET,
                onWorkerStarted: (Int) -> Unit = {},
                serviceClass: Class<out OfficeWorkerService> = OfficeWorkerService::class.java): OfficePreparedDocument {
        convert(context, privateSource, options, cancelled, budget, onWorkerStarted, serviceClass).use { raw ->
            try {
                val session = DocumentInspector.openPreparedPdf(context, raw.file, cancelled)
                return OfficePreparedDocument(session, raw.sheetNames, raw.workerPid, raw.peakObservedRssBytes)
            } catch (_: CancellationException) { throw OfficeConversionFailure(OfficeFailure.CANCELLED) }
            catch (error: PrivateDocumentImport.Failure) { throw OfficeConversionFailure(OfficeWorkerProtocol.failure(error)) }
            catch (_: Throwable) { throw OfficeConversionFailure(OfficeFailure.INVALID_RESULT) }
        }
    }

    fun convert(context: Context, privateSource: File, options: OfficeOptions,
                cancelled: () -> Boolean = { false }, budget: OfficeWorkBudget = DEFAULT_BUDGET,
                onWorkerStarted: (Int) -> Unit = {},
                serviceClass: Class<out OfficeWorkerService> = OfficeWorkerService::class.java): OfficeConvertedPdf {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (!slot.tryAcquire()) throw OfficeConversionFailure(OfficeFailure.BUSY)
        try { return Attempt(context.applicationContext, privateSource, options, cancelled, budget,
            onWorkerStarted, serviceClass).execute() }
        finally { slot.release() }
    }

    private class Attempt(val context: Context, val source: File, val options: OfficeOptions,
                          val cancelled: () -> Boolean, val budget: OfficeWorkBudget,
                          val onWorkerStarted: (Int) -> Unit, val serviceClass: Class<out OfficeWorkerService>) {
        val id = UUID.randomUUID().toString()
        val directory = OfficeWorkerProtocol.directory(context, id)
        val main = Handler(Looper.getMainLooper())
        val finished = CountDownLatch(1)
        val dead = CountDownLatch(1)
        val bindingStopped = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val pid = AtomicInteger(0)
        val remote = AtomicReference<IBinder?>()
        val failure = AtomicReference<OfficeFailure?>()
        val sheets = AtomicReference<List<String>>(emptyList())
        @Volatile var bound = false
        @Volatile var birth: String? = null
        var peakRss = 0L

        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != OfficeWorkerProtocol.RESULT) return super.onTransact(code, data, reply, flags)
                require(Binder.getCallingUid() == Process.myUid() && Binder.getCallingPid() == pid.get())
                data.enforceInterface(OfficeWorkerProtocol.DESCRIPTOR)
                require(data.readString() == id)
                val result = data.readInt()
                val count = data.readInt().also { require(it in 0..256) }
                val names = List(count) { requireNotNull(data.readString()).also { require(it.length <= 256) } }
                require(data.dataAvail() == 0)
                if (!closed.get() && finished.count > 0) {
                    if (result == -1) sheets.set(names)
                    else failure.compareAndSet(null, OfficeFailure.entries.getOrNull(result) ?: OfficeFailure.INVALID_RESULT)
                    finished.countDown()
                }
                reply?.writeNoException()
                return true
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(OfficeWorkerProtocol.DESCRIPTOR)
                    check(service.transact(OfficeWorkerProtocol.HELLO, data, reply, 0))
                    reply.readException()
                    val workerPid = reply.readInt()
                    check(workerPid > 0 && workerPid != Process.myPid() && reply.readInt() == Process.myUid())
                    check(reply.readString() == "${context.packageName}:office")
                    birth = processBirth(workerPid)
                    check(birth != null)
                    pid.set(workerPid)
                    remote.set(service)
                    service.linkToDeath({
                        if (finished.count > 0) fail(OfficeFailure.WORKER_CRASHED)
                        dead.countDown()
                    }, 0)
                    if (closed.get()) { kill(); return }
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        val request = Parcel.obtain()
                        try {
                            request.writeInterfaceToken(OfficeWorkerProtocol.DESCRIPTOR)
                            request.writeString(id); request.writeStrongBinder(callback)
                            descriptor.writeToParcel(request, 0); options.write(request)
                            check(service.transact(OfficeWorkerProtocol.START, request, null, IBinder.FLAG_ONEWAY))
                        } finally { request.recycle() }
                    }
                } catch (_: Throwable) { fail(OfficeFailure.ENGINE_UNAVAILABLE) }
                finally { data.recycle(); reply.recycle() }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (finished.count > 0) fail(OfficeFailure.WORKER_CRASHED)
                dead.countDown()
            }
            override fun onNullBinding(name: ComponentName) { fail(OfficeFailure.ENGINE_UNAVAILABLE) }
            override fun onBindingDied(name: ComponentName) { fail(OfficeFailure.WORKER_CRASHED); dead.countDown() }
        }

        fun fail(reason: OfficeFailure) { failure.compareAndSet(null, reason); finished.countDown() }

        fun execute(): OfficeConvertedPdf {
            var preserve = false
            val started = SystemClock.elapsedRealtime()
            try {
                if (cancelled() || Thread.currentThread().isInterrupted)
                    throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                if (options.fitWidth) throw OfficeConversionFailure(OfficeFailure.UNSUPPORTED_LAYOUT)
                val appRoot = File(context.applicationInfo.dataDir).canonicalFile.toPath()
                if (!source.canonicalFile.toPath().startsWith(appRoot) || !source.isFile ||
                    source.length() !in 1..OfficeWorkerProtocol.MAX_PDF_BYTES)
                    throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
                try { context.packageManager.getServiceInfo(ComponentName(context, serviceClass), 0) }
                catch (_: PackageManager.NameNotFoundException) { throw OfficeConversionFailure(OfficeFailure.ENGINE_UNAVAILABLE) }
                check(directory.mkdirs())
                budget.checkDisk(directory, directory.usableSpace)
                main.post {
                    if (closed.get()) return@post
                    try {
                        bound = context.bindService(Intent(context, serviceClass),
                            connection, Context.BIND_AUTO_CREATE)
                        if (!bound) fail(OfficeFailure.ENGINE_UNAVAILABLE)
                    } catch (_: Throwable) { fail(OfficeFailure.ENGINE_UNAVAILABLE) }
                }
                var observedPid = 0
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted)
                        throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                    budget.checkTime(SystemClock.elapsedRealtime() - started)
                    val workerPid = pid.get()
                    if (workerPid != 0 && observedPid == 0) {
                        observedPid = workerPid; onWorkerStarted(workerPid)
                    }
                    if (workerPid != 0 && dead.count > 0) {
                        // Same UID /proc reads are not ActivityManager's rate-limited PSS cache.
                        val rss = try { OfficeWorkBudget.parseRss(readStatus(workerPid)) }
                        catch (_: Throwable) {
                            // Give a just-crashed process's Binder death a bounded delivery window.
                            if (finished.await(100, TimeUnit.MILLISECONDS)) 0L
                            else if (remote.get()?.isBinderAlive == false || processBirth(workerPid) != birth)
                                throw OfficeConversionFailure(OfficeFailure.WORKER_CRASHED)
                            else throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION)
                        }
                        if (rss > 0) { budget.checkRss(rss); peakRss = maxOf(peakRss, rss) }
                    }
                    budget.checkDisk(directory, directory.usableSpace)
                    if (finished.await(100, TimeUnit.MILLISECONDS)) break
                }
                failure.get()?.let { throw OfficeConversionFailure(it) }
                if (cancelled()) throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                val pdf = File(directory, "result.pdf")
                if (Files.isSymbolicLink(pdf.toPath()) || !pdf.isFile ||
                    pdf.length() !in 1..OfficeWorkerProtocol.MAX_PDF_BYTES)
                    throw OfficeConversionFailure(OfficeFailure.INVALID_RESULT)
                preserve = true
                return OfficeConvertedPdf(pdf, sheets.get(), pid.get(), peakRss, directory)
            } catch (error: OfficeWorkBudget.Exceeded) {
                throw OfficeConversionFailure(OfficeWorkerProtocol.failure(error))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OfficeConversionFailure(OfficeFailure.CANCELLED)
            } finally {
                val interrupted = Thread.interrupted()
                closed.set(true)
                main.post {
                    try { if (bound) context.unbindService(connection) }
                    catch (_: IllegalArgumentException) { /* Binding already gone. */ }
                    finally { bound = false; bindingStopped.countDown() }
                }
                // Native code may be stuck; termination does not depend on it servicing Binder.
                kill()
                try {
                    val unbound = bindingStopped.await(2, TimeUnit.SECONDS)
                    val stopped = pid.get() == 0 || dead.await(2, TimeUnit.SECONDS) || processBirth(pid.get()) != birth
                    if (stopped && (!preserve || !unbound)) directory.deleteRecursively()
                    if (!unbound || !stopped) throw OfficeConversionFailure(OfficeFailure.WORKER_STOP_FAILED)
                } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
        }

        fun kill() {
            val workerPid = pid.get()
            if (workerPid > 0 && workerPid != Process.myPid() && birth != null &&
                dead.count > 0 && processBirth(workerPid) == birth) Process.killProcess(workerPid)
        }

        fun readBounded(file: File, max: Int): String = file.inputStream().use { stream ->
            val bytes = ByteArray(max)
            var total = 0
            while (total < max) {
                val count = stream.read(bytes, total, max - total)
                if (count <= 0) break
                total += count
            }
            String(bytes, 0, total, Charsets.US_ASCII)
        }

        fun readStatus(workerPid: Int): String = readBounded(File("/proc/$workerPid/status"), 16 * 1024)

        fun processBirth(workerPid: Int): String? = runCatching {
            val stat = readBounded(File("/proc/$workerPid/stat"), 4096)
            stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))[19]
        }.getOrNull()
    }
}
