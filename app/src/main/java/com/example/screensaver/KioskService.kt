package com.example.screensaver.kiosk

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.preference.PreferenceManager
import com.example.screensaver.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class KioskService : Service() {
    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    private val kioskReceiver = KioskReceiver()

    companion object {
        private const val TAG = "KioskService"
        private const val NOTIFICATION_ID = 3 // Use a different ID from your other services
    }

    override fun onCreate() {
        super.onCreate()
        registerKioskReceiver()
        startForeground(
            NOTIFICATION_ID,
            notificationHelper.createServiceNotification("Kiosk Mode Active", "App is running in kiosk mode")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_KIOSK" -> stopKioskMode()
            else -> setupKioskMode()
        }
        return START_STICKY
    }

    private fun setupKioskMode() {
        kioskPolicyManager.setKioskPolicies(true)
        // Launch kiosk activity if it's not already running
        if (!isKioskActivityRunning()) {
            KioskActivity.start(this)
        }
    }

    private fun stopKioskMode() {
        kioskPolicyManager.setKioskPolicies(false)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean("kiosk_mode_enabled", false)
            .apply()
        stopSelf()
    }

    private fun registerKioskReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(kioskReceiver, filter)
    }

    private fun isKioskActivityRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val taskInfo = am.getRunningTasks(1)[0]
        return taskInfo.topActivity?.className == KioskActivity::class.java.name
    }

    override fun onDestroy() {
        unregisterReceiver(kioskReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}