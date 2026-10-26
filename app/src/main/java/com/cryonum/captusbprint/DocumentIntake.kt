package com.cryonum.captusbprint

/** Hints for system file selectors only. Actual kind is detected from the private snapshot. */
internal object DocumentIntake {
    val MIME_TYPES = arrayOf("application/pdf", "image/jpeg", "image/png",
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
}
