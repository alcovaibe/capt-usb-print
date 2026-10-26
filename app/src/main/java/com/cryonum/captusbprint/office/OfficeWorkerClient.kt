package com.cryonum.captusbprint.office

import android.content.ComponentName
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.DocumentSession
import com.cryonum.captusbprint.document.PrivateDocumentImport
import java.util.concurrent.CancellationException

/** Internal only: no SAF/Share route until the engine's external-resource policy is verified. */
internal object OfficeWorkerClient {
    val DEFAULT_BUDGET = OfficeWorkBudget(120_000, 768L * 1024 * 1024,
        192L * 1024 * 1024, 8L * 1024 * 1024, 8192)
    private val slot = Semaphore(1)
    private val stopUncertain = AtomicBoolean(false)
    @Volatile private var startupClean = false

    /** Called once by the main Application, before any preview can own these roots. */
    fun cleanupAtStartup(context: Context) {
        check(Application.getProcessName() == context.packageName)
        startupClean = false
        // Never remove files from under a surviving worker after an owner crash.
        val children = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        if (children == null || children.any {
                it.uid == Process.myUid() && it.processName == "${context.packageName}:office"
            }) return
        startupClean = OfficePrivateFiles.deleteTree(File(context.noBackupFilesDir, "office-work")) &&
            OfficePrivateFiles.deleteTree(File(context.noBackupFilesDir, "office-ui"))
    }

