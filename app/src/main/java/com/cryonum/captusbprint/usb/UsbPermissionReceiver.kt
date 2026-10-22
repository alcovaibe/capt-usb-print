package com.cryonum.captusbprint.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager

internal object PermissionEvents {
    const val ACTION = "com.cryonum.captusbprint.action.USB_PERMISSION"
    @Volatile var outcome: String = "Событие разрешения USB в текущем сеансе не зафиксировано"
    // Main-process callback only. It is installed only while Activity is resumed.
    var listener: (() -> Unit)? = null
}

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PermissionEvents.ACTION) return
        PermissionEvents.outcome =
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                "Системное разрешение USB предоставлено"
            else
                "Системное разрешение USB отклонено"
        PermissionEvents.listener?.invoke()
    }
}
