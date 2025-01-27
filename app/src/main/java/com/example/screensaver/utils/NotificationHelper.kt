package com.example.screensaver.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.screensaver.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val KIOSK_CHANNEL_ID = "kiosk_channel"
        const val KIOSK_NOTIFICATION_ID = 2
        private const val KIOSK_CHANNEL_NAME = "Kiosk Mode"
        private const val KIOSK_CHANNEL_DESCRIPTION = "Notifications for kiosk mode"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                KIOSK_CHANNEL_ID,
                KIOSK_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = KIOSK_CHANNEL_DESCRIPTION
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createKioskNotification(title: String, content: String) = NotificationCompat.Builder(context, KIOSK_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()
}