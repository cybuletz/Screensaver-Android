package com.photostreamr.utils

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

            // For Android 8, we need a more aggressive approach
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {

                Log.d(TAG, "Using Android 8 specific process restart method")

                // Create launch intent with stronger flags
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

                    putExtra("restarted", true)
                    putExtra("restart_type", restartTypeOrdinal)
                    putExtra("restart_timestamp", System.currentTimeMillis())

                    // Set background color to black to make restart visible
                    putExtra("force_black_background", true)
                }

                // Start a new activity first
                try {
                    context.startActivity(launchIntent)
                    Log.d(TAG, "Started new activity instance for Android 8 restart")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start new activity", e)
                }

                // Wait a moment, then kill process
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Log.d(TAG, "CRITICAL: Forcibly terminating process from broadcast receiver")
                        Runtime.getRuntime().exit(0) // More forceful than Process.killProcess
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to exit runtime", e)
                        try {
                            Log.d(TAG, "Attempting alternate process kill method")
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } catch (e2: Exception) {
                            Log.e(TAG, "All process kill attempts failed", e2)
                        }
                    }
                }, 1000) // Give it a full second

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