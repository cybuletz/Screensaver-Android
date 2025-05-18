package com.photostreamr.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.photostreamr.MainActivity

class RestartBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "RestartReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val timestamp = intent.getLongExtra("timestamp", 0L)
        val restartTypeOrdinal = intent.getIntExtra("restart_type", AppRestartManager.RESTART_TYPE_ACTIVITY)

        if (restartTypeOrdinal == AppRestartManager.RESTART_TYPE_PROCESS) {
            Log.d(TAG, "Received PROCESS restart signal (timestamp: $timestamp)")

            // For Android 8, use a more aggressive and faster restart approach
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {

                Log.d(TAG, "Using Android 8 specific fast process restart method")

                // Create launch intent - include all flags needed for proper restart
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    // Set all flags needed for a complete restart
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

                    putExtra("restarted", true)
                    putExtra("restart_type", restartTypeOrdinal)
                    putExtra("restart_timestamp", System.currentTimeMillis())
                    putExtra("fast_restart", true)
                }

                // Create a PendingIntent to launch after we exit
                val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_CANCEL_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

                val pendingIntent = PendingIntent.getActivity(
                    context, 1001, launchIntent, flags
                )

                // Use AlarmManager for immediate restart
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                try {
                    // Set for immediate restart - use setExact for better timing
                    alarmManager.setExact(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + 50, // Just 50ms delay
                        pendingIntent
                    )
                    Log.d(TAG, "Set exact alarm for immediate restart")
                } catch (e: Exception) {
                    try {
                        // Fall back to regular set if setExact fails
                        alarmManager.set(
                            AlarmManager.RTC,
                            System.currentTimeMillis() + 50,
                            pendingIntent
                        )
                        Log.d(TAG, "Set regular alarm for immediate restart")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to set any alarm", e2)
                    }
                }

                // Immediately start the activity as well (backup method)
                try {
                    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    context.startActivity(launchIntent)
                    Log.d(TAG, "Started activity directly as backup")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start activity directly", e)
                }

                // Kill process immediately without delay
                try {
                    Log.d(TAG, "CRITICAL: Forcibly terminating process from broadcast receiver")
                    Runtime.getRuntime().exit(0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to exit runtime", e)
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (e2: Exception) {
                        Log.e(TAG, "All process kill attempts failed", e2)
                    }
                }

                return
            }

            // For other Android versions, proceed with normal approach
            try {
                Log.d(TAG, "Starting main activity with restart extras")

                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("restarted", true)
                    putExtra("restart_type", restartTypeOrdinal)
                    putExtra("restart_timestamp", timestamp)
                }

                context.startActivity(mainIntent)
                Log.d(TAG, "Successfully started main activity for restart")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting activity for restart", e)
            }

        } else {
            // Activity restart - handle as before
            Log.d(TAG, "Received ACTIVITY restart signal (timestamp: $timestamp)")

            try {
                // Start the main activity with restart flags
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("restarted", true)
                    putExtra("restart_type", restartTypeOrdinal)
                    putExtra("restart_timestamp", timestamp)
                }

                context.startActivity(mainIntent)
                Log.d(TAG, "Successfully started main activity for activity restart")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting activity for restart", e)
            }
        }
    }
}