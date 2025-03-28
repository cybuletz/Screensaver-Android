package com.photostreamr.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.photostreamr.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SERVICE_CHANNEL_ID = "screensaver_service_channel"
        const val SERVICE_NOTIFICATION_ID = 1
        private const val SERVICE_CHANNEL_NAME = "Screensaver Service"
        private const val SERVICE_CHANNEL_DESCRIPTION = "Required for photostreamr functionality"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                SERVICE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = SERVICE_CHANNEL_DESCRIPTION
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(serviceChannel)
        }
    }

    fun createServiceNotification(title: String, content: String) =
        NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}