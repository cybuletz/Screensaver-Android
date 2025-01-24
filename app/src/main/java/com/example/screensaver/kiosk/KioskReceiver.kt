package com.example.screensaver.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager

class KioskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isKioskModeEnabled(context)) return

        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                // Screen turned off, ensure kiosk mode is still active when screen comes back
                val kioskIntent = Intent(context, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(kioskIntent)
            }
            Intent.ACTION_USER_PRESENT -> {
                // User unlocked the device, ensure kiosk mode is active
                KioskActivity.start(context)
            }
        }
    }

    private fun isKioskModeEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("kiosk_mode_enabled", false)
}