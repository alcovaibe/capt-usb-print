package com.pulse.ai.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pulse.ai.MainActivity
import com.pulse.ai.R
import com.pulse.ai.PulseAiApplication
import com.pulse.ai.data.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PulseAiService : LifecycleService() {

    private lateinit var repository: ChatRepository

    override fun onCreate() {
        super.onCreate()
        repository = (application as PulseAiApplication).repository
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                101,
                createNotification(getString(R.string.service_title), getString(R.string.service_content)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(101, createNotification(getString(R.string.service_title), getString(R.string.service_content)))
        }
        
        // Polling logic
        lifecycleScope.launch {
            while (true) {
                delay(30000) // 30 seconds
                checkForUpdates()
            }
        }
    }

    private suspend fun checkForUpdates() {
        // Logic to poll Gemini if needed
        // For example, if there's a "pending" message from AI
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        const val CHANNEL_ID = "pulse_ai_service_channel_v3"
    }
}
