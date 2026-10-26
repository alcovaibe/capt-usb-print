package com.cryonum.captusbprint

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.cryonum.captusbprint.office.OfficeWorkerService
import java.io.File

/** Synthetic pre-HELLO cancellation window. Never receives a document or starts a backend. */
class OfficeDelayedBindService : OfficeWorkerService() {
    override fun onBind(intent: Intent?): IBinder {
        File(noBackupFilesDir, "office-delayed-bind-pid.txt").writeText(Process.myPid().toString())
        SystemClock.sleep(1500)
        return super.onBind(intent)
    }
}
