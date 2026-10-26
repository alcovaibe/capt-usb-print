package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.cryonum.captusbprint.office.*
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.document.DocumentInspector
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded real files/processes + explicitly injected system/errno states. Never fill device RAM/disk. */
class OfficeResourceStressSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        var stage="start";var code=Activity.RESULT_CANCELED
        val result=Bundle();val context=targetContext
        val source=File(context.cacheDir,"office-resource-source.bin")
        val ui=File(context.noBackupFilesDir,"office-ui/resource-stress")
        val preview=File(context.cacheDir,"stage-c-inspection/resource-stress")
        val root=File(context.noBackupFilesDir,"office-work")
        val copy=File(context.cacheDir,"office-resource-copy")
        fun workers()=root.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        fun queueFolders()=File(context.noBackupFilesDir,"print-queue").listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val before=workers();val queue=PrintQueueRuntime.snapshot()
        val queueBefore=queueFolders()
        val healthy=OfficeMemorySample(8L shl 30,4L shl 30,128L shl 20,false)
        fun failed(expected:OfficeFailure, mode:Int=6, monitor:OfficeResourceMonitor?=null,
                   started:(Int)->Unit={}) {
            source.writeBytes(byteArrayOf(mode.toByte()))
            val pid=AtomicInteger()
            val error=runCatching {
                OfficeWorkerClient.prepare(context,source,OfficeOptions(OfficeKind.DOCX),
                    onWorkerStarted={ pid.set(it);started(it) },serviceClass=OfficeFaultWorkerService::class.java,
                    resourceMonitor=monitor ?: OfficeResourceMonitor(context)).close()
            }.exceptionOrNull()
            check(error is OfficeConversionFailure && error.reason==expected) {
                "RESOURCE_RESULT_${(error as? OfficeConversionFailure)?.reason?.name ?: error?.javaClass?.simpleName}"
            }
            check(!error.toString().contains("PRIVATE_RESOURCE"))
            check(workers()==before && PrintQueueRuntime.snapshot()==queue)
            check(queueFolders()==queueBefore)
            if(pid.get()!=0) {
                check(pid.get()!=Process.myPid())
                check(try { Os.kill(pid.get(),0);false } catch(e:ErrnoException) { e.errno==OsConstants.ESRCH })
            }
        }
        try {
            stage="system-low-before-bind"
            var called=false
            failed(OfficeFailure.MEMORY_LIMIT,monitor=OfficeResourceMonitor(context,sample={healthy.copy(low=true)})) { called=true }
            check(!called)
            stage="free-space-growth-before-bind"
            failed(OfficeFailure.NO_SPACE,monitor=OfficeResourceMonitor(context,sample={healthy},
                freeBytes={OfficeResourcePolicy.CONVERSION_GROWTH+OfficeResourcePolicy.DISK_HEADROOM-1}))
            stage="aggregate-private-roots"
            check(ui.mkdirs());check(preview.mkdirs())
            File(ui,"source").writeBytes(ByteArray(4096));File(preview,"pdf").writeBytes(ByteArray(4096))
            val roots=arrayOf(File(context.noBackupFilesDir,"office-ui"),
                File(context.cacheDir,"stage-c-inspection"),File(context.noBackupFilesDir,"print-queue"),root)
            val baseline=OfficeWorkBudget(120_000,Long.MAX_VALUE,Long.MAX_VALUE,0,16_384)
                .checkDisk(roots,Long.MAX_VALUE) { false }
            failed(OfficeFailure.DISK_LIMIT,monitor=OfficeResourceMonitor(context,sample={healthy},
                ownedBudget=OfficeWorkBudget(120_000,Long.MAX_VALUE,baseline-4096,0,16_384)))
            check(OfficePrivateFiles.deleteTree(ui) && OfficePrivateFiles.deleteTree(preview))
            stage="pressure-after-worker-start"
            val pressure=AtomicBoolean(false)
            failed(OfficeFailure.MEMORY_LIMIT,mode=1,monitor=OfficeResourceMonitor(context,
                sample={healthy.copy(low=pressure.get())})) { pressure.set(true) }
            stage="typed-resource-errno"
            for((mode,expected) in listOf(11 to OfficeFailure.NO_SPACE,12 to OfficeFailure.NO_SPACE,
                    13 to OfficeFailure.MEMORY_LIMIT,14 to OfficeFailure.FILE_LIMIT)) failed(expected,mode)
            stage="success-after-resource-failures"
            source.writeBytes(byteArrayOf(6))
            OfficeWorkerClient.prepare(context,source,OfficeOptions(OfficeKind.DOCX),
                serviceClass=OfficeFaultWorkerService::class.java).use { ready ->
                check(ready.session.overview.pageCount==1)
                check(copy.mkdir())
                ready.session.copyForPrint(copy) {}.use {
                    val inspection=File(context.cacheDir,"stage-c-inspection")
                    fun inspected()=inspection.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                    val names=inspected()
                    for(reason in listOf(OfficeFailure.NO_SPACE,OfficeFailure.MEMORY_LIMIT)) {
                        stage="resource-during-prepared-copy-${reason.name}"
                        var polls=0
                        val error=runCatching {
                            DocumentInspector.openPreparedPdfChecked(context,File(copy,"source.bin"),{false}) {
                                if(++polls==6)throw OfficeConversionFailure(reason)
                            }.close()
                        }.exceptionOrNull()
                        check(error is OfficeConversionFailure && error.reason==reason && polls==6)
                        check(inspected()==names)
                    }
                }
                check(copy.delete())
                stage="resource-during-held-copy"
                val plan=JobSpecification.preparedPdf(requireNotNull(ready.session.preparedPdfLayout).orientations,"1",1)
                for(reason in listOf(OfficeFailure.NO_SPACE,OfficeFailure.MEMORY_LIMIT,OfficeFailure.CANCELLED)) {
                    var polls=0
                    val error=runCatching {
                        PrintQueueRuntime.enqueueChecked(context,ready.session,plan,"offline-resource-no-usb") {
                            if(++polls==3) {
                                if(reason==OfficeFailure.CANCELLED)throw CancellationException()
                                throw OfficeConversionFailure(reason)
                            }
                        }
                    }.exceptionOrNull()
                    check(polls==3 && if(reason==OfficeFailure.CANCELLED)error is CancellationException
                        else error is OfficeConversionFailure && error.reason==reason)
                    check(PrintQueueRuntime.snapshot()==queue)
                    check(queueFolders()==queueBefore)
                }
                ready.session.renderPreparedPrintPreview(0) { false }.recycle()
            }
            check(workers()==before && PrintQueueRuntime.snapshot()==queue)
            result.putString("stream","CAPT_OFFICE_RESOURCE_STRESS_PASSED (shared roots, growth reserve, injected system pressure/errno, process exit/cleanup/next job; no USB)")
            code=Activity.RESULT_OK
        } catch(error:Throwable) {
            result.putString("stream","OFFICE_RESOURCE_STRESS_FAILED:$stage:${(error as? OfficeConversionFailure)?.reason?.name ?: error.javaClass.simpleName}")
        } finally { OfficePrivateFiles.deleteTree(ui);OfficePrivateFiles.deleteTree(preview);OfficePrivateFiles.deleteTree(copy);source.delete() }
        finish(code,result)
    }
}
