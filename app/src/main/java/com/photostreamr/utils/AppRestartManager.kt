package com.photostreamr.utils

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import com.photostreamr.MainActivity
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.system.exitProcess

class AppRestartManager(private val context: Context) {

    companion object {
        private const val TAG = "AppRestartManager"

        // Restart intervals - can customize as needed
        private const val ACTIVITY_RESTART_INTERVAL_MS = 33 * 60 * 1000L // 4 hours
        private const val PROCESS_RESTART_INTERVAL_MS = 1 * 60 * 60 * 1000L // 24 hours

        // Request codes
        private const val ACTIVITY_RESTART_REQUEST_CODE = 9876
        private const val PROCESS_RESTART_REQUEST_CODE = 9877

        // Animation durations - shorter for wall display
        private const val TRANSITION_DURATION_MS = 500L // Faster transition for seamlessness

        // Shared prefs keys
        private const val PREF_KEY_LAST_ACTIVITY_RESTART = "last_activity_restart_time"
        private const val PREF_KEY_LAST_PROCESS_RESTART = "last_process_restart_time"

        // Restart types
        const val RESTART_TYPE_ACTIVITY = 0
        const val RESTART_TYPE_PROCESS = 1
    }

    private val activityRestartHandler = Handler(Looper.getMainLooper())
    private val processRestartHandler = Handler(Looper.getMainLooper())
    private var activityRestartRunnable: Runnable? = null
    private var processRestartRunnable: Runnable? = null

    // Config variable to choose restart type
    var defaultRestartType = RESTART_TYPE_ACTIVITY

    private val handler = Handler(Looper.getMainLooper())
    private var isActivityRestartScheduled = false
    private var isProcessRestartScheduled = false
    private var restartOverlayView: View? = null

    /**
     * Schedule both types of restarts with their respective intervals
     */
    fun scheduleAllRestarts(activity: Activity) {
        scheduleActivityRestart(activity)
        scheduleProcessRestart(activity)
    }

    /**
     * Schedule just the activity-level restart
     * This uses both AlarmManager (when permissions are available) and Handler (as backup)
     */
    fun scheduleActivityRestart(activity: Activity) {
        if (isActivityRestartScheduled) {
            Log.d(TAG, "Activity restart already scheduled")
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastRestartTime = prefs.getLong(PREF_KEY_LAST_ACTIVITY_RESTART, 0L)
        val currentTime = System.currentTimeMillis()

        val timeUntilNextRestart = calculateNextRestartTime(
            lastRestartTime,
            currentTime,
            ACTIVITY_RESTART_INTERVAL_MS
        )

        Log.d(TAG, "Scheduling activity restart in ${timeUntilNextRestart/60000} minutes")

        // Try to schedule via AlarmManager first
        var alarmScheduled = false
        try {
            scheduleRestart(
                ACTIVITY_RESTART_REQUEST_CODE,
                timeUntilNextRestart,
                RestartType.ACTIVITY
            )
            alarmScheduled = true
            Log.d(TAG, "AlarmManager schedule successful for activity restart")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule activity restart with AlarmManager, falling back to Handler", e)
            alarmScheduled = false
        }

        // Also set up handler as backup if alarm wasn't scheduled successfully
        if (!alarmScheduled) {
            scheduleRestartWithHandler(
                activity,
                timeUntilNextRestart,
                RestartType.ACTIVITY
            )
        } else {
            // If using exact alarms on devices with background restrictions,
            // still use the handler as a backup to ensure restart happens
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // For Android 9+ devices, set a slightly delayed handler backup
                val backupDelay = timeUntilNextRestart + (2 * 60 * 1000) // 2 minutes extra
                scheduleRestartWithHandler(
                    activity,
                    backupDelay,
                    RestartType.ACTIVITY
                )
                Log.d(TAG, "Set backup handler restart for 2 minutes after scheduled alarm")
            }
        }

        isActivityRestartScheduled = true
        Log.d(TAG, "Next activity restart scheduled at ${formatTime(currentTime + timeUntilNextRestart)}")

        // Store the planned restart time for reference
        prefs.edit().putLong("next_activity_restart_time", currentTime + timeUntilNextRestart).apply()
    }

