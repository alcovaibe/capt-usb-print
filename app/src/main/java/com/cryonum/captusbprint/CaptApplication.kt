package com.cryonum.captusbprint

import android.app.Application
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import java.io.File

class CaptApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // An Office child must not initialize USB/queue or delete a live UI preview.
        if (Application.getProcessName() != packageName) return
        // Only at process startup: no live preview owns these transient files yet.
        File(cacheDir, "stage-c-inspection").deleteRecursively()
        // Office work is never resumed after its owning UI process has died.
        File(noBackupFilesDir, "office-work").deleteRecursively()
        File(noBackupFilesDir, "office-ui").deleteRecursively()
        runCatching { PrintQueueRuntime.initialize(this) }
    }
}
