package com.example.screensaver

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoSourceState @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("photo_source_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_DAILY_PREVIEWS = 10
        private const val MIN_PREVIEW_INTERVAL = 5000L // 5 seconds between previews
        private const val PREVIEW_COUNT_RESET_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
    }

    var hasSelectedSource: Boolean
        get() = prefs.getBoolean("has_selected_source", false)
        set(value) = prefs.edit().putBoolean("has_selected_source", value).apply()

    var hasSelectedPhotos: Boolean
        get() = prefs.getBoolean("has_selected_photos", false)
        set(value) = prefs.edit().putBoolean("has_selected_photos", value).apply()

    var lastPreviewTimestamp: Long
        get() = prefs.getLong("last_preview_timestamp", 0)
        private set(value) = prefs.edit().putLong("last_preview_timestamp", value).apply()

    var previewCount: Int
        get() = prefs.getInt("preview_count", 0)
        private set(value) = prefs.edit().putInt("preview_count", value).apply()

    private var lastPreviewResetTime: Long
        get() = prefs.getLong("last_preview_reset_time", 0)
        set(value) = prefs.edit().putLong("last_preview_reset_time", value).apply()

    var isInPreviewMode: Boolean
        get() = prefs.getBoolean("is_in_preview_mode", false)
        set(value) = prefs.edit().putBoolean("is_in_preview_mode", value).apply()

    fun reset() {
        prefs.edit().apply {
            remove("has_selected_source")
            remove("has_selected_photos")
            remove("last_preview_timestamp")
            remove("preview_count")
            remove("last_preview_reset_time")
            remove("is_in_preview_mode")
            apply()
        }
    }

    fun isScreensaverReady(): Boolean {
        return hasSelectedSource && hasSelectedPhotos
    }

    fun canStartPreview(): Boolean {
        checkAndResetDailyCount()
        return isScreensaverReady() &&
                getTimeSinceLastPreview() >= MIN_PREVIEW_INTERVAL &&
                previewCount < MAX_DAILY_PREVIEWS
    }

    fun recordPreviewStarted() {
        checkAndResetDailyCount()
        lastPreviewTimestamp = System.currentTimeMillis()
        previewCount++
        isInPreviewMode = true
    }

    fun recordPreviewEnded() {
        isInPreviewMode = false
    }

    fun getTimeSinceLastPreview(): Long {
        return if (lastPreviewTimestamp == 0L) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() - lastPreviewTimestamp
        }
    }

    fun getRemainingPreviewsToday(): Int {
        checkAndResetDailyCount()
        return MAX_DAILY_PREVIEWS - previewCount
    }

    fun getTimeUntilNextPreviewAllowed(): Long {
        val timeSinceLastPreview = getTimeSinceLastPreview()
        return if (timeSinceLastPreview < MIN_PREVIEW_INTERVAL) {
            MIN_PREVIEW_INTERVAL - timeSinceLastPreview
        } else {
            0L
        }
    }

    private fun checkAndResetDailyCount() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPreviewResetTime >= PREVIEW_COUNT_RESET_INTERVAL) {
            resetPreviewStats()
            lastPreviewResetTime = currentTime
        }
    }

    fun resetPreviewStats() {
        prefs.edit().apply {
            remove("last_preview_timestamp")
            remove("preview_count")
            remove("is_in_preview_mode")
            apply()
        }
    }
}