    /**
     * Schedule a restart using Handler (in-memory approach)
     * This works even without alarm permissions
     */
    private fun scheduleRestartWithHandler(activity: Activity, delay: Long, restartType: RestartType) {
        val handler = if (restartType == RestartType.ACTIVITY) activityRestartHandler else processRestartHandler

        // Cancel any existing handler
        if (restartType == RestartType.ACTIVITY) {
            activityRestartRunnable?.let { handler.removeCallbacks(it) }
        } else {
            processRestartRunnable?.let { handler.removeCallbacks(it) }
        }

        // Create new runnable
        val runnable = Runnable {
            try {
                // Store reference to activity weakly to prevent memory leaks
                val activityRef = WeakReference(activity)
                val currentActivity = activityRef.get()

                // Check if the activity is still valid
                if (currentActivity == null || currentActivity.isFinishing ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && currentActivity.isDestroyed)) {
                    Log.w(TAG, "Activity no longer valid, cannot restart")
                    return@Runnable
                }

                Log.d(TAG, "Handler-initiated ${restartType.name} restart")

                // Mark restart time in preferences
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val prefKey = if (restartType == RestartType.ACTIVITY)
                    PREF_KEY_LAST_ACTIVITY_RESTART else PREF_KEY_LAST_PROCESS_RESTART

                prefs.edit().putLong(prefKey, System.currentTimeMillis()).apply()

                // Mark that we're performing a restart
                // (This is used to ensure we don't pick the same photo after restart)
                prefs.edit().putBoolean("is_performing_restart", true).apply()

                // Perform the restart
                when (restartType) {
                    RestartType.ACTIVITY -> restartActivity(currentActivity)
                    RestartType.PROCESS -> restartProcess(currentActivity)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in handler-initiated restart", e)
            }
        }

        // Store and schedule
        if (restartType == RestartType.ACTIVITY) {
            activityRestartRunnable = runnable
        } else {
            processRestartRunnable = runnable
        }

        handler.postDelayed(runnable, delay)

        Log.d(TAG, "Scheduled ${restartType.name} restart with handler in ${delay/1000} seconds")
    }

    fun cancelScheduledRestarts() {
        // Cancel handler-based restarts
        activityRestartRunnable?.let { activityRestartHandler.removeCallbacks(it) }
        processRestartRunnable?.let { processRestartHandler.removeCallbacks(it) }
        activityRestartRunnable = null
        processRestartRunnable = null

        // Try to cancel alarm-based restarts
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val activityIntent = Intent(context, RestartBroadcastReceiver::class.java)
            val activityPendingIntent = PendingIntent.getBroadcast(
                context,
                ACTIVITY_RESTART_REQUEST_CODE,
                activityIntent,
                PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            activityPendingIntent?.let { alarmManager.cancel(it) }

            val processIntent = Intent(context, RestartBroadcastReceiver::class.java)
            val processPendingIntent = PendingIntent.getBroadcast(
                context,
                PROCESS_RESTART_REQUEST_CODE,
                processIntent,
                PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            processPendingIntent?.let { alarmManager.cancel(it) }

            Log.d(TAG, "Scheduled restarts cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling alarms", e)
        }
    }

    /**
     * Schedule the process-level restart
     */
    fun scheduleProcessRestart(activity: Activity) {
        if (isProcessRestartScheduled) {
            Log.d(TAG, "Process restart already scheduled")
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastRestartTime = prefs.getLong(PREF_KEY_LAST_PROCESS_RESTART, 0L)
        val currentTime = System.currentTimeMillis()

        val timeUntilNextRestart = calculateNextRestartTime(
            lastRestartTime,
            currentTime,
            PROCESS_RESTART_INTERVAL_MS
        )

        Log.d(TAG, "Scheduling process restart in ${timeUntilNextRestart/60000} minutes")

        scheduleRestart(
            PROCESS_RESTART_REQUEST_CODE,
            timeUntilNextRestart,
            RestartType.PROCESS
        )

        isProcessRestartScheduled = true
        Log.d(TAG, "Next process restart scheduled at ${formatTime(currentTime + timeUntilNextRestart)}")
    }

    /**
     * Calculate time until next restart
     */
    private fun calculateNextRestartTime(lastRestart: Long, currentTime: Long, interval: Long): Long {
        val timeSinceLastRestart = currentTime - lastRestart

        if (lastRestart == 0L) {
            // First time - schedule at a random time within next interval/2
            // This helps stagger restarts if multiple devices are deployed
            return interval / 2 + Random.nextLong(interval / 2)
        }

        val remainingTime = interval - timeSinceLastRestart
        if (remainingTime <= 0) {
            // Overdue, schedule soon but not immediately
            return TimeUnit.MINUTES.toMillis(2) + Random.nextLong(TimeUnit.MINUTES.toMillis(3))
        }

        return remainingTime
    }

    /**
     * Schedule a restart using AlarmManager
     */
    private fun scheduleRestart(requestCode: Int, delay: Long, restartType: RestartType) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, RestartBroadcastReceiver::class.java).apply {
                action = "com.photostreamr.ACTION_RESTART"
                putExtra("restart_type", restartType.ordinal)
                putExtra("timestamp", System.currentTimeMillis())
            }

            Log.d(TAG, "Creating pending intent for ${restartType.name} restart")

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                flags
            )

