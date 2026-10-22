package com.cryonum.captusbprint.document

import com.cryonum.captusbprint.core.CaptPageProtocol
import java.io.*
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/** Private spool, one encoded strip in RAM. Checks lengths and CRC before USB. */
internal class StoredCaptPage private constructor(
    private val file: File, override val compressedBytes: Int
) : PreparedCaptPage {
    override val bandCount = OfflinePdfHiscoaPreparation.EXPECTED_BANDS
    override fun descriptorFrame() = CaptPageProtocol.beginA4TestPage()

    override fun forEachVideoFrame(chunkLimit: Int, cancelled: () -> Boolean,
        consumer: (Int, ByteArray) -> Unit) {
        require(chunkLimit in 64..0xff00)
        read(cancelled) { index, bytes ->
            CaptPageProtocol.videoData(bytes, chunkLimit).forEach {
                guard(cancelled)
                consumer(index, it)
            }
        }
    }

    fun validate(cancelled: () -> Boolean) { read(cancelled) { _, _ -> } }

    private fun read(cancelled: () -> Boolean, accept: (Int, ByteArray) -> Unit) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == MAGIC) { "Invalid private spool header" }
            val crc = CRC32()
            var total = 0L
            repeat(bandCount) { index ->
                guard(cancelled)
                val n = input.readInt()
                check(n in 1..MAX_BAND_BYTES) { "Invalid private spool strip length" }
                total += n
                check(total <= OfflinePdfHiscoaPreparation.MAX_COMPRESSED_PAGE_BYTES)
                val bytes = ByteArray(n)
                input.readFully(bytes)
                crc.update(bytes)
                accept(index, bytes)
            }
            check(total == compressedBytes.toLong() && input.readLong() == crc.value && input.read() == -1) {
                "Incomplete or changed private spool page"
            }
        }
    }

    companion object {
        private const val MAGIC = 0x43505431
        private const val MAX_BAND_BYTES = 620 * 64 * 2 + 64
        fun write(file: File, produce: ((Int, ByteArray) -> Unit) -> Unit,
                  encode: (ByteArray, Int, Int, Boolean) -> ByteArray,
                  cancelled: () -> Boolean, reserve: (Long) -> Unit): StoredCaptPage {
            try {
                val crc = CRC32()
                val summary = DataOutputStream(file.outputStream().buffered()).use { out ->
                    reserve(12)
                    out.writeInt(MAGIC)
                    val result = OfflinePdfHiscoaPreparation.prepare(produce, encode, cancelled) { _, bytes ->
                        reserve(4L + bytes.size)
                        out.writeInt(bytes.size)
                        out.write(bytes)
                        crc.update(bytes)
                    }
                    out.writeLong(crc.value)
                    result
                }
                return StoredCaptPage(file, summary.compressedBytes.toInt()).also { it.validate(cancelled) }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
        private fun guard(cancelled: () -> Boolean) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
    }
}
