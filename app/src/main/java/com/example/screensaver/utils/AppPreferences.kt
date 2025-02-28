package com.example.screensaver.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.example.screensaver.photos.VirtualAlbum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import javax.annotation.PostConstruct
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages application preferences and settings.
 * Handles persistence and provides type-safe access to user preferences.
 */
@Singleton
class AppPreferences @Inject constructor(
    context: Context
) {
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
    private val _previewCountFlow = MutableStateFlow(getPreviewCount())

    private val virtualAlbumPrefs = context.getSharedPreferences("virtual_albums", Context.MODE_PRIVATE)
    private val KEY_VIRTUAL_ALBUMS = "virtual_albums_data"
    private val KEY_SELECTED_VIRTUAL_ALBUMS = "selected_virtual_albums"
    private val _virtualAlbumsFlow = MutableStateFlow<List<VirtualAlbum>>(emptyList())
    val virtualAlbumsFlow = _virtualAlbumsFlow.asStateFlow()

    // Public flows
    val selectedAlbumsFlow = _selectedAlbumsFlow.asStateFlow()

    @PostConstruct
    fun initialize() {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    companion object {
        private const val TAG = "AppPreferences"

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
        private const val DEFAULT_TRANSITION_INTERVAL = 30
        private const val DEFAULT_CLOCK_FORMAT = "24h"

        private const val PREF_TRANSITION_DURATION = "transition_duration"
        private const val PREF_PHOTO_QUALITY = "photo_quality"
        private const val PREF_RANDOM_ORDER = "random_order"
        private const val PREF_SHOW_LOCATION = "show_location"
        private const val PREF_SHOW_DATE = "show_date"
        private const val PREF_ENABLE_TRANSITIONS = "enable_transitions"
        private const val PREF_DARK_MODE = "dark_mode"

        private const val PREF_PREVIEW_COUNT = "preview_count"

        private const val KEY_PICKED_URIS = "picked_photo_uris"
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

    data class VirtualAlbum(
        val id: String,
        val name: String,
        val photoUris: List<String>,
        val dateCreated: Long,
        val sortOrder: Int = 0
    )

    init {
        loadVirtualAlbums()
    }

    fun getSelectedAlbumIds(): Set<String> {
        return prefs.getStringSet(PREF_SELECTED_ALBUMS, emptySet()) ?: emptySet()
    }

    fun setSelectedAlbumIds(albumIds: Set<String>) {
        prefs.edit {
            putStringSet(PREF_SELECTED_ALBUMS, albumIds)
            putLong(PREF_LAST_SYNC, System.currentTimeMillis())
        }
        _selectedAlbumsFlow.value = albumIds
    }

    fun addSelectedAlbumId(albumId: String) {
        val currentIds = getSelectedAlbumIds().toMutableSet()
        if (currentIds.add(albumId)) {  // Only update if the set changed
            setSelectedAlbumIds(currentIds)
        }
    }

    fun removeSelectedAlbumId(albumId: String) {
        val currentIds = getSelectedAlbumIds().toMutableSet()
        if (currentIds.remove(albumId)) {  // Only update if the set changed
            setSelectedAlbumIds(currentIds)
        }
    }

    fun clearSelectedAlbums() {
        setSelectedAlbumIds(emptySet())
    }

    fun removePickedUri(uri: String) {
        val currentUris = getPickedUris().toMutableSet()
        currentUris.remove(uri)
        prefs.edit().putStringSet("picked_uris", currentUris).apply()
    }

    fun clearAll() {
        prefs.edit().clear().commit() // Use commit() for synchronous execution

        // Reset all preference flows to defaults
        _displayModeFlow.value = DisplayMode.DREAM_SERVICE
        _transitionIntervalFlow.value = DEFAULT_TRANSITION_INTERVAL
        _transitionAnimationFlow.value = TransitionAnimation.FADE
        _showPhotoInfoFlow.value = true
        _showClockFlow.value = false
        _clockFormatFlow.value = ClockFormat.FORMAT_24H
        _selectedAlbumsFlow.value = emptySet()
        _previewCountFlow.value = 0
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun updatePreference(operation: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply {
            operation()
            apply()
        }
    }

    fun getLong(key: String, defaultValue: Long): Long = prefs.getLong(key, defaultValue)

    fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    fun edit(operation: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply {
            operation()
            apply()
        }
    }

    fun getPreviewCount(): Int = prefs.getInt(PREF_PREVIEW_COUNT, 0)

    fun getDisplayMode(): DisplayMode = when(prefs.getString(PREF_DISPLAY_MODE, DEFAULT_DISPLAY_MODE)) {
        "lock_screen" -> DisplayMode.LOCK_SCREEN
        else -> DisplayMode.DREAM_SERVICE
    }

    fun setClockPosition(position: String) {
        edit {
            putString("clock_position", position)
        }
    }

    fun getTransitionDuration(): Int =
        prefs.getInt(PREF_TRANSITION_DURATION, 30)

    fun setTransitionDuration(duration: Int) {
        updatePreference { putInt(PREF_TRANSITION_DURATION, duration) }
    }

    fun getPhotoQuality(): Int =
        prefs.getInt(PREF_PHOTO_QUALITY, 1)

    fun setPhotoQuality(quality: Int) {
        updatePreference { putInt(PREF_PHOTO_QUALITY, quality) }
    }

    fun getRandomOrder(): Boolean =
        prefs.getBoolean(PREF_RANDOM_ORDER, true)

    fun setRandomOrder(enabled: Boolean) {
        updatePreference { putBoolean(PREF_RANDOM_ORDER, enabled) }
    }

    fun getShowLocation(): Boolean =
        prefs.getBoolean(PREF_SHOW_LOCATION, false)

    fun setShowLocation(enabled: Boolean) {
        updatePreference { putBoolean(PREF_SHOW_LOCATION, enabled) }
    }

    fun getShowDate(): Boolean =
        prefs.getBoolean(PREF_SHOW_DATE, false)

    fun setShowDate(enabled: Boolean) {
        updatePreference { putBoolean(PREF_SHOW_DATE, enabled) }
    }

    fun getEnableTransitions(): Boolean =
        prefs.getBoolean(PREF_ENABLE_TRANSITIONS, true)

    fun setEnableTransitions(enabled: Boolean) {
        updatePreference { putBoolean(PREF_ENABLE_TRANSITIONS, enabled) }
    }

    fun getDarkMode(): Boolean =
        prefs.getBoolean(PREF_DARK_MODE, false)

    fun setDarkMode(enabled: Boolean) {
        updatePreference { putBoolean(PREF_DARK_MODE, enabled) }
    }

    fun getTransitionInterval(): Int =
        prefs.getInt(PREF_TRANSITION_INTERVAL, DEFAULT_TRANSITION_INTERVAL)

    fun getTransitionAnimation(): TransitionAnimation {
        val prefValue = getString("transition_animation", "fade")
        return when (prefValue.lowercase()) { // Remove the ?. operator
            "slide" -> TransitionAnimation.SLIDE
            "zoom" -> TransitionAnimation.ZOOM
            else -> TransitionAnimation.FADE
        }
    }

    fun savePickedUris(uris: Set<Uri>) {
        prefs.edit().putStringSet(KEY_PICKED_URIS, uris.map { it.toString() }.toSet()).apply()
    }

    fun getPickedUris(): Set<String> {
        return prefs.getStringSet(KEY_PICKED_URIS, emptySet()) ?: emptySet()
    }

    fun addPickedUri(uri: String) {
        val currentUris = getPickedUris().toMutableSet()
        currentUris.add(uri)
        prefs.edit().putStringSet(KEY_PICKED_URIS, currentUris).apply()
    }

    fun isShowPhotoInfo(): Boolean = prefs.getBoolean(PREF_SHOW_PHOTO_INFO, true)

    fun isShowClock(): Boolean = prefs.getBoolean(PREF_SHOW_CLOCK, false)

    fun setShowClock(show: Boolean) {
        updatePreference { putBoolean(PREF_SHOW_CLOCK, show) }
    }

    fun getClockFormat(): ClockFormat = when(prefs.getString(PREF_CLOCK_FORMAT, DEFAULT_CLOCK_FORMAT)) {
        "12h" -> ClockFormat.FORMAT_12H
        else -> ClockFormat.FORMAT_24H
    }

    fun setShowWeather(enabled: Boolean) {
        updatePreference { putBoolean("show_weather", false) }
    }

    fun getString(key: String, defaultValue: String): String {
        Log.d(TAG, "Getting string preference for key: $key, default: $defaultValue")
        val value = prefs.getString(key, defaultValue) ?: defaultValue
        Log.d(TAG, "Retrieved value: $value")
        return value
    }

    fun cleanup() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun saveVirtualAlbum(album: VirtualAlbum) {
        val currentAlbums = getVirtualAlbums().toMutableList()
        val existingIndex = currentAlbums.indexOfFirst { it.id == album.id }

        if (existingIndex != -1) {
            currentAlbums[existingIndex] = album
        } else {
            currentAlbums.add(album)
        }

        saveVirtualAlbums(currentAlbums)
    }

    fun deleteVirtualAlbum(albumId: String) {
        val currentAlbums = getVirtualAlbums().filterNot { it.id == albumId }
        saveVirtualAlbums(currentAlbums)

        // Remove from selected albums if present
        val selectedAlbums = getSelectedVirtualAlbumIds().toMutableSet()
        if (selectedAlbums.remove(albumId)) {
            setSelectedVirtualAlbumIds(selectedAlbums)
        }
    }

    fun saveVirtualAlbums(albums: List<VirtualAlbum>) {
        try {
            val json = JSONArray().apply {
                albums.forEach { album ->
                    put(JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("photoUris", JSONArray(album.photoUris))
                        put("dateCreated", album.dateCreated)
                        put("sortOrder", album.sortOrder)
                    })
                }
            }

            virtualAlbumPrefs.edit()
                .putString(KEY_VIRTUAL_ALBUMS, json.toString())
                .apply()

            _virtualAlbumsFlow.value = albums
        } catch (e: Exception) {
            Log.e(TAG, "Error saving virtual albums", e)
        }
    }

    fun getVirtualAlbums(): List<VirtualAlbum> {
        return try {
            val json = virtualAlbumPrefs.getString(KEY_VIRTUAL_ALBUMS, "[]")
            val jsonArray = JSONArray(json)
            List(jsonArray.length()) { i ->
                val obj = jsonArray.getJSONObject(i)
                val urisArray = obj.getJSONArray("photoUris")
                val photoUris = List(urisArray.length()) { j ->
                    urisArray.getString(j)
                }
                VirtualAlbum(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    photoUris = photoUris,
                    dateCreated = obj.getLong("dateCreated"),
                    sortOrder = obj.optInt("sortOrder", 0)
                )
            }.sortedBy { it.sortOrder }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading virtual albums", e)
            emptyList()
        }
    }

    fun getSelectedVirtualAlbumIds(): Set<String> {
        return virtualAlbumPrefs.getStringSet(KEY_SELECTED_VIRTUAL_ALBUMS, emptySet()) ?: emptySet()
    }

    fun setSelectedVirtualAlbumIds(albumIds: Set<String>) {
        virtualAlbumPrefs.edit()
            .putStringSet(KEY_SELECTED_VIRTUAL_ALBUMS, albumIds)
            .apply()
    }

    private fun loadVirtualAlbums() {
        _virtualAlbumsFlow.value = getVirtualAlbums()
    }

    fun getPhotoSources(): Set<String> {
        return prefs.getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")
    }
}