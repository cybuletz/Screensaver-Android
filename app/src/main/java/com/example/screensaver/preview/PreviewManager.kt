package com.example.screensaver.preview

import android.content.Context
import android.os.SystemClock
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {
    private val _canStartPreview = MutableStateFlow(true)
    val canStartPreview: StateFlow<Boolean> = _canStartPreview

    private var isPreviewActive = false
    private var lastPreviewTime = 0L
    private var dailyPreviewCount = 0
    private var lastResetTime = 0L

    companion object {
        private const val MIN_PREVIEW_INTERVAL = 5000L // 5 seconds between previews
        private const val MAX_DAILY_PREVIEWS = 10
        private const val PREVIEW_COUNT_RESET_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours

        private const val PREF_LAST_PREVIEW_TIME = "last_preview_time"
        private const val PREF_DAILY_PREVIEW_COUNT = "daily_preview_count"
        private const val PREF_LAST_RESET_TIME = "last_reset_time"
    }

    init {
        loadPreviewStats()
        checkAndResetDailyCount()
    }

    fun canStartPreview(): Boolean {
        checkAndResetDailyCount()

        val currentTime = SystemClock.elapsedRealtime()
        val timeSinceLastPreview = currentTime - lastPreviewTime

        return !isPreviewActive &&
                dailyPreviewCount < MAX_DAILY_PREVIEWS &&
                timeSinceLastPreview >= MIN_PREVIEW_INTERVAL
    }

    fun getTimeUntilNextPreviewAllowed(): Long {
        val currentTime = SystemClock.elapsedRealtime()
        val timeSinceLastPreview = currentTime - lastPreviewTime

        return if (timeSinceLastPreview < MIN_PREVIEW_INTERVAL) {
            MIN_PREVIEW_INTERVAL - timeSinceLastPreview
        } else {
            0L
        }
    }

    fun startPreview() {
        if (canStartPreview()) {
            isPreviewActive = true
            lastPreviewTime = SystemClock.elapsedRealtime()
            dailyPreviewCount++
            savePreviewStats()
            updatePreviewState()
        }
    }

    fun endPreview() {
        isPreviewActive = false
        updatePreviewState()
        savePreviewStats()
    }

    fun getRemainingPreviews(): Int {
        checkAndResetDailyCount()
        return MAX_DAILY_PREVIEWS - dailyPreviewCount
    }

    fun isInPreviewMode(): Boolean = isPreviewActive

    private fun updatePreviewState() {
        _canStartPreview.value = canStartPreview()
    }

    private fun checkAndResetDailyCount() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastResetTime >= PREVIEW_COUNT_RESET_INTERVAL) {
            resetPreviewStats()
        }
    }

    fun resetPreviewStats() {
        dailyPreviewCount = 0
        lastResetTime = SystemClock.elapsedRealtime()
        lastPreviewTime = 0L
        savePreviewStats()
        updatePreviewState()
    }

    private fun savePreviewStats() {
        preferences.edit {
            putLong(PREF_LAST_PREVIEW_TIME, lastPreviewTime)
            putInt(PREF_DAILY_PREVIEW_COUNT, dailyPreviewCount)
            putLong(PREF_LAST_RESET_TIME, lastResetTime)
        }
    }

    private fun loadPreviewStats() {
        with(preferences) {
            lastPreviewTime = getLong(PREF_LAST_PREVIEW_TIME, 0L)
            dailyPreviewCount = getInt(PREF_DAILY_PREVIEW_COUNT, 0)
            lastResetTime = getLong(PREF_LAST_RESET_TIME, SystemClock.elapsedRealtime())
        }
    }

}