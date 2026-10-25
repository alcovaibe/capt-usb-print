package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.cryonum.captusbprint.office.*
import java.io.File

/** Actual kernel restrictions, same UID as queue. No user URI, USB or native Office. */
class OfficeSandboxSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        val context=targetContext;val result=Bundle();var stage="start"
        var code=Activity.RESULT_CANCELED
        val secret=File(context.noBackupFilesDir,"office-sandbox-secret")
        val input=File(context.cacheDir,"office-sandbox-source")
        val work=File(context.noBackupFilesDir,"office-work")
        val before=work.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        try {
            secret.writeText("CAPT_PRIVATE_DOCUMENT_SENTINEL_63B1")
            input.writeBytes(byteArrayOf(1))
            stage="parent-guard"
            android.os.ParcelFileDescriptor.open(input,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                check(runCatching { OfficeSandbox.install(context,fd,context.cacheDir) }.isFailure)
            }
            stage="worker-outside-denial"
            val error=runCatching { OfficeWorkerClient.prepare(context,input,OfficeOptions(OfficeKind.DOCX),
                serviceClass=OfficeSandboxTestService::class.java).close() }.exceptionOrNull()
            check(error is OfficeConversionFailure && error.reason==OfficeFailure.EXTERNAL_RESOURCE)
            check(secret.readText()=="CAPT_PRIVATE_DOCUMENT_SENTINEL_63B1")
            check((work.listFiles()?.map { it.name }?.toSet() ?: emptySet())==before)
            stage="new-normal-worker"
            input.writeBytes(byteArrayOf(6))
            OfficeWorkerClient.prepare(context,input,OfficeOptions(OfficeKind.DOCX),
                serviceClass=OfficeFaultWorkerService::class.java).use { check(it.session.overview.pageCount==1) }
            check((work.listFiles()?.map { it.name }?.toSet() ?: emptySet())==before)
            // Guarding the worker must not remove INTERNET or disable the main updater.
            check(context.packageManager.getPackageInfo(context.packageName,
                android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions?.contains("android.permission.INTERNET")==true)
            result.putString("stream","CAPT_OFFICE_SANDBOX_ANDROID_PASSED (same-UID file denial, whole job rejected, logging attempts, next job; no USB)")
            code=Activity.RESULT_OK
        } catch(error:Throwable) {
            result.putString("stream","OFFICE_SANDBOX_FAILED:$stage:${(error as? OfficeConversionFailure)?.reason?.name ?: error.javaClass.simpleName}")
        } finally { input.delete();secret.delete() }
        finish(code,result)
    }
}

class OfficeSandboxTestService : OfficeWorkerService() {
    internal override fun validateInput(input:File,options:OfficeOptions)=Unit
    internal override fun newBackend()=object:OfficeWorkerBackend {
        override fun convert(context:Context,input:File,output:File,directory:File,options:OfficeOptions):List<String> {
            val secret=File(context.noBackupFilesDir,"office-sandbox-secret")
            check(runCatching { secret.readText() }.isFailure)
            check(runCatching { secret.writeText("changed") }.isFailure)
            check(runCatching { Os.open(secret.absolutePath,OsConstants.O_RDONLY,0) }.isFailure)
            check(runCatching { Os.symlink(secret.absolutePath,File(directory,"link").absolutePath) }.isFailure)
            val token="CAPT_OFFICE_PRIVATE_LOG_SENTINEL_933D"
            Log.v("OfficePrivacy",token);Log.d("OfficePrivacy",token);Log.i("OfficePrivacy",token)
            Log.w("OfficePrivacy",token);Log.e("OfficePrivacy",token);System.err.println(token);System.out.println(token)
            val own=File(directory,"own");own.writeText("local");check(own.readText()=="local");check(own.delete())
            val pdf=PdfDocument()
            try {
                pdf.finishPage(pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create()))
                output.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            check(Process.myPid()>0)
            return emptyList()
        }
    }
}
