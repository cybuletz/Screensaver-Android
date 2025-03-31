package com.photostreamr.music

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import com.photostreamr.data.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class LocalMusicPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val PREF_LOCAL_ENABLED = "local_music_enabled"
        private const val PREF_CURRENT_FOLDER = "local_music_folder_uri"
        private const val PREF_FOLDER_NAME = "local_music_folder_name"
        private const val PREF_CURRENT_TRACK = "local_music_current_track"
        private const val PREF_LAST_VOLUME = "local_music_last_volume"
        private const val PREF_CONNECTION_STATE = "local_music_connection_state"
        private const val PREF_SHUFFLE_ENABLED = "local_music_shuffle_enabled"
        private const val PREF_AUTOPLAY_ENABLED = "local_music_autoplay_enabled"
        private const val PREF_PLAYLIST_TRACKS = "local_music_playlist_tracks"
        private const val PREF_WAS_PLAYING = "local_music_was_playing"
    }

    private val preferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun isEnabled(): Boolean =
        preferences.getBoolean(PREF_LOCAL_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(PREF_LOCAL_ENABLED, enabled)
            .apply()
    }

    fun setConnectionState(isConnected: Boolean) {
        preferences.edit()
            .putBoolean(PREF_CONNECTION_STATE, isConnected)
            .apply()
    }

    fun wasConnected(): Boolean =
        preferences.getBoolean(PREF_CONNECTION_STATE, false)

    fun isAutoplayEnabled(): Boolean =
        preferences.getBoolean(PREF_AUTOPLAY_ENABLED, false)

    fun setAutoplayEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(PREF_AUTOPLAY_ENABLED, enabled)
            .apply()
    }

    fun getMusicFolderUri(): Uri? {
        return secureStorage.getSecurely(PREF_CURRENT_FOLDER)?.let {
            try {
                Uri.parse(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun setMusicFolderUri(uri: Uri?) {
        if (uri != null) {
            secureStorage.saveSecurely(PREF_CURRENT_FOLDER, uri.toString())
        } else {
            secureStorage.removeSecurely(PREF_CURRENT_FOLDER)
        }
    }

    fun getMusicFolderName(): String? =
        secureStorage.getSecurely(PREF_FOLDER_NAME)

    fun setMusicFolderName(name: String?) {
        if (name != null) {
            secureStorage.saveSecurely(PREF_FOLDER_NAME, name)
        } else {
            secureStorage.removeSecurely(PREF_FOLDER_NAME)
        }
    }

    fun getCurrentTrackUri(): Uri? {
        return secureStorage.getSecurely(PREF_CURRENT_TRACK)?.let {
            try {
                Uri.parse(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun setCurrentTrackUri(uri: Uri?) {
        if (uri != null) {
            secureStorage.saveSecurely(PREF_CURRENT_TRACK, uri.toString())
        } else {
            secureStorage.removeSecurely(PREF_CURRENT_TRACK)
        }
    }

    fun isShuffleEnabled(): Boolean =
        preferences.getBoolean(PREF_SHUFFLE_ENABLED, false)

    fun setShuffleEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(PREF_SHUFFLE_ENABLED, enabled)
            .apply()
    }

    fun setWasPlaying(wasPlaying: Boolean) {
        preferences.edit()
            .putBoolean(PREF_WAS_PLAYING, wasPlaying)
            .apply()
    }

    fun wasPlaying(): Boolean =
        preferences.getBoolean(PREF_WAS_PLAYING, false)

    fun getLastVolume(): Float =
        preferences.getFloat(PREF_LAST_VOLUME, 0.7f)

    fun setLastVolume(volume: Float) {
        preferences.edit()
            .putFloat(PREF_LAST_VOLUME, volume)
            .apply()
    }

    fun saveTracksList(tracks: List<String>) {
        val jsonArray = JSONArray()
        tracks.forEach { jsonArray.put(it) }
        secureStorage.saveSecurely(PREF_PLAYLIST_TRACKS, jsonArray.toString())
    }

    fun getTracksList(): List<String> {
        return secureStorage.getSecurely(PREF_PLAYLIST_TRACKS)?.let { json ->
            try {
                val jsonArray = JSONArray(json)
                List(jsonArray.length()) { i -> jsonArray.getString(i) }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }
}