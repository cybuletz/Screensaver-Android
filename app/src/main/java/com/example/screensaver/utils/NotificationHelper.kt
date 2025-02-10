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
        const val SERVICE_CHANNEL_ID = "screensaver_service_channel"
        const val SERVICE_NOTIFICATION_ID = 1
        private const val KIOSK_CHANNEL_NAME = "Kiosk Mode"
        private const val KIOSK_CHANNEL_DESCRIPTION = "Notifications for kiosk mode"
        private const val SERVICE_CHANNEL_NAME = "Screensaver Service"
        private const val SERVICE_CHANNEL_DESCRIPTION = "Required for screensaver functionality"
    }

    init {
        createNotificationChannels()
    }

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val kioskChannel = NotificationChannel(
                KIOSK_CHANNEL_ID,
                KIOSK_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = KIOSK_CHANNEL_DESCRIPTION
            }

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                SERVICE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = SERVICE_CHANNEL_DESCRIPTION
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannels(listOf(kioskChannel, serviceChannel))
        }
    }

    fun createKioskNotification(title: String, content: String) = NotificationCompat.Builder(context, KIOSK_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    fun createServiceNotification(title: String, content: String) = NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()
}