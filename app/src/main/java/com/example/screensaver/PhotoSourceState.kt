package com.example.screensaver

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoSourceState @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("photo_source_prefs", Context.MODE_PRIVATE)

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

    fun reset() {
        prefs.edit().apply {
            remove("has_selected_source")
            remove("has_selected_photos")
            remove("last_preview_timestamp")
            remove("preview_count")
            apply()
        }
    }

    fun isScreensaverReady(): Boolean {
        return hasSelectedSource && hasSelectedPhotos
    }

    fun recordPreviewStarted() {
        lastPreviewTimestamp = System.currentTimeMillis()
        previewCount++
    }

    fun getTimeSinceLastPreview(): Long {
        return if (lastPreviewTimestamp == 0L) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() - lastPreviewTimestamp
        }
    }

    fun resetPreviewStats() {
        prefs.edit().apply {
            remove("last_preview_timestamp")
            remove("preview_count")
            apply()
        }
    }
}