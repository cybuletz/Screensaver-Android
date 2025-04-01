package com.photostreamr.music

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.photostreamr.data.SecureStorage
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMusicPreferences @Inject constructor(
    private val context: Context,
    private val secureStorage: SecureStorage
) {

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    private val gson: Gson by lazy {
        GsonBuilder().create()
    }

    companion object {
        private const val KEY_ENABLED = "local_music_enabled"
        private const val KEY_AUTOPLAY = "local_music_autoplay_enabled"
        private const val KEY_LAST_TRACK = "local_music_last_track"
        private const val KEY_WAS_PLAYING = "local_music_was_playing"
        private const val KEY_SHUFFLE = "local_music_shuffle_enabled"
        private const val KEY_REPEAT_MODE = "local_music_repeat_mode"
        private const val KEY_SELECTED_PLAYLIST_ID = "local_music_selected_playlist_id"
        private const val KEY_SELECTED_PLAYLIST_NAME = "local_music_selected_playlist_name"
        private const val KEY_PLAYLISTS = "local_music_playlists"
        private const val KEY_MUSIC_DIRECTORY = "local_music_directory"
        private const val DEFAULT_MUSIC_DIRECTORY = "Music" // Relative to external storage
        private const val KEY_CURRENT_PLAYLIST = "current_playlist"
        private const val KEY_CURRENT_TRACK_INDEX = "current_track_index"
        private const val KEY_ORIGINAL_PLAYLIST = "local_music_original_playlist"
        private const val MAX_CHUNK_SIZE = 50
    }

    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAutoplayEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTOPLAY, false)
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOPLAY, enabled).apply()
    }

    fun getCurrentTrackIndex(): Int {
        return prefs.getInt(KEY_CURRENT_TRACK_INDEX, 0)
    }

    fun getLastTrack(): LocalMusicManager.LocalTrack? {
        val trackJson = prefs.getString(KEY_LAST_TRACK, null)
        return if (trackJson != null) {
            try {
                gson.fromJson(trackJson, LocalMusicManager.LocalTrack::class.java)
            } catch (e: Exception) {
                Timber.e(e, "Error parsing last track")
                null
            }
        } else {
            null
        }
    }

    fun setLastTrack(track: LocalMusicManager.LocalTrack) {
        val trackJson = gson.toJson(track)
        prefs.edit().putString(KEY_LAST_TRACK, trackJson).apply()
    }

    fun wasPlaying(): Boolean {
        return prefs.getBoolean(KEY_WAS_PLAYING, false)
    }

    fun setWasPlaying(wasPlaying: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_PLAYING, wasPlaying).apply()
    }

    fun isShuffleEnabled(): Boolean {
        return prefs.getBoolean(KEY_SHUFFLE, false)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHUFFLE, enabled).apply()
    }

    fun getRepeatMode(): String {
        return prefs.getString(KEY_REPEAT_MODE, LocalMusicManager.RepeatMode.OFF.name)
            ?: LocalMusicManager.RepeatMode.OFF.name
    }

    fun setRepeatMode(mode: String) {
        prefs.edit().putString(KEY_REPEAT_MODE, mode).apply()
    }

    fun getSelectedPlaylistId(): String? {
        return prefs.getString(KEY_SELECTED_PLAYLIST_ID, null)
    }

    fun setSelectedPlaylist(id: String, name: String) {
        prefs.edit()
            .putString(KEY_SELECTED_PLAYLIST_ID, id)
            .putString(KEY_SELECTED_PLAYLIST_NAME, name)
            .apply()
    }

    fun getSelectedPlaylistName(): String? {
        return prefs.getString(KEY_SELECTED_PLAYLIST_NAME, null)
    }

    fun getPlaylists(): List<LocalMusicManager.Playlist> {
        val playlistsJson = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        val type = object : TypeToken<List<LocalMusicManager.Playlist>>() {}.type
        return try {
            gson.fromJson(playlistsJson, type)
        } catch (e: Exception) {
            Timber.e(e, "Error parsing playlists")
            emptyList()
        }
    }

    fun getPlaylist(id: String): LocalMusicManager.Playlist? {
        return getPlaylists().find { it.id == id }
    }

    fun savePlaylists(playlists: List<LocalMusicManager.Playlist>) {
        val playlistsJson = gson.toJson(playlists)
        prefs.edit().putString(KEY_PLAYLISTS, playlistsJson).apply()
    }

    fun savePlaylist(playlist: LocalMusicManager.Playlist) {
        val playlists = getPlaylists().toMutableList()
        val existingIndex = playlists.indexOfFirst { it.id == playlist.id }

        if (existingIndex >= 0) {
            playlists[existingIndex] = playlist
        } else {
            playlists.add(playlist)
        }

        savePlaylists(playlists)
    }

    fun deletePlaylist(id: String) {
        val playlists = getPlaylists().filter { it.id != id }
        savePlaylists(playlists)

        // If this was the selected playlist, clear the selection
        if (getSelectedPlaylistId() == id) {
            prefs.edit()
                .remove(KEY_SELECTED_PLAYLIST_ID)
                .remove(KEY_SELECTED_PLAYLIST_NAME)
                .apply()
        }
    }

    fun getMusicDirectory(): String {
        val defaultMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
        return prefs.getString(KEY_MUSIC_DIRECTORY, defaultMusicDir) ?: defaultMusicDir
    }

    fun setMusicDirectory(directory: String) {
        prefs.edit().putString(KEY_MUSIC_DIRECTORY, directory).apply()
    }

    fun clearAll() {
        prefs.edit().apply {
            remove(KEY_LAST_TRACK)
            remove(KEY_WAS_PLAYING)
            remove(KEY_SELECTED_PLAYLIST_ID)
            remove(KEY_SELECTED_PLAYLIST_NAME)
            // Don't remove playlists or other settings
        }.apply()
    }

    fun saveCurrentPlaylist(playlist: List<LocalMusicManager.LocalTrack>, currentIndex: Int) {
        try {
            // For debugging, log exact details
            Timber.d("Saving current playlist: tracks=${playlist.size}, index=${currentIndex}")

            // Clear any existing chunks first
            clearPlaylistChunks()

            // Save current index
            prefs.edit().putInt(KEY_CURRENT_TRACK_INDEX, currentIndex).apply()

            // Save total playlist size for verification
            prefs.edit().putInt("playlist_total_size", playlist.size).apply()

            // Split playlist into chunks if needed
            val chunkCount = (playlist.size + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE
            Timber.d("Splitting playlist into $chunkCount chunks (max $MAX_CHUNK_SIZE items per chunk)")

            // Save how many chunks we have
            prefs.edit().putInt("playlist_chunk_count", chunkCount).apply()

            // Process each chunk
            for (i in 0 until chunkCount) {
                val startIndex = i * MAX_CHUNK_SIZE
                val endIndex = minOf(startIndex + MAX_CHUNK_SIZE, playlist.size)
                val chunk = playlist.subList(startIndex, endIndex)

                // For debugging, track each chunk
                Timber.d("Saving chunk $i with ${chunk.size} tracks (${startIndex}-${endIndex-1})")

                val chunkJson = gson.toJson(chunk)
                val chunkKey = "$KEY_CURRENT_PLAYLIST:$i"
                // Just call the method without checking return value
                secureStorage.saveSecurely(chunkKey, chunkJson)
            }

            Timber.d("Finished saving current playlist: ${playlist.size} tracks in $chunkCount chunks")
        } catch (e: Exception) {
            Timber.e(e, "Critical error saving playlist: ${e.message}")
        }
    }

    fun getCurrentPlaylist(): List<LocalMusicManager.LocalTrack> {
        try {
            // Get number of chunks and expected size
            val chunkCount = prefs.getInt("playlist_chunk_count", 0)
            val expectedSize = prefs.getInt("playlist_total_size", 0)

            Timber.d("LOAD PLAYLIST: Reading playlist from $chunkCount chunks (expecting $expectedSize tracks)")

            if (chunkCount == 0 || expectedSize == 0) {
                Timber.d("LOAD PLAYLIST: No saved playlist found (chunk count = $chunkCount, expected size = $expectedSize)")
                return emptyList()
            }

            // Pre-allocate to expected size to avoid resizing
            val fullPlaylist = ArrayList<LocalMusicManager.LocalTrack>(expectedSize)
            val type = object : TypeToken<List<LocalMusicManager.LocalTrack>>() {}.type

            var totalLoaded = 0

            // Read each chunk
            for (i in 0 until chunkCount) {
                val chunkKey = "$KEY_CURRENT_PLAYLIST:$i"
                val chunkJson = secureStorage.getSecurely(chunkKey)

                if (chunkJson == null) {
                    Timber.e("LOAD PLAYLIST: Missing chunk $i when reading playlist")
                    continue
                }

                try {
                    val chunk = gson.fromJson<List<LocalMusicManager.LocalTrack>>(chunkJson, type)
                    Timber.d("LOAD PLAYLIST: Read chunk $i with ${chunk.size} tracks")
                    fullPlaylist.addAll(chunk)
                    totalLoaded += chunk.size
                } catch (e: Exception) {
                    Timber.e(e, "LOAD PLAYLIST: Error parsing chunk $i: ${e.message}")
                }
            }

            Timber.d("LOAD PLAYLIST: Successfully loaded $totalLoaded tracks from $chunkCount chunks")

            // Verify we got expected size
            if (fullPlaylist.size != expectedSize) {
                Timber.w("LOAD PLAYLIST: Playlist size mismatch: loaded ${fullPlaylist.size} tracks, expected $expectedSize")
            }

            // Log the first few tracks for debugging
            if (fullPlaylist.isNotEmpty()) {
                Timber.d("LOAD PLAYLIST: First track is: ${fullPlaylist[0].title}")
                if (fullPlaylist.size > 1) {
                    Timber.d("LOAD PLAYLIST: Second track is: ${fullPlaylist[1].title}")
                }
            }

            return fullPlaylist
        } catch (e: Exception) {
            Timber.e(e, "LOAD PLAYLIST: Critical error retrieving playlist: ${e.message}")
            return emptyList()
        }
    }

    fun saveOriginalPlaylist(playlist: List<LocalMusicManager.LocalTrack>) {
        try {
            // For debugging, log exact details
            Timber.d("Saving original playlist: tracks=${playlist.size}")

            // Clear any existing chunks first
            clearOriginalPlaylistChunks()

            // Save total playlist size for verification
            prefs.edit().putInt("orig_playlist_total_size", playlist.size).apply()

            // Split playlist into chunks if needed
            val chunkCount = (playlist.size + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE
            Timber.d("Splitting original playlist into $chunkCount chunks (max $MAX_CHUNK_SIZE items per chunk)")

            // Save how many chunks we have
            prefs.edit().putInt("orig_playlist_chunk_count", chunkCount).apply()

            // Process each chunk
            for (i in 0 until chunkCount) {
                val startIndex = i * MAX_CHUNK_SIZE
                val endIndex = minOf(startIndex + MAX_CHUNK_SIZE, playlist.size)
                val chunk = playlist.subList(startIndex, endIndex)

                // For debugging, track each chunk
                Timber.d("Saving original playlist chunk $i with ${chunk.size} tracks (${startIndex}-${endIndex-1})")

                val chunkJson = gson.toJson(chunk)
                val chunkKey = "$KEY_ORIGINAL_PLAYLIST:$i"
                secureStorage.saveSecurely(chunkKey, chunkJson)
            }

            Timber.d("Finished saving original playlist: ${playlist.size} tracks in $chunkCount chunks")
        } catch (e: Exception) {
            Timber.e(e, "Critical error saving original playlist: ${e.message}")
        }
    }

    fun getOriginalPlaylist(): List<LocalMusicManager.LocalTrack> {
        try {
            // Get number of chunks and expected size
            val chunkCount = prefs.getInt("orig_playlist_chunk_count", 0)
            val expectedSize = prefs.getInt("orig_playlist_total_size", 0)

            Timber.d("LOAD ORIGINAL: Reading original playlist from $chunkCount chunks (expecting $expectedSize tracks)")

            if (chunkCount == 0 || expectedSize == 0) {
                Timber.d("LOAD ORIGINAL: No saved original playlist found (chunk count = $chunkCount, expected size = $expectedSize)")
                return emptyList()
            }

            // Pre-allocate to expected size to avoid resizing
            val fullPlaylist = ArrayList<LocalMusicManager.LocalTrack>(expectedSize)
            val type = object : TypeToken<List<LocalMusicManager.LocalTrack>>() {}.type

            var totalLoaded = 0

            // Read each chunk
            for (i in 0 until chunkCount) {
                val chunkKey = "$KEY_ORIGINAL_PLAYLIST:$i"
                val chunkJson = secureStorage.getSecurely(chunkKey)

                if (chunkJson == null) {
                    Timber.e("LOAD ORIGINAL: Missing chunk $i when reading original playlist")
                    continue
                }

                try {
                    val chunk = gson.fromJson<List<LocalMusicManager.LocalTrack>>(chunkJson, type)
                    Timber.d("LOAD ORIGINAL: Read original playlist chunk $i with ${chunk.size} tracks")
                    fullPlaylist.addAll(chunk)
                    totalLoaded += chunk.size
                } catch (e: Exception) {
                    Timber.e(e, "LOAD ORIGINAL: Error parsing original playlist chunk $i: ${e.message}")
                }
            }

            Timber.d("LOAD ORIGINAL: Successfully loaded $totalLoaded tracks from $chunkCount chunks for original playlist")

            // Verify we got expected size
            if (fullPlaylist.size != expectedSize) {
                Timber.w("LOAD ORIGINAL: Original playlist size mismatch: loaded ${fullPlaylist.size} tracks, expected $expectedSize")
            }

            // Log the first few tracks for debugging
            if (fullPlaylist.isNotEmpty()) {
                Timber.d("LOAD ORIGINAL: First track is: ${fullPlaylist[0].title}")
                if (fullPlaylist.size > 1) {
                    Timber.d("LOAD ORIGINAL: Second track is: ${fullPlaylist[1].title}")
                }
            }

            return fullPlaylist
        } catch (e: Exception) {
            Timber.e(e, "LOAD ORIGINAL: Critical error retrieving original playlist: ${e.message}")
            return emptyList()
        }
    }

    private fun clearPlaylistChunks() {
        val chunkCount = prefs.getInt("playlist_chunk_count", 0)
        Timber.d("Clearing existing playlist chunks: $chunkCount chunks")
        for (i in 0 until chunkCount) {
            secureStorage.removeSecurely("$KEY_CURRENT_PLAYLIST:$i")
        }
        prefs.edit().remove("playlist_chunk_count").apply()
        prefs.edit().remove("playlist_total_size").apply()
    }

    private fun clearOriginalPlaylistChunks() {
        val chunkCount = prefs.getInt("orig_playlist_chunk_count", 0)
        Timber.d("Clearing existing original playlist chunks: $chunkCount chunks")
        for (i in 0 until chunkCount) {
            secureStorage.removeSecurely("$KEY_ORIGINAL_PLAYLIST:$i")
        }
        prefs.edit().remove("orig_playlist_chunk_count").apply()
        prefs.edit().remove("orig_playlist_total_size").apply()
    }
}