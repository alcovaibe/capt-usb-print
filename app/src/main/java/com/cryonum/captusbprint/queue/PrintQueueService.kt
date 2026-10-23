package com.cryonum.captusbprint.queue

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.cryonum.captusbprint.R
import java.util.concurrent.atomic.AtomicBoolean

/** Started from a visible user action with an existing USB grant. Never sticky/replayed. */
class PrintQueueService : Service() {
    private val expectedStop = AtomicBoolean(false)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.queue_title), NotificationManager.IMPORTANCE_LOW))
    }
    @android.annotation.SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        expectedStop.set(false)
        try {
            startForeground(17, notification(getString(R.string.queue_preparing)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            PrintQueueRuntime.initialize(this)
            if (intent?.action == CANCEL) PrintQueueRuntime.stopServiceJobs()
            PrintQueueWorker.execute {
                    try {
                        when (intent?.action) {
                            CLEAR -> PrintQueueRuntime.clearRequested()
                            CONTINUE -> PrintQueueRuntime.continuePaper(intent.getLongExtra("jobId", -1))
                        }
                        PrintQueueRuntime.drain { record ->
                            runCatching {
                                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(17,
                                    notification(getString(R.string.queue_progress, record.confirmed, record.sheets)))
                            }
                        }
                    } catch (_: Throwable) {
                        PrintQueueRuntime.serviceFailure()
                    } finally {
                        if (!PrintQueueRuntime.hasRunnableWork() || PrintQueueRuntime.isPaused()) {
                            // A newer startId has its own queued worker command.
                            expectedStop.set(true)
                            if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                            else expectedStop.set(false)
                        }
                    }
            }
        } catch (_: Exception) {
            expectedStop.set(true)
            when (intent?.action) {
                CLEAR -> PrintQueueWorker.execute { runCatching { PrintQueueRuntime.clearRequested() } }
                CONTINUE -> PrintQueueRuntime.maintenanceStartFailed()
                else -> PrintQueueRuntime.serviceFailure(directOnly = true)
            }
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
        if (!expectedStop.get()) runCatching { PrintQueueRuntime.stopServiceJobs(directOnly = true) }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "capt_usb_jobs"
        private const val CANCEL = "com.cryonum.captusbprint.CANCEL_QUEUE"
        private const val CLEAR = "com.cryonum.captusbprint.CLEAR_QUEUE"
        private const val CONTINUE = "com.cryonum.captusbprint.CONTINUE_PAPER"
        fun clear(context: Context) {
            PrintQueueRuntime.requestClear()
            // Clearing local records works even without a connected/granted printer.
            // In that case the worker reports printer clear as unconfirmed, without USB.
            runCatching { start(context, CLEAR) }.onFailure {
                PrintQueueWorker.execute { runCatching { PrintQueueRuntime.clearRequested() } }
            }
        }
        fun continuePaper(context: Context, id: Long) {
            // Check the grant BEFORE changing intent; no new permission dialog or replay.
            com.cryonum.captusbprint.usb.CaptPrinterMaintenance.deviceKey(context)
            PrintQueueRuntime.requestPaperContinuation(id)
            runCatching { start(context, CONTINUE, id) }.onFailure {
                PrintQueueRuntime.maintenanceStartFailed()
                throw it
            }
        }
        fun start(context: Context) = start(context, null)
        private fun start(context: Context, action: String?, id: Long = -1) {
            val usb = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val device = usb.deviceList.values.filter {
                com.cryonum.captusbprint.usb.UsbDeviceProfiles.identify(it.vendorId, it.productId)?.modelName == "Canon LBP2900"
            }.singleOrNull()
            check(device != null && usb.hasPermission(device)) { "A granted Canon USB device is required" }
            // Only callers reached from a visible Activity may start this service.
            context.startForegroundService(Intent(context, PrintQueueService::class.java).setAction(action).putExtra("jobId", id))
        }
    }
}
