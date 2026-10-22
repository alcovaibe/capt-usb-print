package com.cryonum.captusbprint.document

/** Trust the file signature, never just the MIME type or display name. */
enum class DocumentFormat(val label: String) {
    PDF("PDF"), JPEG("JPEG"), PNG("PNG")
}

object DocumentSignature {
    fun detect(header: ByteArray): DocumentFormat? = when {
        header.size >= 5 && header.copyOfRange(0, 5).contentEquals(
            byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        ) -> DocumentFormat.PDF
        header.size >= 8 && header.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        ) -> DocumentFormat.PNG
        header.size >= 3 && header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() ->
            DocumentFormat.JPEG
        else -> null
    }
}