            Log.d(TAG, "Setting alarm for ${restartType.name} restart in ${delay/1000} seconds")

            // Try to use exact alarm first, fall back to inexact if permission is missing
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Successfully scheduled exact ${restartType.name} restart")
            } catch (e: SecurityException) {
                // Fall back to inexact alarm if we don't have permission
                Log.w(TAG, "No permission for exact alarm, using inexact alarm instead", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Successfully scheduled inexact ${restartType.name} restart")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule ${restartType.name} restart", e)
        }
    }

    /**
     * Initiate restart with specified type
     */
    fun initiateRestart(activity: Activity, restartType: Int) {
        try {
            val type = if (restartType == RESTART_TYPE_PROCESS)
                RestartType.PROCESS else RestartType.ACTIVITY

            Log.d(TAG, "Initiating ${type.name.lowercase()} restart with transition")

            // Mark restart time in preferences
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val prefKey = if (type == RestartType.ACTIVITY)
                PREF_KEY_LAST_ACTIVITY_RESTART else PREF_KEY_LAST_PROCESS_RESTART

            prefs.edit().putLong(prefKey, System.currentTimeMillis()).apply()

            // Mark that we're performing a restart
            // (This is used to ensure we don't pick the same photo after restart)
            prefs.edit().putBoolean("is_performing_restart", true).apply()

            // Create and show black overlay with fade-in animation
            showRestartTransition(activity) {
                when (type) {
                    RestartType.ACTIVITY -> restartActivity(activity)
                    RestartType.PROCESS -> restartProcess(activity)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during restart transition", e)
            // Fallback to direct activity restart if transition fails
            restartActivity(activity)
        }
    }

    /**
     * Show a black screen transition
     */
    private fun showRestartTransition(activity: Activity, onComplete: () -> Unit) {
        try {
            val decorView = activity.window.decorView as ViewGroup

            // Create black overlay
            restartOverlayView = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                alpha = 0f
            }

            // Add overlay to window
            decorView.addView(restartOverlayView)

            // Animate fade to black
            restartOverlayView?.animate()
                ?.alpha(1f)
                ?.setDuration(TRANSITION_DURATION_MS)
                ?.withEndAction {
                    // Give some time for animation to complete
                    handler.postDelayed({
                        onComplete()
                    }, 50)
                }
                ?.start()

            Log.d(TAG, "Restart transition overlay added")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing restart transition", e)
            onComplete()
        }
    }

    /**
     * Actually restart the activity only
     */
    private fun restartActivity(activity: Activity) {
        try {
            Log.d(TAG, "Executing activity restart")

            // Add a small delay to ensure animation is visible
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Get main activity class
                    val mainActivityClass = activity.javaClass

                    // For Android 8, use a more aggressive approach
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O ||
                        Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {

                        Log.d(TAG, "Using specialized Android 8 restart approach")

                        // First make sure we have the restart flag in preferences
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit()
                            .putBoolean("is_performing_restart", true)
                            .putLong("last_restart_timestamp", System.currentTimeMillis())
                            .apply()

                        // Create intent with specific flags for Android 8
                        val intent = Intent(activity, mainActivityClass).apply {
                            // These flags are particularly important for Android 8
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

                            putExtra("restarted", true)
                            putExtra("restart_type", RestartType.ACTIVITY.ordinal)
                            putExtra("restart_timestamp", System.currentTimeMillis())
                        }

                        // Finish the current activity first to ensure it's properly destroyed
                        activity.finish()

                        // Very short delay to allow finish to complete
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                // Start the new instance
                                activity.startActivity(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in delayed Android 8 activity restart", e)
                            }
                        }, 100)

                    } else {
                        // Standard approach for other Android versions
                        val intent = Intent(activity, mainActivityClass).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra("restarted", true)
                            putExtra("restart_type", RestartType.ACTIVITY.ordinal)
                        }

                        activity.startActivity(intent)
                        activity.finish()

                        // Simple fade for the transition
                        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delayed restart sequence", e)
                }
            }, 500) // Wait for animation to be visible

        } catch (e: Exception) {
            Log.e(TAG, "Error restarting activity", e)
        }
    }

    /**
     * Force a very aggressive restart on Android 8
     * This is only used as a last resort
     */
    fun forceRestartOnAndroid8(activity: Activity) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1) {
            Log.d(TAG, "Force restart method only needed on Android 8, current version: ${Build.VERSION.SDK_INT}")
            return
        }

        try {
            Log.d(TAG, "Performing forced restart for Android 8")


            // Make sure restart preferences are set
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit()
                .putBoolean("is_performing_restart", true)
                .putLong("last_restart_timestamp", System.currentTimeMillis())
                .apply()

            // Create a pending intent that will restart the app
            val packageManager = activity.packageManager
            val intent = packageManager.getLaunchIntentForPackage(activity.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.putExtra("restarted", true)
            intent?.putExtra("restart_type", RestartType.PROCESS.ordinal)
            intent?.putExtra("force_restarted", true)

            val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

            val pendingIntent = PendingIntent.getActivity(activity, 0, intent, flags)

            // Use AlarmManager to restart app very soon
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent)

            // Finish current activity
            activity.finish()

            // Wait a moment and exit process
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // This will force the app to be completely restarted by the system
                    exitProcess(0)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to exit process cleanly", e)
                    // As a last resort, crash the app to force a restart
                    throw RuntimeException("Forced restart")
                }
            }, 300)

        } catch (e: Exception) {
            Log.e(TAG, "Error in forced Android 8 restart", e)
        }
    }

    /**
     * Actually restart the entire process
     */
    private fun restartProcess(activity: Activity) {
        try {
            Log.d(TAG, "Executing process restart")

            // Create intent to restart app
            val packageManager = activity.packageManager
            val intent = packageManager.getLaunchIntentForPackage(activity.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.putExtra("restarted", true)
            intent?.putExtra("restart_type", RestartType.PROCESS.ordinal)
            intent?.putExtra("restart_timestamp", System.currentTimeMillis())

            // Create pending intent that will restart the app
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

            val pendingIntent = PendingIntent.getActivity(
                activity, 0, intent, flags
            )

            // Use AlarmManager to restart in 100ms after process death
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // For Android 8, we need to start the activity first, then kill the process
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.O_MR1) {

                Log.d(TAG, "Using modified process kill for Android 8")

                // First set the alarm as backup
                try {
                    alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set alarm for Android 8 restart", e)
                }

                // Start a new instance of the app
                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start new activity", e)
                }

                // Give it a moment to start before killing
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Explicitly finish the activity first
                        activity.finish()

                        // Then kill the process
                        Log.d(TAG, "Killing process for Android 8 restart")
                        android.os.Process.killProcess(android.os.Process.myPid())
                        System.exit(0)  // As a backup
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to kill process", e)
                    }
                }, 500)  // Give it half a second

                return
            }

            // For other Android versions, use the original approach
            alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pendingIntent)

            // Kill the entire process
            android.os.Process.killProcess(android.os.Process.myPid())

        } catch (e: Exception) {
            Log.e(TAG, "Error restarting process", e)
            // Fall back to activity restart
            restartActivity(activity)
        }
    }

    private fun formatTime(timeMs: Long): String {
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(timeMs))
    }

    enum class RestartType {
        ACTIVITY, PROCESS
    }
}