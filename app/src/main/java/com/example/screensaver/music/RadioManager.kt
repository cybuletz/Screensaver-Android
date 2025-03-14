package com.example.screensaver.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class RadioManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: RadioPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private val client = OkHttpClient()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentStation = MutableStateFlow<RadioStation?>(null)
    val currentStation: StateFlow<RadioStation?> = _currentStation.asStateFlow()

    companion object {
        private const val TAG = "RadioManager"
        private const val BASE_URL = "https://de1.api.radio-browser.info/json"
        val DEFAULT_STATION = RadioStation(
            id = "defaultstation",
            name = "Radio Paradise - Main Mix",
            url = "http://stream.radioparadise.com/aac-128",
            genre = "Eclectic",
            country = "USA",
            favicon = "https://www.radioparadise.com/favicon.ico"
        )
    }

    // Internal data classes
    data class RadioStation(
        val id: String,
        val name: String,
        val url: String,
        val genre: String? = null,
        val country: String? = null,
        val favicon: String? = null
    )

    sealed class ConnectionState {
        object Connected : ConnectionState()
        object Disconnected : ConnectionState()
        data class Error(val error: Throwable) : ConnectionState()
    }

    sealed class PlaybackState {
        object Idle : PlaybackState()
        data class Playing(
            val stationName: String,
            val genre: String?,
            val isPlaying: Boolean
        ) : PlaybackState()
    }

    init {
        setupMediaPlayer()
        // Get the last station from preferences and play it if available
        val lastStation = preferences.getLastStation()
        if (lastStation != null) {
            playStation(lastStation)
        } else {
            // If no last station, play default
            playStation(DEFAULT_STATION)
        }
    }

    private fun setupMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            setOnPreparedListener {
                Timber.d("MediaPlayer prepared")
                start()
                _connectionState.value = ConnectionState.Connected
                updatePlaybackState(true)
            }

            setOnErrorListener { _, what, extra ->
                val error = Exception("MediaPlayer error: what=$what, extra=$extra")
                Timber.e(error, "MediaPlayer error")
                _connectionState.value = ConnectionState.Error(error)
                true
            }

            setOnCompletionListener {
                Timber.d("MediaPlayer completed")
                updatePlaybackState(false)
            }
        }
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val station = _currentStation.value
        _playbackState.value = if (station != null) {
            PlaybackState.Playing(
                stationName = station.name,
                genre = station.genre,
                isPlaying = isPlaying
            )
        } else {
            PlaybackState.Idle
        }
    }

    fun playStation(station: RadioStation) {
        Timber.d("Playing station: ${station.name}")
        scope.launch {
            try {
                _currentStation.value = station
                mediaPlayer?.apply {
                    reset()
                    setDataSource(station.url)
                    prepareAsync()
                }
                // Add to recent stations when playing
                preferences.addToRecentStations(station)
            } catch (e: Exception) {
                Timber.e(e, "Error playing station")
                _connectionState.value = ConnectionState.Error(e)
            }
        }
    }

    fun resume() {
        Timber.d("Resuming playback")
        mediaPlayer?.start()
        updatePlaybackState(true)
    }

    fun pause() {
        Timber.d("Pausing playback")
        mediaPlayer?.pause()
        updatePlaybackState(false)
    }

    fun disconnect() {
        Timber.d("Disconnecting radio")
        mediaPlayer?.apply {
            stop()
            reset()
        }
        _currentStation.value = null
        _connectionState.value = ConnectionState.Disconnected
        _playbackState.value = PlaybackState.Idle
    }

    fun searchStations(query: String, callback: (List<RadioStation>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/stations/search?name=$query&limit=30")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Search failed")

                    val stations = JSONArray(response.body!!.string()).let { json ->
                        List(json.length()) { i ->
                            val station = json.getJSONObject(i)
                            RadioStation(
                                id = station.getString("stationuuid"),
                                name = station.getString("name"),
                                url = station.getString("url_resolved"),
                                genre = station.optString("tags").takeIf { it.isNotEmpty() },
                                country = station.optString("country").takeIf { it.isNotEmpty() },
                                favicon = station.optString("favicon").takeIf { it.isNotEmpty() }
                            )
                        }
                    }

                    launch(Dispatchers.Main) {
                        callback(stations)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error searching stations")
                launch(Dispatchers.Main) {
                    callback(emptyList())
                }
            }
        }
    }

    fun cleanup() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}