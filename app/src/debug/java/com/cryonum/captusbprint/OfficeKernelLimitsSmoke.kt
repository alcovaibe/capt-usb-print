package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.cryonum.captusbprint.office.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Independent kernel failures in real disposable Android processes. No Office parsing/USB. */
class OfficeKernelLimitsSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        val result=Bundle(); var outcome=Activity.RESULT_CANCELED; var stage="main-guard"
        val context=targetContext
        val source=File(context.cacheDir,"office-kernel-source.bin").apply { writeBytes(byteArrayOf(6)) }
        val root=File(context.noBackupFilesDir,"office-work")
        val previous=root.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val before=OfficeKernelLimits.snapshot().take(6)
        try {
            check(runCatching { OfficeKernelLimits.install(context,context.cacheDir) }.exceptionOrNull() is IllegalStateException)
            check(OfficeKernelLimits.snapshot().take(6)==before)
            for ((name,service,expected) in listOf(
                Triple("file",OfficeFileLimitTestService::class.java,OfficeFailure.FILE_LIMIT),
                Triple("cpu",OfficeCpuLimitTestService::class.java,OfficeFailure.CPU_LIMIT),
                Triple("address",OfficeAddressLimitTestService::class.java,OfficeFailure.DOCUMENT_LIMIT),
                Triple("committed-memory",OfficeCommittedMemoryTestService::class.java,OfficeFailure.MEMORY_LIMIT))) {
                stage=name
                val pid=AtomicInteger()
                val error=runCatching { OfficeWorkerClient.prepare(context,source,OfficeOptions(OfficeKind.DOCX),
                    onWorkerStarted={pid.set(it)},serviceClass=service).close() }.exceptionOrNull()
                check(error is OfficeConversionFailure && error.reason==expected)
                check(pid.get()>0 && android.os.Process.myPid()!=pid.get())
                check((root.listFiles()?.map { it.name }?.toSet() ?: emptySet())==previous)
                check(OfficeKernelLimits.snapshot().take(6)==before)
            }
            stage="new-process-after-limits"
            OfficeWorkerClient.prepare(context,source,OfficeOptions(OfficeKind.DOCX),serviceClass=OfficeFaultWorkerService::class.java).use {
                check(it.session.overview.pageCount==1)
            }
            check(OfficeKernelLimits.snapshot().take(6)==before)
            check((root.listFiles()?.map { it.name }?.toSet() ?: emptySet())==previous)
            result.putString("stream","CAPT_OFFICE_KERNEL_LIMITS_PASSED (file/CPU/address denial, bounded committed ENOMEM, parent unchanged, next job succeeds; no USB)")
            outcome=Activity.RESULT_OK
        } catch(error:Throwable) {
            result.putString("stream","OFFICE_KERNEL_LIMITS_FAILED:$stage:${(error as? OfficeConversionFailure)?.reason?.name ?: error.javaClass.simpleName}")
        } finally { source.delete() }
        finish(outcome,result)
    }
}

/** Synthetic fault services retain no user URI and never call LibreOffice. */
abstract class OfficeKernelTestService : OfficeWorkerService() {
    internal override fun validateInput(input:File,options:OfficeOptions)=Unit
    internal override fun installKernelLimits(directory:File) = OfficeKernelLimits.install(this,directory,
        OfficeKernelLimits.Limits(fileBytes=4096,cpuSeconds=if(this is OfficeCpuLimitTestService) 1 else 5,addressGrowthBytes=32L*1024*1024))
    internal override fun newBackend()=object:OfficeWorkerBackend {
        override fun convert(context:android.content.Context,input:File,output:File,directory:File,options:OfficeOptions):List<String> {
            when(this@OfficeKernelTestService) {
                is OfficeFileLimitTestService -> File(directory,"oversize").outputStream().use { stream ->
                    val block=ByteArray(8192); while(true) stream.write(block)
                }
                is OfficeCpuLimitTestService -> { while(true) System.nanoTime() }
                is OfficeAddressLimitTestService -> {
                    check(OfficeKernelLimits.deniedMapping(64L*1024*1024))
                    throw OfficeConversionFailure(OfficeFailure.DOCUMENT_LIMIT)
                }
                is OfficeCommittedMemoryTestService -> {
                    val committed=OfficeKernelLimits.committedMemoryUntilDenied()
                    check(committed in 1..(64L*1024*1024))
                    throw OfficeConversionFailure(OfficeFailure.MEMORY_LIMIT)
                }
            }
            error("UNREACHABLE_KERNEL_PROBE")
        }
    }
}
class OfficeFileLimitTestService:OfficeKernelTestService()
class OfficeCpuLimitTestService:OfficeKernelTestService()
class OfficeAddressLimitTestService:OfficeKernelTestService()
class OfficeCommittedMemoryTestService:OfficeKernelTestService()
