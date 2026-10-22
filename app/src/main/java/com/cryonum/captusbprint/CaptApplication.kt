package com.cryonum.captusbprint

import android.app.Application
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import java.io.File

class CaptApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only at process startup: no live preview owns these transient files yet.
        File(cacheDir, "stage-c-inspection").deleteRecursively()
        runCatching { PrintQueueRuntime.initialize(this) }
    }
}
