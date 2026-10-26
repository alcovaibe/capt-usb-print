package com.cryonum.captusbprint.office

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import com.cryonum.captusbprint.document.DocumentSession

internal enum class OfficeKind(val extension: String, val spreadsheet: Boolean) {
    DOCX("docx", false), DOC("doc", false), XLS("xls", true), XLSX("xlsx", true)
}

internal class OfficeOptions(kind: OfficeKind, selectedSheets: List<Int>? = null,
                             val fitWidth: Boolean = false) {
    val kind = kind
    init { require(!fitWidth || kind.spreadsheet) }
    val selectedSheets: List<Int>? = selectedSheets?.let {
        require(kind.spreadsheet && it.size in 1..256 && it.all { index -> index in 0..255 })
        require(it.distinct().size == it.size)
        Collections.unmodifiableList(it.sorted())
    }
    fun write(parcel: Parcel) {
        parcel.writeInt(kind.ordinal); parcel.writeInt(if (fitWidth) 1 else 0)
        parcel.writeInt(selectedSheets?.size ?: -1)
        selectedSheets?.forEach { parcel.writeInt(it) }
    }
    companion object {
        fun read(parcel: Parcel): OfficeOptions {
            val kind = OfficeKind.entries.getOrNull(parcel.readInt()) ?: error("INVALID_KIND")
            val fit = parcel.readInt().also { require(it in 0..1) } == 1
            val count = parcel.readInt().also { require(it == -1 || it in 1..256) }
            return OfficeOptions(kind, if (count == -1) null else List(count) { parcel.readInt() }, fit)
        }
    }
}

internal enum class OfficeFailure {
    ENGINE_UNAVAILABLE, CANCELLED, TIME_LIMIT, MEMORY_LIMIT, DISK_LIMIT, NO_SPACE,
    RESOURCE_OBSERVATION, WORKER_CRASHED, WORKER_STOP_FAILED, BAD_INPUT, UNSUPPORTED_LAYOUT,
    ENGINE_FAILED, INVALID_RESULT, BUSY, PASSWORD_REQUIRED, UNSUPPORTED_DOCUMENT, CORRUPT_DOCUMENT, DOCUMENT_LIMIT, CPU_LIMIT, FILE_LIMIT, SANDBOX_UNAVAILABLE, EXTERNAL_RESOURCE,
    SOURCE_ACCESS, SOURCE_FAILED, FIT_WIDTH_UNSUPPORTED
}

internal class OfficeConversionFailure(val reason: OfficeFailure, val sandboxCategory: Int = 0) : IOException(reason.name)

/** A stopped worker's private result. Closing deletes only this request's workspace. */
internal class OfficeWorkerOutput(val file: File, sheets: List<String>, val workerPid: Int,
                                  val peakObservedRssBytes: Long, private val directory: File,
                                  val importedKind: OfficeKind? = null) : Closeable {
    val sheetNames: List<String> = Collections.unmodifiableList(sheets.toList())
    override fun close() {
        if (!OfficePrivateFiles.deleteTree(directory)) throw OfficeConversionFailure(OfficeFailure.RESOURCE_OBSERVATION)
    }
}

internal class OfficePreparedDocument(val session: DocumentSession, sheets: List<String>,
                                     val workerPid: Int, val peakObservedRssBytes: Long) : Closeable {
    val sheetNames: List<String> = Collections.unmodifiableList(sheets.toList())
    override fun close() = session.close()
}

/** Implementations must not be exposed to user documents until their resource policy is verified. */
internal interface OfficeWorkerBackend {
    fun preload() = Unit
    fun convert(context: Context, input: File, output: File, directory: File,
                options: OfficeOptions): List<String>
}

internal object OfficeWorkerProtocol {
    const val DESCRIPTOR = "com.cryonum.captusbprint.office.Worker.v1"
    const val HELLO = IBinder.FIRST_CALL_TRANSACTION
    const val START = HELLO + 1
    const val RESULT = HELLO + 2
    const val IMPORT = HELLO + 3
    const val BACKEND = "com.cryonum.captusbprint.office.LibreOfficeWorkerBackend"
    const val MAX_PDF_BYTES = 64L * 1024 * 1024
    fun directory(context: Context, id: String): File {
        require(UUID.fromString(id).toString() == id)
        val root = File(context.noBackupFilesDir, "office-work").canonicalFile
        return File(root, id).also { require(it.canonicalFile == it.absoluteFile) }
    }
    fun failure(error: Throwable): OfficeFailure = when (error) {
        is OfficeConversionFailure -> error.reason
        is OfficeAdmission.Failure -> when (error.reason) {
            OfficeAdmission.Reason.PASSWORD -> OfficeFailure.PASSWORD_REQUIRED
            OfficeAdmission.Reason.UNSUPPORTED -> OfficeFailure.UNSUPPORTED_DOCUMENT
            OfficeAdmission.Reason.CORRUPT -> OfficeFailure.CORRUPT_DOCUMENT
            OfficeAdmission.Reason.LIMIT -> OfficeFailure.DOCUMENT_LIMIT
            OfficeAdmission.Reason.CANCELLED -> OfficeFailure.CANCELLED
        }
        is OfficeWorkBudget.Exceeded -> when (error.limit) {
            OfficeWorkBudget.Limit.TIME -> OfficeFailure.TIME_LIMIT
            OfficeWorkBudget.Limit.MEMORY -> OfficeFailure.MEMORY_LIMIT
            OfficeWorkBudget.Limit.NO_SPACE -> OfficeFailure.NO_SPACE
            OfficeWorkBudget.Limit.OBSERVATION -> OfficeFailure.RESOURCE_OBSERVATION
            else -> OfficeFailure.DISK_LIMIT
        }
        is com.cryonum.captusbprint.document.PrivateDocumentImport.Failure -> when (error.reason) {
            com.cryonum.captusbprint.document.PrivateDocumentImport.Reason.NO_SPACE -> OfficeFailure.NO_SPACE
            com.cryonum.captusbprint.document.PrivateDocumentImport.Reason.TIMED_OUT -> OfficeFailure.TIME_LIMIT
            com.cryonum.captusbprint.document.PrivateDocumentImport.Reason.TOO_LARGE -> OfficeFailure.DOCUMENT_LIMIT
            else -> OfficeFailure.BAD_INPUT
        }
        is ClassNotFoundException -> OfficeFailure.ENGINE_UNAVAILABLE
        is OutOfMemoryError -> OfficeFailure.MEMORY_LIMIT
        else -> OfficeFailure.ENGINE_FAILED
    }
}
