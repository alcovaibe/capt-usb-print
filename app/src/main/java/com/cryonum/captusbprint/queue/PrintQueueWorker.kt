package com.cryonum.captusbprint.queue

import java.util.concurrent.Executors

/** Both the visible direct-print service and system-bound PrintService use ONE serial worker. */
internal object PrintQueueWorker {
    private val worker = Executors.newSingleThreadExecutor()
    fun execute(action: () -> Unit) { worker.execute(action) }
}
