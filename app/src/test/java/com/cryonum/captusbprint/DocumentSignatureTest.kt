package com.cryonum.captusbprint

import com.cryonum.captusbprint.document.DocumentFormat
import com.cryonum.captusbprint.document.DocumentSignature
import org.junit.Assert.*
import org.junit.Test

class DocumentSignatureTest {
    @Test fun supportedFileHeadersAreRecognizedWithoutMimeOrExtension() {
        assertEquals(DocumentFormat.PDF, DocumentSignature.detect(
            "%PDF-1.7".toByteArray(Charsets.US_ASCII)))
        assertEquals(DocumentFormat.PNG, DocumentSignature.detect(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)))
        assertEquals(DocumentFormat.JPEG, DocumentSignature.detect(
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())))
    }

    @Test fun maliciousAndIncompleteInputsAreRejected() {
        assertNull(DocumentSignature.detect(byteArrayOf()))
        assertNull(DocumentSignature.detect("%PDF".toByteArray()))
        assertNull(DocumentSignature.detect("not a pdf".toByteArray()))
        assertNull(DocumentSignature.detect(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        assertNull(DocumentSignature.detect(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 0)))
    }
}
