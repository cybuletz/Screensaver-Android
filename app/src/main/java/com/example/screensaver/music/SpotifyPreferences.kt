package com.example.screensaver.music

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.example.screensaver.data.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SpotifyPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val PREF_SPOTIFY_ENABLED = "spotify_enabled"
        private const val PREF_AUTOPLAY_ENABLED = "spotify_autoplay"
        private const val PREF_SELECTED_PLAYLIST = "spotify_selected_playlist"
        private const val PREF_LAST_VOLUME = "spotify_last_volume"
        private const val PREF_CONNECTION_STATE = "spotify_connection_state"
        private const val PREF_PLAYLIST_SUMMARY = "spotify_playlist_summary"
    }

    private val preferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun isEnabled(): Boolean =
        preferences.getBoolean(PREF_SPOTIFY_ENABLED, false)

    fun setConnectionState(isConnected: Boolean) {
        preferences.edit()
            .putBoolean(PREF_CONNECTION_STATE, isConnected)
            .apply()
    }

    fun wasConnected(): Boolean =
        preferences.getBoolean(PREF_CONNECTION_STATE, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(PREF_SPOTIFY_ENABLED, enabled)
            .apply()
    }

    fun isAutoplayEnabled(): Boolean =
        preferences.getBoolean(PREF_AUTOPLAY_ENABLED, false)

    fun setAutoplayEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(PREF_AUTOPLAY_ENABLED, enabled)
            .apply()
    }

    fun getSelectedPlaylist(): String? =
        secureStorage.getSecurely(PREF_SELECTED_PLAYLIST)

    fun setSelectedPlaylist(uri: String?) {
        if (uri != null) {
            secureStorage.saveSecurely(PREF_SELECTED_PLAYLIST, uri)
        } else {
            secureStorage.removeSecurely(PREF_SELECTED_PLAYLIST)
        }
    }

    fun setSelectedPlaylistWithTitle(uri: String, title: String) {
        secureStorage.saveSecurely("spotify_selected_playlist", uri)
        secureStorage.saveSecurely("spotify_selected_playlist_title", title)
    }

    fun getSelectedPlaylistTitle(): String? {
        return secureStorage.getSecurely("spotify_selected_playlist_title")
    }

    fun getPlaylistSummary(): String? =
        secureStorage.getSecurely(PREF_PLAYLIST_SUMMARY)

    fun setPlaylistSummary(summary: String?) {
        if (summary != null) {
            secureStorage.saveSecurely(PREF_PLAYLIST_SUMMARY, summary)
        } else {
            secureStorage.removeSecurely(PREF_PLAYLIST_SUMMARY)
        }
    }
}