    private fun requireCleanStartup() {
        if (stopUncertain.get()) throw OfficeConversionFailure(OfficeFailure.WORKER_STOP_FAILED)
        if (!startupClean) throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION,
            observation = OfficeObservation.STARTUP)
    }
    val IMPORT_BUDGET = OfficeWorkBudget(30_000, 256L * 1024 * 1024,
        PrivateDocumentImport.MAX_BYTES + 4096, PrivateDocumentImport.HEADROOM_BYTES, 8)

    /** Immediate private snapshot under the caller's current read grant. No persisted grant,
     * metadata query or provider work on main. Caller owns/ closes the returned directory. */
    fun importSource(context: Context, uri: Uri, cancelled: () -> Boolean = { false },
                     budget: OfficeWorkBudget = IMPORT_BUDGET,
                     onWorkerStarted: (Int) -> Unit = {}): OfficeWorkerOutput {
        check(Looper.myLooper() != Looper.getMainLooper())
        requireCleanStartup()
        if (uri.scheme != "content" || uri.authority.isNullOrEmpty() || uri.toString().length > 8192)
            throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
        if (!slot.tryAcquire()) throw OfficeConversionFailure(OfficeFailure.BUSY)
        try { return Attempt(context.applicationContext, null, null, cancelled, budget,
            onWorkerStarted, OfficeImportService::class.java, uri).execute() }
        finally { if (!stopUncertain.get()) slot.release() }
    }

    fun prepare(context: Context, privateSource: File, options: OfficeOptions,
                cancelled: () -> Boolean = { false }, budget: OfficeWorkBudget = DEFAULT_BUDGET,
                onWorkerStarted: (Int) -> Unit = {},
                serviceClass: Class<out OfficeWorkerService> = OfficeWorkerService::class.java): OfficePreparedDocument {
        var candidate: OfficePreparedDocument? = null
        var candidateSession: DocumentSession? = null
        try {
            convert(context, privateSource, options, cancelled, budget, onWorkerStarted, serviceClass).use { raw ->
                val session = DocumentInspector.openPreparedPdf(context, raw.file, cancelled)
                candidateSession = session
                candidate = OfficePreparedDocument(session, raw.sheetNames, raw.workerPid, raw.peakObservedRssBytes)
            }
            return requireNotNull(candidate)
        } catch (error: Throwable) {
            // A successful PDF copy is still unpublished if closing the workspace failed.
            runCatching { candidateSession?.close() }
            when (error) {
                is OfficeConversionFailure -> throw error
                is CancellationException -> throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                is PrivateDocumentImport.Failure -> throw OfficeConversionFailure(OfficeWorkerProtocol.failure(error))
                else -> throw OfficeConversionFailure(OfficeFailure.INVALID_RESULT)
            }
        }
    }

    fun convert(context: Context, privateSource: File, options: OfficeOptions,
                cancelled: () -> Boolean = { false }, budget: OfficeWorkBudget = DEFAULT_BUDGET,
                onWorkerStarted: (Int) -> Unit = {},
                serviceClass: Class<out OfficeWorkerService> = OfficeWorkerService::class.java): OfficeWorkerOutput {
        check(Looper.myLooper() != Looper.getMainLooper())
        requireCleanStartup()
        if (!slot.tryAcquire()) throw OfficeConversionFailure(OfficeFailure.BUSY)
        try { return Attempt(context.applicationContext, privateSource, options, cancelled, budget,
            onWorkerStarted, serviceClass).execute() }
        finally { if (!stopUncertain.get()) slot.release() }
    }

    private class Attempt(val context: Context, val source: File?, val options: OfficeOptions?,
                          val cancelled: () -> Boolean, val budget: OfficeWorkBudget,
                          val onWorkerStarted: (Int) -> Unit, val serviceClass: Class<out OfficeWorkerService>,
                          val uri: Uri? = null) {
        val id = UUID.randomUUID().toString()
        val directory = OfficeWorkerProtocol.directory(context, id)
        val main = Handler(Looper.getMainLooper())
        val finished = CountDownLatch(1)
        val dead = CountDownLatch(1)
        val bindingStopped = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val pid = AtomicInteger(0)
        val identityLock = Any()
        val remote = AtomicReference<IBinder?>()
        val connected = AtomicBoolean(false)
        val failure = AtomicReference<OfficeFailure?>()
        val sheets = AtomicReference<List<String>>(emptyList())
        val importedKind = AtomicReference<OfficeKind?>()
        @Volatile var bound = false
        @Volatile var birth: String? = null
        var peakRss = 0L
        var broker: OfficeSandbox.Broker? = null

        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != OfficeWorkerProtocol.RESULT) return super.onTransact(code, data, reply, flags)
                require(Binder.getCallingUid() == Process.myUid() && Binder.getCallingPid() == pid.get())
                data.enforceInterface(OfficeWorkerProtocol.DESCRIPTOR)
                require(data.readString() == id)
                val result = data.readInt()
                val count = data.readInt().also { require(it in 0..256) }
                val names = List(count) { requireNotNull(data.readString()).also { require(it.length <= 256) } }
                val kind = data.readInt().also { require(it == -1 || it in OfficeKind.entries.indices) }
                require(data.dataAvail() == 0)
                if (!closed.get() && finished.count > 0) {
                    if (result == -1) {
                        sheets.set(names)
                        importedKind.set(OfficeKind.entries.getOrNull(kind))
                    }
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
                    data.writeStrongBinder(callback)
                    check(service.transact(OfficeWorkerProtocol.HELLO, data, reply, 0))
                    reply.readException()
                    val workerPid = reply.readInt()
                    check(workerPid > 0 && workerPid != Process.myPid() && reply.readInt() == Process.myUid())
                    check(reply.readString() == "${context.packageName}:office")
                    val identity = processBirth(workerPid)
                    check(identity != null)
                    synchronized(identityLock) {
                        check(pid.get() == 0 || (pid.get() == workerPid && birth == identity))
                        birth = identity
                        pid.set(workerPid)
                    }
                    broker?.attach(workerPid)
                    remote.set(service)
                    service.linkToDeath({
                        if (finished.count > 0) fail(OfficeFailure.WORKER_CRASHED)
                        dead.countDown()
                    }, 0)
                    if (closed.get()) { kill(); return }
                    connected.set(true)
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

        fun execute(): OfficeWorkerOutput {
            var preserve = false
            val started = SystemClock.elapsedRealtime()
            try {
                if (cancelled() || Thread.currentThread().isInterrupted)
                    throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                if (uri == null) {
                    val local = requireNotNull(source)
                    val appRoot = File(context.applicationInfo.dataDir).canonicalFile.toPath()
                    if (!local.canonicalFile.toPath().startsWith(appRoot) || !local.isFile ||
                        local.length() !in 1..PrivateDocumentImport.MAX_BYTES)
                        throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
                }
                val serviceInfo = try {
                    context.packageManager.getServiceInfo(ComponentName(context, serviceClass), 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    throw OfficeConversionFailure(OfficeFailure.ENGINE_UNAVAILABLE)
                }
                if (serviceInfo.exported || !serviceInfo.enabled || serviceInfo.processName != "${context.packageName}:office")
                    throw OfficeConversionFailure(OfficeFailure.ENGINE_UNAVAILABLE)
                check(directory.mkdirs())
                budget.checkDisk(directory, directory.usableSpace)
                if (uri == null) broker = OfficeSandbox.start(context, directory)
                main.post {
                    if (closed.get()) return@post
                    try {
                        bound = context.bindService(Intent(context, serviceClass),
                            connection, Context.BIND_AUTO_CREATE)
                        if (!bound) fail(OfficeFailure.ENGINE_UNAVAILABLE)
                    } catch (_: Throwable) { fail(OfficeFailure.ENGINE_UNAVAILABLE) }
                }
                var dispatched = false
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted)
                        throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                    budget.checkTime(SystemClock.elapsedRealtime() - started)
                    val workerPid = pid.get()
                    observeRss()
                    budget.checkDisk(directory, directory.usableSpace)
                    if (connected.get() && workerPid != 0 && !dispatched && finished.count > 0) {
                        // Observe resident memory before any backend can finish a fast conversion.
                        check(peakRss > 0)
                        dispatch(requireNotNull(remote.get()))
                        dispatched = true
                        onWorkerStarted(workerPid)
                    }
                    if (finished.await(100, TimeUnit.MILLISECONDS)) break
                }
                failure.get()?.let { throw OfficeConversionFailure(it) }
                if (cancelled()) throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                // A quick callback may arrive during await, after the last observation.
                budget.checkTime(SystemClock.elapsedRealtime() - started)
                budget.checkDisk(directory, directory.usableSpace)
                observeRss()
                val pdf = File(directory, if (uri == null) "result.pdf" else "source.bin")
                if (Files.isSymbolicLink(pdf.toPath()) || !pdf.isFile ||
                    pdf.length() !in 1..OfficeWorkerProtocol.MAX_PDF_BYTES)
                    throw OfficeConversionFailure(OfficeFailure.INVALID_RESULT)
                if (uri != null && importedKind.get() == null)
                    throw OfficeConversionFailure(OfficeFailure.INVALID_RESULT)
                preserve = true
                return OfficeWorkerOutput(pdf, sheets.get(), pid.get(), peakRss, directory, importedKind.get())
            } catch (error: OfficeWorkBudget.Exceeded) {
                throw OfficeConversionFailure(OfficeWorkerProtocol.failure(error),
                    observation = if (error.limit == OfficeWorkBudget.Limit.OBSERVATION) OfficeObservation.DISK else null)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OfficeConversionFailure(OfficeFailure.CANCELLED)
            } catch (error: OfficeConversionFailure) {
                val limit = if (error.reason == OfficeFailure.WORKER_CRASHED)
                    runCatching { OfficeKernelLimits.event(directory) }.getOrNull() else null
                if (limit != null) throw OfficeConversionFailure(limit)
                throw error
            } catch (_: IOException) {
                throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION,
                    observation = OfficeObservation.WORKSPACE_IO)
            } catch (_: SecurityException) {
                throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
            } finally {
                var interrupted = Thread.interrupted()
                closed.set(true)
                main.post {
                    try { if (bound) context.unbindService(connection) }
                    catch (_: IllegalArgumentException) { /* Binding already gone. */ }
                    finally { bound = false; bindingStopped.countDown() }
                }
                // Native code may be stuck; termination does not depend on it servicing Binder.
                kill()
                fun awaitCleanup(latch: CountDownLatch): Boolean {
                    val until = SystemClock.elapsedRealtime() + 2000
                    while (latch.count > 0) {
                        val left = until - SystemClock.elapsedRealtime()
                        if (left <= 0) return false
                        try { return latch.await(left, TimeUnit.MILLISECONDS) }
                        catch (_: InterruptedException) { interrupted = true }
                    }
                    return true
                }
                try {
                    val unbound = awaitCleanup(bindingStopped)
                    // A dead service Binder alone does not prove that the Linux process exited.
                    val until = SystemClock.elapsedRealtime() + 2000
                    var stopped = processStopped()
                    while (!stopped && SystemClock.elapsedRealtime() < until) {
                        try { Thread.sleep(25) } catch (_: InterruptedException) { interrupted = true }
                        stopped = processStopped()
                    }
                    var finalFailure: OfficeFailure? = null
                    var sandboxCategory = 0
                    if (preserve && stopped && unbound) {
                        // Native background threads can still write after the success callback.
                        // Only a stopped process makes the last workspace measurement stable.
                        try { budget.checkDisk(directory, directory.usableSpace) }
                        catch (error: OfficeWorkBudget.Exceeded) { finalFailure = OfficeWorkerProtocol.failure(error) }
                        catch (_: IOException) { finalFailure = OfficeFailure.RESOURCE_OBSERVATION }
                        catch (_: SecurityException) { finalFailure = OfficeFailure.RESOURCE_OBSERVATION }
                        try { budget.checkTime(SystemClock.elapsedRealtime() - started) }
                        catch (error: OfficeWorkBudget.Exceeded) { finalFailure = OfficeWorkerProtocol.failure(error) }
                        sandboxCategory = broker?.diagnostic() ?: 0
                        if (sandboxCategory != 0) finalFailure = OfficeFailure.EXTERNAL_RESOURCE
                        if (finalFailure != null) preserve = false
                    }
                    val cleaned = !stopped || (preserve && unbound) || OfficePrivateFiles.deleteTree(directory)
                    if (!unbound || !stopped) {
                        // Never bind a new request to an unverified surviving native process.
                        stopUncertain.set(true)
                        throw OfficeConversionFailure(OfficeFailure.WORKER_STOP_FAILED)
                    }
                    if (!cleaned) throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION,
                        observation = OfficeObservation.CLEANUP)
                    if (preserve && (cancelled() || interrupted)) {
                        if (!OfficePrivateFiles.deleteTree(directory))
                            throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION,
                                observation = OfficeObservation.CLEANUP)
                        throw OfficeConversionFailure(OfficeFailure.CANCELLED)
                    }
                    finalFailure?.let { throw OfficeConversionFailure(it, sandboxCategory,
                        if (it == OfficeFailure.RESOURCE_OBSERVATION) OfficeObservation.STOPPED_DISK else null) }
                } finally {
                    broker?.close(); broker = null
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }

        fun observeRss() {
            val workerPid = pid.get()
            if (workerPid == 0 || dead.count == 0L) return
            // Same UID /proc reads are not ActivityManager's rate-limited PSS cache.
            val rss = try { OfficeWorkBudget.parseRss(readStatus(workerPid)) }
            catch (_: Throwable) {
                // Give a just-crashed process's Binder death a bounded delivery window.
                if (finished.await(100, TimeUnit.MILLISECONDS)) 0L
                else if (remote.get()?.isBinderAlive == false || processBirth(workerPid) != birth)
                    throw OfficeConversionFailure(OfficeFailure.WORKER_CRASHED)
                else throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION,
                    observation = OfficeObservation.RSS)
            }
            if (rss > 0) { budget.checkRss(rss); peakRss = maxOf(peakRss, rss) }
        }

        fun dispatch(service: IBinder) {
            if (uri != null) {
                val request = Parcel.obtain()
                try {
                    request.writeInterfaceToken(OfficeWorkerProtocol.DESCRIPTOR)
                    request.writeString(id); request.writeStrongBinder(callback)
                    uri.writeToParcel(request, 0)
                    check(service.transact(OfficeWorkerProtocol.IMPORT, request, null, IBinder.FLAG_ONEWAY))
                } finally { request.recycle() }
                return
            }
            ParcelFileDescriptor.open(requireNotNull(source), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val request = Parcel.obtain()
                try {
                    request.writeInterfaceToken(OfficeWorkerProtocol.DESCRIPTOR)
                    request.writeString(id); request.writeStrongBinder(callback)
                    descriptor.writeToParcel(request, 0)
                    requireNotNull(broker).descriptor.writeToParcel(request, 0)
                    requireNotNull(options).write(request)
                    check(service.transact(OfficeWorkerProtocol.START, request, null, IBinder.FLAG_ONEWAY))
                } finally { request.recycle() }
            }
        }

        fun kill() {
            val workerPid = pid.get()
            if (workerPid > 0 && workerPid != Process.myPid() && birth != null &&
                processBirth(workerPid) == birth) Process.killProcess(workerPid)
        }

        fun processStopped(): Boolean {
            val workerPid = pid.get()
            if (workerPid == 0) {
                // Cancellation can win before HELLO. Unbinding alone does not prove that
                // a just-created service process has exited. Android can cache this PID
                // without ever delivering onServiceConnected/onDestroy. Authenticate that
                // exact app-owned child through AM, proc owner, cmdline and creation time;
                // only then terminate it. Never treat missing HELLO as permission to delete.
                val children = context.getSystemService(ActivityManager::class.java).runningAppProcesses
                    ?: return false
                val candidates = children.filter { it.uid == Process.myUid() &&
                    it.processName == "${context.packageName}:office" && it.pid != Process.myPid() }
                if (candidates.isEmpty()) return true
                for (child in candidates) {
                    val identity = processBirth(child.pid) ?: continue
                    val verified = runCatching {
                        Os.stat("/proc/${child.pid}").st_uid == Process.myUid() &&
                            readBounded(File("/proc/${child.pid}/cmdline"), 512).substringBefore('\u0000') ==
                            "${context.packageName}:office" && processBirth(child.pid) == identity
                    }.getOrDefault(false)
                    if (verified) {
                        synchronized(identityLock) {
                            if (pid.get() == 0) { birth = identity; pid.set(child.pid) }
                        }
                        kill()
                        return false // A signal is not proof of exit; next observation verifies it.
                    }
                }
                return false
            }
            val currentBirth = processBirth(workerPid)
            if (currentBirth != null && currentBirth != birth) return true
            return try { Os.kill(workerPid, 0); false }
            catch (error: ErrnoException) { error.errno == OsConstants.ESRCH }
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
