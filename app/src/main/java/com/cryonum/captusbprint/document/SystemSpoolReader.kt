package com.cryonum.captusbprint.document

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Reads seekable files and Android spooler pipes off the main thread with finite waits. */
internal object SystemSpoolReader {
    fun copy(input: ParcelFileDescriptor, destination: File, reserve: (Long) -> Unit,
             cancelled: () -> Boolean, timeoutMillis: Long = 30_000): Long = input.use { descriptor ->
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val bytes = ByteArray(65536)
        var total = 0L
        destination.outputStream().use { output ->
            while (true) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
                if (SystemClock.elapsedRealtime() >= deadline) throw IOException("SYSTEM_SPOOL_TIMEOUT")
                if (Os.poll(arrayOf(poll), 250) == 0) continue
                val flags = poll.revents.toInt()
                if ((flags and (OsConstants.POLLERR or OsConstants.POLLNVAL)) != 0)
                    throw IOException("SYSTEM_SPOOL_READ_FAILED")
                val n = Os.read(descriptor.fileDescriptor, bytes, 0, bytes.size)
                if (n == 0) break
                total += n
                require(total <= DocumentInspector.MAX_SOURCE_BYTES) { "SYSTEM_SPOOL_TOO_LARGE" }
                reserve(n.toLong())
                output.write(bytes, 0, n)
            }
        }
        require(total > 0) { "SYSTEM_SPOOL_EMPTY" }
        total
    }
}
