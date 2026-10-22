package com.cryonum.captusbprint

import com.cryonum.captusbprint.document.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

class StageCSpoolTest {
    private fun bands(accept: (Int, ByteArray) -> Unit) {
        var y = 0
        while (y < 7016) { val lines = minOf(64, 7016-y); accept(y, ByteArray(620*lines)); y += lines }
    }
    @Test fun pageSpoolsWithoutRetainingTheRasterAndCopiesReadTheSameFrames() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            var budget = 0L
            val page = StoredCaptPage.write(File(directory, "p"), ::bands,
                { _, _, _, _ -> byteArrayOf(23) }, { false }, { budget += it })
            assertEquals(110, page.compressedBytes)
            assertEquals(File(directory, "p").length(), budget)
            repeat(2) {
                var frames = 0
                page.forEachVideoFrame(64, { false }) { _, frame ->
                    assertEquals(23, frame[4].toInt()); frames++
                }
                assertEquals(110, frames)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptLengthTruncationAndCrcAreRejectedBeforeUsb() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            for (offset in listOf(4L, 8L, 120L)) {
                val file = File(directory, "p")
                val page = StoredCaptPage.write(file, ::bands, { _, _, _, _ -> byteArrayOf(1) }, { false }, {})
                java.io.RandomAccessFile(file, "rw").use { it.seek(offset); it.write(0x7f) }
                assertThrows(Exception::class.java) { page.validate { false } }
            }
            val truncated = File(directory,"truncated")
            val page = StoredCaptPage.write(truncated,::bands,{ _, _, _, _ -> byteArrayOf(1) },{ false },{})
            java.io.RandomAccessFile(truncated,"rw").use { it.setLength(truncated.length()-3) }
            assertThrows(Exception::class.java) { page.validate { false } }
        } finally { directory.deleteRecursively() }
    }
    @Test fun quotaAndCancellationRemovePartialSpool() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val file = File(directory, "p")
            var allocated = 0L
            assertThrows(IOException::class.java) {
                StoredCaptPage.write(file, ::bands, { _, _, _, _ -> ByteArray(10) }, { false }, {
                    allocated += it; if (allocated > 100) throw IOException("Quota") })
            }
            assertFalse(file.exists())
            assertThrows(CancellationException::class.java) {
                StoredCaptPage.write(file, ::bands, { _, _, _, _ -> byteArrayOf(1) }, { true }, {})
            }
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}
