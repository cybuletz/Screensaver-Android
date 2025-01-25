package com.example.screensaver.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages application preferences and settings.
 * Handles persistence and provides type-safe access to user preferences.
 */


@Singleton
class AppPreferences @Inject constructor(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            PREF_DISPLAY_MODE -> _displayModeFlow.value = getDisplayMode()
            PREF_TRANSITION_INTERVAL -> _transitionIntervalFlow.value = getTransitionInterval()
            PREF_TRANSITION_ANIMATION -> _transitionAnimationFlow.value = getTransitionAnimation()
            PREF_SHOW_PHOTO_INFO -> _showPhotoInfoFlow.value = isShowPhotoInfo()
            PREF_SHOW_CLOCK -> _showClockFlow.value = isShowClock()
            PREF_CLOCK_FORMAT -> _clockFormatFlow.value = getClockFormat()
            PREF_SELECTED_ALBUMS -> _selectedAlbumsFlow.value = getSelectedAlbumIds()
            PREF_KIOSK_MODE_ENABLED -> _kioskModeEnabledFlow.value = isKioskModeEnabled()
        }
    }

    // StateFlows for reactive preferences
    private val _displayModeFlow = MutableStateFlow(getDisplayMode())
    private val _transitionIntervalFlow = MutableStateFlow(getTransitionInterval())
    private val _transitionAnimationFlow = MutableStateFlow(getTransitionAnimation())
    private val _showPhotoInfoFlow = MutableStateFlow(isShowPhotoInfo())
    private val _showClockFlow = MutableStateFlow(isShowClock())
    private val _clockFormatFlow = MutableStateFlow(getClockFormat())
    private val _selectedAlbumsFlow = MutableStateFlow(getSelectedAlbumIds())
    private val _kioskModeEnabledFlow = MutableStateFlow(isKioskModeEnabled())

    private val _previewCountFlow = MutableStateFlow(getPreviewCount())
    val previewCountFlow = _previewCountFlow.asStateFlow()


    // Public flows
    val displayModeFlow = _displayModeFlow.asStateFlow()
    val transitionIntervalFlow = _transitionIntervalFlow.asStateFlow()
    val transitionAnimationFlow = _transitionAnimationFlow.asStateFlow()
    val showPhotoInfoFlow = _showPhotoInfoFlow.asStateFlow()
    val showClockFlow = _showClockFlow.asStateFlow()
    val clockFormatFlow = _clockFormatFlow.asStateFlow()
    val selectedAlbumsFlow = _selectedAlbumsFlow.asStateFlow()
    val kioskModeEnabledFlow = _kioskModeEnabledFlow.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    companion object {
        // Preference keys
        private const val PREF_DISPLAY_MODE = "display_mode_selection"
        private const val PREF_TRANSITION_INTERVAL = "transition_interval"
        private const val PREF_TRANSITION_ANIMATION = "transition_animation"
        private const val PREF_SHOW_PHOTO_INFO = "show_photo_info"
        private const val PREF_SHOW_CLOCK = "show_clock"
        private const val PREF_CLOCK_FORMAT = "clock_format"
        private const val PREF_SELECTED_ALBUMS = "selected_albums"
        private const val PREF_LAST_SYNC = "last_sync_timestamp"

        // Default values
        private const val DEFAULT_DISPLAY_MODE = "dream_service"
        private const val DEFAULT_TRANSITION_INTERVAL = 30 // seconds
        private const val DEFAULT_TRANSITION_ANIMATION = "fade"
        private const val DEFAULT_CLOCK_FORMAT = "24h"

        private const val PREF_TRANSITION_DURATION = "transition_duration"
        private const val PREF_PHOTO_QUALITY = "photo_quality"
        private const val PREF_RANDOM_ORDER = "random_order"
        private const val PREF_SHOW_LOCATION = "show_location"
        private const val PREF_SHOW_DATE = "show_date"
        private const val PREF_ENABLE_TRANSITIONS = "enable_transitions"
        private const val PREF_DARK_MODE = "dark_mode"
        private const val PREF_KIOSK_MODE_ENABLED = "kiosk_mode_enabled"
        private const val PREF_KIOSK_SETTINGS_TIMEOUT = "kiosk_settings_timeout"

        private const val PREF_PREVIEW_COOLDOWN = "preview_cooldown"
        private const val PREF_PREVIEW_COUNT = "preview_count"
        private const val PREF_LAST_PREVIEW = "last_preview_timestamp"
        private const val MAX_PREVIEW_COUNT = 5
        private const val PREVIEW_COOLDOWN_DURATION = 3600000L

    }

    enum class DisplayMode {
        DREAM_SERVICE, LOCK_SCREEN
    }

    enum class TransitionAnimation {
        FADE, SLIDE, ZOOM
    }

    enum class ClockFormat {
        FORMAT_12H, FORMAT_24H
    }


    fun getPreviewCount(): Int = prefs.getInt(PREF_PREVIEW_COUNT, 0)

    fun getRemainingPreviews(): Int = MAX_PREVIEW_COUNT - getPreviewCount()

    fun canStartPreview(): Boolean {
        val lastPreview = prefs.getLong(PREF_LAST_PREVIEW, 0)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastPreview = currentTime - lastPreview

        return getPreviewCount() < MAX_PREVIEW_COUNT ||
                timeSinceLastPreview > PREVIEW_COOLDOWN_DURATION
    }

    fun recordPreviewStart() {
        prefs.edit {
            putInt(PREF_PREVIEW_COUNT, getPreviewCount() + 1)
            putLong(PREF_LAST_PREVIEW, System.currentTimeMillis())
        }
        _previewCountFlow.value = getPreviewCount()
    }

    fun getTimeUntilNextPreviewAllowed(): Long {
        val lastPreview = prefs.getLong(PREF_LAST_PREVIEW, 0)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastPreview = currentTime - lastPreview

        return if (timeSinceLastPreview < PREVIEW_COOLDOWN_DURATION) {
            PREVIEW_COOLDOWN_DURATION - timeSinceLastPreview
        } else {
            0L
        }
    }

    fun resetPreviewCount() {
        prefs.edit {
            putInt(PREF_PREVIEW_COUNT, 0)
            putLong(PREF_LAST_PREVIEW, 0)
        }
        _previewCountFlow.value = 0
    }

    // Display Mode
    fun getDisplayMode(): DisplayMode = when(prefs.getString(PREF_DISPLAY_MODE, DEFAULT_DISPLAY_MODE)) {
        "lock_screen" -> DisplayMode.LOCK_SCREEN
        else -> DisplayMode.DREAM_SERVICE
    }

    fun setDisplayMode(mode: DisplayMode) {
        prefs.edit {
            putString(PREF_DISPLAY_MODE, when(mode) {
                DisplayMode.LOCK_SCREEN -> "lock_screen"
                DisplayMode.DREAM_SERVICE -> "dream_service"
            })
        }
    }

    fun getTransitionDuration(): Int =
        prefs.getInt(PREF_TRANSITION_DURATION, 30)

    fun setTransitionDuration(duration: Int) {
        prefs.edit { putInt(PREF_TRANSITION_DURATION, duration) }
    }

    fun getPhotoQuality(): Int =
        prefs.getInt(PREF_PHOTO_QUALITY, 1)

    fun setPhotoQuality(quality: Int) {
        prefs.edit { putInt(PREF_PHOTO_QUALITY, quality) }
    }

    fun getRandomOrder(): Boolean =
        prefs.getBoolean(PREF_RANDOM_ORDER, true)

    fun setRandomOrder(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_RANDOM_ORDER, enabled) }
    }

    fun getShowLocation(): Boolean =
        prefs.getBoolean(PREF_SHOW_LOCATION, false)

    fun setShowLocation(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_SHOW_LOCATION, enabled) }
    }

    fun getShowDate(): Boolean =
        prefs.getBoolean(PREF_SHOW_DATE, true)

    fun setShowDate(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_SHOW_DATE, enabled) }
    }

    fun getEnableTransitions(): Boolean =
        prefs.getBoolean(PREF_ENABLE_TRANSITIONS, true)

    fun setEnableTransitions(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_ENABLE_TRANSITIONS, enabled) }
    }

    fun getDarkMode(): Boolean =
        prefs.getBoolean(PREF_DARK_MODE, false)

    fun setDarkMode(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_DARK_MODE, enabled) }
    }

    // Transition Interval
    fun getTransitionInterval(): Int =
        prefs.getInt(PREF_TRANSITION_INTERVAL, DEFAULT_TRANSITION_INTERVAL)

    fun setTransitionInterval(seconds: Int) {
        prefs.edit { putInt(PREF_TRANSITION_INTERVAL, seconds) }
    }

    // Transition Animation
    fun getTransitionAnimation(): TransitionAnimation {
        val prefValue = getString("transition_animation", "fade")
        return when (prefValue?.lowercase()) {
            "slide" -> TransitionAnimation.SLIDE
            "zoom" -> TransitionAnimation.ZOOM
            else -> TransitionAnimation.FADE
        }
    }

    fun setTransitionAnimation(animation: TransitionAnimation) {
        prefs.edit { putString(PREF_TRANSITION_ANIMATION, animation.name.lowercase()) }
    }

    // Photo Info Display
    fun isShowPhotoInfo(): Boolean = prefs.getBoolean(PREF_SHOW_PHOTO_INFO, true)

    fun setShowPhotoInfo(show: Boolean) {
        prefs.edit { putBoolean(PREF_SHOW_PHOTO_INFO, show) }
    }

    // Clock Display
    fun isShowClock(): Boolean = prefs.getBoolean(PREF_SHOW_CLOCK, true)

    fun setShowClock(show: Boolean) {
        prefs.edit { putBoolean(PREF_SHOW_CLOCK, show) }
    }

    // Clock Format
    fun getClockFormat(): ClockFormat = when(prefs.getString(PREF_CLOCK_FORMAT, DEFAULT_CLOCK_FORMAT)) {
        "12h" -> ClockFormat.FORMAT_12H
        else -> ClockFormat.FORMAT_24H
    }

    fun setClockFormat(format: ClockFormat) {
        prefs.edit {
            putString(PREF_CLOCK_FORMAT, when(format) {
                ClockFormat.FORMAT_12H -> "12h"
                ClockFormat.FORMAT_24H -> "24h"
            })
        }
    }

    // Selected Albums
    fun getSelectedAlbumIds(): Set<String> =
        prefs.getStringSet(PREF_SELECTED_ALBUMS, emptySet()) ?: emptySet()

    fun setSelectedAlbumIds(albumIds: Set<String>) {
        prefs.edit { putStringSet(PREF_SELECTED_ALBUMS, albumIds) }
    }

    fun addSelectedAlbumId(albumId: String) {
        val currentIds = getSelectedAlbumIds().toMutableSet()
        currentIds.add(albumId)
        setSelectedAlbumIds(currentIds)
    }

    fun removeSelectedAlbumId(albumId: String) {
        val currentIds = getSelectedAlbumIds().toMutableSet()
        currentIds.remove(albumId)
        setSelectedAlbumIds(currentIds)
    }

    // Last Sync Time
    fun getLastSyncTimestamp(): Long = prefs.getLong(PREF_LAST_SYNC, 0)

    fun updateLastSyncTimestamp() {
        prefs.edit { putLong(PREF_LAST_SYNC, System.currentTimeMillis()) }
    }

    /**
     * Resets all preferences to their default values
     */
    fun resetToDefaults() {
        prefs.edit {
            // ... existing reset preferences ...
            putInt(PREF_TRANSITION_DURATION, 30)
            putInt(PREF_PHOTO_QUALITY, 1)
            putBoolean(PREF_RANDOM_ORDER, true)
            putBoolean(PREF_SHOW_LOCATION, false)
            putBoolean(PREF_SHOW_DATE, true)
            putBoolean(PREF_ENABLE_TRANSITIONS, true)
            putBoolean(PREF_DARK_MODE, false)
            putBoolean(PREF_KIOSK_MODE_ENABLED, false)
            putInt(PREF_KIOSK_SETTINGS_TIMEOUT, 5)
        }
    }

    // Kiosk Mode
    fun isKioskModeEnabled(): Boolean =
        prefs.getBoolean(PREF_KIOSK_MODE_ENABLED, false)

    fun setKioskModeEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_KIOSK_MODE_ENABLED, enabled) }
    }

    fun getKioskSettingsTimeout(): Int =
        prefs.getInt(PREF_KIOSK_SETTINGS_TIMEOUT, 5)

    fun setKioskSettingsTimeout(seconds: Int) {
        prefs.edit { putInt(PREF_KIOSK_SETTINGS_TIMEOUT, seconds) }
    }

    fun cleanup() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
    }
}