package com.cryonum.captusbprint.queue

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.cryonum.captusbprint.R
import java.util.concurrent.Executors

/** Started from a visible user action with an existing USB grant. Never sticky/replayed. */
class PrintQueueService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.queue_title), NotificationManager.IMPORTANCE_LOW))
    }
    @android.annotation.SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(17, notification(getString(R.string.queue_preparing)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            PrintQueueRuntime.initialize(this)
            if (intent?.action == CANCEL) PrintQueueRuntime.stopServiceJobs()
            worker.execute {
                    running = true
                    try {
                        PrintQueueRuntime.drain { record ->
                            runCatching {
                                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(17,
                                    notification(getString(R.string.queue_progress, record.confirmed, record.sheets)))
                            }
                        }
                    } catch (_: Throwable) {
                        PrintQueueRuntime.serviceFailure()
                    } finally {
                        running = false
                        if (!PrintQueueRuntime.hasWork() || PrintQueueRuntime.isPaused()) {
                            // A newer startId has its own queued worker command.
                            if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    }
            }
        } catch (_: Exception) {
            PrintQueueRuntime.serviceFailure()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, PrintQueueActivity::class.java), flags)
        val cancel = PendingIntent.getService(this, 2,
            Intent(this, PrintQueueService::class.java).setAction(CANCEL), flags)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.queue_title)).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.queue_cancel_all), cancel)
            .build()
    }
    override fun onDestroy() {
        if (running) PrintQueueRuntime.stopServiceJobs()
        worker.shutdown()
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "capt_usb_jobs"
        private const val CANCEL = "com.cryonum.captusbprint.CANCEL_QUEUE"
        fun start(context: Context) {
            // Only callers reached from a visible Activity may start this service.
            context.startForegroundService(Intent(context, PrintQueueService::class.java))
        }
    }
}
