package com.example.screensaver.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.screensaver.MainActivity

class ChargingReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ChargingReceiver"
        private const val DEBOUNCE_TIME = 1000L // 1 second debounce
    }

    private var lastProcessedTime = 0L

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received action: $action")

        try {
            // Always log preference state
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val startOnCharge = prefs.getBoolean("start_on_charge", false)
            val allPrefs = prefs.all.toString()

            Log.i(TAG, """
                Receiver triggered:
                Action: $action
                Start on charge enabled: $startOnCharge
                All preferences: $allPrefs
                System time: ${System.currentTimeMillis()}
            """.trimIndent())

            if (!startOnCharge) {
                Log.i(TAG, "Start on charge is disabled, ignoring event")
                return
            }

            when (action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.i(TAG, "Power connected event received")
                    checkChargingAndStart(context)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Log.i(TAG, "Power disconnected event received")
                }
                Intent.ACTION_BOOT_COMPLETED -> {
                    Log.i(TAG, "Boot completed event received")
                }
                else -> {
                    Log.i(TAG, "Unhandled action received: $action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in receiver", e)
            e.printStackTrace()
        }
    }

    private fun checkChargingAndStart(context: Context) {
        try {
            val batteryStatus = context.registerReceiver(null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            if (batteryStatus == null) {
                Log.e(TAG, "Failed to get battery status")
                return
            }

            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

            Log.i(TAG, """
                Charging Check:
                Status: $status
                Is Charging: $isCharging
                Charge Plug: $chargePlug
                USB Charging: ${chargePlug == BatteryManager.BATTERY_PLUGGED_USB}
                AC Charging: ${chargePlug == BatteryManager.BATTERY_PLUGGED_AC}
            """.trimIndent())

            if (isCharging) {
                startApp(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking charging state", e)
            e.printStackTrace()
        }
    }

    private fun startApp(context: Context) {
        try {
            Log.i(TAG, "Starting app")
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("start_screensaver", true)
                putExtra("start_reason", "charging")
                putExtra("timestamp", System.currentTimeMillis())
            }

            Log.i(TAG, "Starting activity with intent flags: ${intent.flags}")
            context.startActivity(intent)
            Log.i(TAG, "Activity start requested successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting app", e)
            e.printStackTrace()
        }
    }

    private fun logPreferenceState(context: Context) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val startOnCharge = prefs.getBoolean("start_on_charge", false)
            val allPrefs = prefs.all

            Log.d(TAG, """
            Preference State:
            - start_on_charge: $startOnCharge
            - all preferences: $allPrefs
        """.trimIndent())
        } catch (e: Exception) {
            Log.e(TAG, "Error logging preferences", e)
        }
    }

}