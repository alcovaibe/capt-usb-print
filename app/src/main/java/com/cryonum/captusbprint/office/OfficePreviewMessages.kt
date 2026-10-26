package com.cryonum.captusbprint.office

import com.cryonum.captusbprint.R

/** Only fixed localized codes reach the UI. Never Throwable.message/provider/UNO text. */
internal object OfficePreviewMessages {
    fun resource(reason: OfficeFailure): Int = when (reason) {
        OfficeFailure.CANCELLED -> R.string.document_import_cancelled
        OfficeFailure.PASSWORD_REQUIRED -> R.string.office_error_password
        OfficeFailure.CORRUPT_DOCUMENT, OfficeFailure.BAD_INPUT -> R.string.office_error_corrupt
        OfficeFailure.UNSUPPORTED_DOCUMENT -> R.string.office_error_unsupported
        OfficeFailure.DOCUMENT_LIMIT, OfficeFailure.DISK_LIMIT, OfficeFailure.MEMORY_LIMIT, OfficeFailure.FILE_LIMIT -> R.string.office_error_limit
        OfficeFailure.NO_SPACE -> R.string.document_import_no_space
        OfficeFailure.TIME_LIMIT, OfficeFailure.CPU_LIMIT -> R.string.office_error_time
        OfficeFailure.UNSUPPORTED_LAYOUT -> R.string.office_error_layout
        OfficeFailure.ENGINE_UNAVAILABLE -> R.string.office_error_engine
        OfficeFailure.BUSY -> R.string.office_error_busy
        OfficeFailure.WORKER_CRASHED -> R.string.office_error_crash
        OfficeFailure.INVALID_RESULT -> R.string.office_error_pdf
        OfficeFailure.RESOURCE_OBSERVATION, OfficeFailure.WORKER_STOP_FAILED -> R.string.office_error_cleanup
        OfficeFailure.ENGINE_FAILED -> R.string.office_error_failed
        OfficeFailure.SANDBOX_UNAVAILABLE -> R.string.office_error_sandbox
        OfficeFailure.EXTERNAL_RESOURCE -> R.string.office_error_external
        OfficeFailure.SOURCE_ACCESS -> R.string.office_error_source_access
        OfficeFailure.SOURCE_FAILED -> R.string.office_error_source_failed
    }
}
