package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.core.content.FileProvider
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.PrivateDocumentImport
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import com.cryonum.captusbprint.office.OfficeAdmissionChecks
import com.cryonum.captusbprint.office.OfficeConversionFailure
import com.cryonum.captusbprint.office.OfficeFailure
import com.cryonum.captusbprint.office.OfficeKind
import com.cryonum.captusbprint.office.OfficeOptions
import com.cryonum.captusbprint.office.OfficeWorkerClient
import java.io.File
import java.util.concurrent.CancellationException

/** Rejection/cleanup contract, explicitly NOT a claim of Office rendering support. No USB. */
class OfficeIntakeSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            waitForIdleSync()
            val context = targetContext
            val privateDir = File(context.cacheDir, "stage-c-inspection")
            fun sources() = privateDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val before = sources()
            val queueBefore = PrintQueueRuntime.snapshot()
            val directory = File(context.cacheDir, "reports/office-intake-smoke").apply { mkdirs() }
            try {
                val fixtures = File(directory, "fixtures").apply { mkdirs() }
                val workRoot = File(context.noBackupFilesDir, "office-work")
                fun workFiles() = workRoot.listFiles()?.map { it.name }?.toSet() ?: emptySet()
                val workBefore = workFiles()
                fun rejectedByWorker(source: File, kind: OfficeKind, expected: OfficeFailure) {
                    val error = runCatching {
                        OfficeWorkerClient.prepare(context, source, OfficeOptions(kind),
                            serviceClass = OfficeAdmissionWorkerService::class.java).close()
                    }.exceptionOrNull()
                    check(error is OfficeConversionFailure && error.reason == expected)
                    check(workFiles() == workBefore)
                    check(sources() == before)
                    check(PrintQueueRuntime.snapshot() == queueBefore)
                }
                for (name in listOf("word.docx", "word.doc", "workbook.xls", "workbook.xlsx",
                    "word-flow.docx", "word-flow.doc", "word-fonts.docx", "word-fonts.doc",
                    "workbook-edge.xlsx", "workbook-edge.xls")) {
                    val source = File(fixtures, name)
                    context.assets.open("office-fixtures/$name").use { input ->
                        source.outputStream().use { input.copyTo(it) }
                    }
                    val kind = OfficeKind.entries.single { it.extension == source.extension }
                    // Real production admission passes the file, then this debug service stops before JNI.
                    rejectedByWorker(source, kind, OfficeFailure.ENGINE_UNAVAILABLE)
                }
                rejectedByWorker(File(fixtures, "word.docx"), OfficeKind.XLSX, OfficeFailure.UNSUPPORTED_DOCUMENT)
                val corrupt = File(directory, "corrupt.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                rejectedByWorker(corrupt, OfficeKind.DOCX, OfficeFailure.CORRUPT_DOCUMENT)
                val admissionChecks = OfficeAdmissionChecks.run(fixtures, directory)
                check(admissionChecks >= 84)
                for (name in listOf("word.docx", "word.doc", "workbook.xls", "workbook.xlsx")) {
                    // A misleading .pdf name/MIME must not turn a ZIP/OLE container into PDF.
                    val file = File(directory, "spoofed.pdf")
                    context.assets.open("office-fixtures/$name").use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                    val uri = FileProvider.getUriForFile(context,
                        "${context.packageName}.reportprovider", file)
                    val failure = runCatching {
                        DocumentInspector.openSession(context, uri).use { error("Office accepted without engine") }
                    }.exceptionOrNull()
                    check(failure is PrivateDocumentImport.Failure &&
                        failure.reason == PrivateDocumentImport.Reason.UNSUPPORTED)
                    check(sources() == before) { "Failed intake retained a private source" }
                    val cancelled = runCatching {
                        DocumentInspector.openSession(context, uri) { true }.use { error("Cancelled intake accepted") }
                    }.exceptionOrNull()
                    check(cancelled is CancellationException)
                    check(sources() == before)
                    file.delete()
                }
                check(PrintQueueRuntime.snapshot() == queueBefore) { "Rejected intake changed queue" }
            } finally { directory.deleteRecursively() }
            result.putString("stream", "CAPT_OFFICE_REJECTION_CLEANUP_PASSED (container admission and rejection; no rendering/USB)")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            // Do not leak file/provider messages into diagnostics.
            result.putString("stream", "OFFICE_INTAKE_FAILED:${error.javaClass.simpleName}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
