package com.example.screensaver.analytics

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages analytics tracking and reporting for the application.
 * Handles event logging, performance monitoring, and error tracking.
 */
class PhotoAnalytics(private val context: Context) {

    private val firebaseAnalytics: FirebaseAnalytics = Firebase.analytics
    private val crashlytics: FirebaseCrashlytics = FirebaseCrashlytics.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sessionData = ConcurrentHashMap<String, Any>()

    companion object {
        // Event Names
        private const val EVENT_APP_LAUNCH = "app_launch"
        private const val EVENT_SCREENSAVER_START = "screensaver_start"
        private const val EVENT_SCREENSAVER_STOP = "screensaver_stop"
        private const val EVENT_PHOTO_DISPLAYED = "photo_displayed"
        private const val EVENT_PHOTO_LOAD_ERROR = "photo_load_error"
        private const val EVENT_ALBUM_SELECTED = "album_selected"
        private const val EVENT_SETTINGS_CHANGED = "settings_changed"
        private const val EVENT_NETWORK_ERROR = "network_error"
        private const val EVENT_PERMISSION_GRANTED = "permission_granted"
        private const val EVENT_PERMISSION_DENIED = "permission_denied"

        // Parameter Names
        private const val PARAM_SESSION_DURATION = "session_duration"
        private const val PARAM_PHOTO_COUNT = "photo_count"
        private const val PARAM_ERROR_TYPE = "error_type"
        private const val PARAM_NETWORK_TYPE = "network_type"
        private const val PARAM_SETTING_NAME = "setting_name"
        private const val PARAM_SETTING_VALUE = "setting_value"
        private const val PARAM_PERMISSION_TYPE = "permission_type"
        private const val PARAM_LOAD_TIME = "load_time"
        private const val PARAM_MEMORY_USAGE = "memory_usage"
        private const val PARAM_CACHE_SIZE = "cache_size"
    }

    init {
        // Enable analytics collection
        firebaseAnalytics.setAnalyticsCollectionEnabled(true)
        crashlytics.setCrashlyticsCollectionEnabled(true)
    }

    /**
     * Tracks application launch
     */
    fun trackAppLaunch() {
        logEvent(EVENT_APP_LAUNCH, bundleOf(
            "device_type" to android.os.Build.MODEL,
            "android_version" to android.os.Build.VERSION.SDK_INT
        ))
    }

    /**
     * Tracks screensaver session
     */
    fun trackScreensaverSession(start: Boolean, sessionDuration: Long = 0) {
        val event = if (start) EVENT_SCREENSAVER_START else EVENT_SCREENSAVER_STOP
        logEvent(event, bundleOf(
            PARAM_SESSION_DURATION to sessionDuration,
            PARAM_PHOTO_COUNT to sessionData.getOrDefault("photo_count", 0)
        ))
    }

    /**
     * Tracks photo display
     */
    fun trackPhotoDisplayed(loadTime: Long, isFromCache: Boolean) {
        logEvent(EVENT_PHOTO_DISPLAYED, bundleOf(
            PARAM_LOAD_TIME to loadTime,
            "from_cache" to isFromCache,
            PARAM_MEMORY_USAGE to getAppMemoryUsage(),
            PARAM_CACHE_SIZE to sessionData.getOrDefault(PARAM_CACHE_SIZE, 0L)
        ))
        incrementPhotoCount()
    }

    /**
     * Tracks photo loading errors
     */
    fun trackPhotoLoadError(error: Throwable, photoUrl: String?) {
        logEvent(EVENT_PHOTO_LOAD_ERROR, bundleOf(
            PARAM_ERROR_TYPE to error.javaClass.simpleName,
            "photo_url" to (photoUrl ?: "unknown")
        ))
        crashlytics.recordException(error)
    }

    /**
     * Tracks album selection
     */
    fun trackAlbumSelection(albumId: String, photoCount: Int) {
        logEvent(EVENT_ALBUM_SELECTED, bundleOf(
            "album_id" to albumId,
            PARAM_PHOTO_COUNT to photoCount
        ))
    }

    /**
     * Tracks settings changes
     */
    fun trackSettingsChanged(settingName: String, newValue: Any) {
        logEvent(EVENT_SETTINGS_CHANGED, bundleOf(
            PARAM_SETTING_NAME to settingName,
            PARAM_SETTING_VALUE to newValue.toString()
        ))
    }

    /**
     * Tracks network errors
     */
    fun trackNetworkError(error: Throwable, networkType: String?) {
        logEvent(EVENT_NETWORK_ERROR, bundleOf(
            PARAM_ERROR_TYPE to error.javaClass.simpleName,
            PARAM_NETWORK_TYPE to (networkType ?: "unknown")
        ))
        crashlytics.recordException(error)
    }

    /**
     * Tracks permission changes
     */
    fun trackPermissionStatus(permissionType: String, granted: Boolean) {
        val event = if (granted) EVENT_PERMISSION_GRANTED else EVENT_PERMISSION_DENIED
        logEvent(event, bundleOf(
            PARAM_PERMISSION_TYPE to permissionType
        ))
    }

    /**
     * Tracks performance metrics
     */
    fun trackPerformanceMetrics(metrics: Map<String, Any>) {
        metrics.forEach { (key, value) ->
            sessionData[key] = value
        }

        // Log if significant changes
        if (shouldLogPerformanceMetrics(metrics)) {
            logEvent("performance_metrics", bundleOf(
                PARAM_MEMORY_USAGE to getAppMemoryUsage(),
                PARAM_CACHE_SIZE to (metrics[PARAM_CACHE_SIZE] ?: 0L),
                "frame_rate" to (metrics["frame_rate"] ?: 0f)
            ))
        }
    }

    /**
     * Logs custom events
     */
    private fun logEvent(eventName: String, params: Bundle) {
        scope.launch {
            try {
                firebaseAnalytics.logEvent(eventName, params)
            } catch (e: Exception) {
                crashlytics.recordException(e)
            }
        }
    }

    /**
     * Gets application memory usage
     */
    private fun getAppMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * Increments photo count in session data
     */
    private fun incrementPhotoCount() {
        val currentCount = sessionData.getOrDefault("photo_count", 0) as Int
        sessionData["photo_count"] = currentCount + 1
    }

    /**
     * Determines if performance metrics should be logged
     */
    private fun shouldLogPerformanceMetrics(metrics: Map<String, Any>): Boolean {
        // Log if memory usage increased significantly or cache size changed dramatically
        val previousMemoryUsage = sessionData.getOrDefault(PARAM_MEMORY_USAGE, 0L) as Long
        val currentMemoryUsage = metrics[PARAM_MEMORY_USAGE] as? Long ?: 0L

        return abs(currentMemoryUsage - previousMemoryUsage) > 10_000_000 // 10MB threshold
    }

    /**
     * Sets user property for analytics
     */
    fun setUserProperty(name: String, value: String) {
        firebaseAnalytics.setUserProperty(name, value)
    }

    /**
     * Cleans up analytics resources
     */
    fun cleanup() {
        sessionData.clear()
    }
}