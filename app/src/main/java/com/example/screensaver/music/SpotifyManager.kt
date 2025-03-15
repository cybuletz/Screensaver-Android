package com.example.screensaver.music

import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.ConnectApi
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.ListItems
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import com.example.screensaver.BuildConfig
import com.example.screensaver.data.SecureStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

    @Singleton
    class SpotifyManager @Inject constructor(
        @ApplicationContext private val context: Context,
        private val spotifyPreferences: SpotifyPreferences,
        private val secureStorage: SecureStorage,
        private val tokenManager: SpotifyTokenManager
    ) {
        private var spotifyAppRemote: SpotifyAppRemote? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var isScreensaverActive = false
        private var wasPlayingBeforeScreensaver = false

        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

        private val _errorState = MutableStateFlow<SpotifyError?>(null)
        val errorState: StateFlow<SpotifyError?> = _errorState.asStateFlow()

        companion object {
            private const val CLIENT_ID = "b6d959e9ca544b2aaebb37d0bb41adb5"
            private const val REDIRECT_URI = "screensaver-spotify://callback"
            private const val RECONNECT_DELAY = 5000L
            private const val RECENTLY_PLAYED_URI = "spotify:playlist:RecentlyPlayed"
        }

        fun onScreensaverStarted() {
            isScreensaverActive = true
            if (spotifyPreferences.isAutoplayEnabled()) {
                scope.launch {
                    try {
                        // Store current playing state
                        wasPlayingBeforeScreensaver = (playbackState.value as? PlaybackState.Playing)?.isPlaying ?: false

                        // Start playback if we have a selected playlist
                        spotifyPreferences.getSelectedPlaylist()?.let { playlistUri ->
                            playPlaylist(playlistUri)
                        } ?: resume() // If no playlist selected, just resume current playback
                    } catch (e: Exception) {
                        Timber.e(e, "Error starting screensaver playback")
                        _errorState.value = SpotifyError.PlaybackFailed(e)
                    }
                }
            }
        }

        fun onScreensaverStopped() {
            isScreensaverActive = false
            if (spotifyPreferences.isAutoplayEnabled() && !wasPlayingBeforeScreensaver) {
                pause()
            }
            // Reset state
            wasPlayingBeforeScreensaver = false
        }

        fun isSpotifyInstalled(): Boolean {
            return try {
                context.packageManager.getPackageInfo("com.spotify.music", 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        fun initialize() {
            Timber.d("Initializing Spotify Manager")

            // Check both conditions
            val isEnabled = spotifyPreferences.isEnabled()
            val wasConnected = spotifyPreferences.wasConnected()

            Timber.d("Spotify preferences - enabled: $isEnabled, wasConnected: $wasConnected")

            if (isEnabled && wasConnected) {
                Timber.d("Spotify was enabled and connected, attempting to reconnect")
                connect()
            } else if (isEnabled) {
                Timber.d("Spotify was enabled but not connected, requesting auth")
                _connectionState.value = ConnectionState.Disconnected
                // Don't automatically connect - wait for user to authenticate
            } else {
                Timber.d("Spotify is not enabled, staying disconnected")
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        fun connect() {
            if (!spotifyPreferences.isEnabled()) {
                Timber.d("Cannot connect - Spotify is not enabled")
                return
            }

            if (spotifyAppRemote?.isConnected == true) {
                Timber.d("Already connected to Spotify")
                // Verify connection is valid by attempting to get player state
                spotifyAppRemote?.playerApi?.playerState
                    ?.setResultCallback { state ->
                        if (state == null) {
                            Timber.d("Connection appears invalid, reconnecting...")
                            disconnect()
                            connectToSpotify()
                        } else {
                            refreshPlayerState()
                            spotifyPreferences.setConnectionState(true)
                        }
                    }
                    ?.setErrorCallback { error ->
                        Timber.e(error, "Connection appears invalid, reconnecting...")
                        disconnect()
                        connectToSpotify()
                    }
                return
            }

            // Check if Spotify is installed before attempting to connect
            if (!isSpotifyInstalled()) {
                handleConnectionError(CouldNotFindSpotifyApp())
                spotifyPreferences.setEnabled(false)
                return
            }

            // First ensure we have a valid auth token
            if (secureStorage.getSecurely(SpotifyAuthManager.KEY_SPOTIFY_TOKEN) == null) {
                _errorState.value = SpotifyError.AuthenticationRequired
                spotifyPreferences.setEnabled(false)
                return
            }

            connectToSpotify()
        }

        private fun connectToSpotify() {
            try {
                // First verify we have a token
                if (secureStorage.getSecurely(SpotifyAuthManager.KEY_SPOTIFY_TOKEN) == null) {
                    Timber.e("No auth token found")
                    _errorState.value = SpotifyError.AuthenticationRequired
                    return
                }

                // Ensure any existing connection is properly closed
                if (spotifyAppRemote?.isConnected == true) {
                    disconnect()
                }

                val connectionParams = ConnectionParams.Builder(CLIENT_ID)
                    .setRedirectUri(REDIRECT_URI)
                    .showAuthView(true)
                    .build()

                Timber.d("Attempting to connect to Spotify")

                SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) {
                        spotifyAppRemote = appRemote
                        _connectionState.value = ConnectionState.Connected
                        _errorState.value = null
                        spotifyPreferences.setConnectionState(true)
                        Timber.d("Connected to Spotify")

                        // Initialize player first
                        initializePlayer()

                        // Then refresh current state
                        refreshPlayerState()

                        // Set up player state subscription with detailed logging
                        appRemote.playerApi.subscribeToPlayerState()
                            .setEventCallback { playerState ->
                                Timber.d("Player state update: isPaused=${playerState.isPaused}, " +
                                        "track=${playerState.track?.name}, " +
                                        "playbackPosition=${playerState.playbackPosition}")

                                val track = playerState.track
                                if (track != null) {
                                    _playbackState.value = PlaybackState.Playing(
                                        isPlaying = !playerState.isPaused,
                                        trackName = track.name,
                                        artistName = track.artist.name,
                                        trackDuration = track.duration,
                                        playbackPosition = playerState.playbackPosition,
                                        playlistTitle = spotifyPreferences.getSelectedPlaylistTitle()
                                    )
                                } else {
                                    Timber.w("Track is null. isPaused=${playerState.isPaused}, " +
                                            "position=${playerState.playbackPosition}")

                                    if (spotifyAppRemote?.isConnected == true && !playerState.isPaused) {
                                        Timber.d("Track is null but player is not paused, trying to resume")
                                        spotifyAppRemote?.playerApi?.resume()
                                            ?.setResultCallback {
                                                Timber.d("Resume attempt completed")
                                                refreshPlayerState()
                                            }
                                            ?.setErrorCallback { error ->
                                                Timber.e(error, "Resume attempt failed")
                                            }
                                    }
                                    _playbackState.value = PlaybackState.Idle
                                }
                            }
                            .setErrorCallback { error ->
                                Timber.e(error, "Error in player state subscription")
                            }

                        // If autoplay is enabled and we're in screensaver mode, start playback
                        if (isScreensaverActive && spotifyPreferences.isAutoplayEnabled()) {
                            spotifyPreferences.getSelectedPlaylist()?.let { playlistUri ->
                                playPlaylist(playlistUri)
                            }
                        }
                    }

                    override fun onFailure(error: Throwable) {
                        handleConnectionError(error)
                        spotifyPreferences.setConnectionState(false)
                    }
                })

            } catch (e: Exception) {
                handleConnectionError(e)
                spotifyPreferences.setConnectionState(false)
            }
        }

        private fun handleConnectionError(error: Throwable) {
            Timber.e(error, "Could not connect to Spotify")
            _connectionState.value = ConnectionState.Error(error)

            when (error) {
                is CouldNotFindSpotifyApp -> {
                    spotifyPreferences.setEnabled(false)
                    _errorState.value = SpotifyError.AppNotInstalled
                }
                is NotLoggedInException,
                is UserNotAuthorizedException -> {
                    // Clear the token as it's no longer valid
                    secureStorage.removeSecurely(SpotifyAuthManager.KEY_SPOTIFY_TOKEN)
                    tokenManager.clearToken()
                    spotifyPreferences.setEnabled(false)
                    _errorState.value = SpotifyError.AuthenticationRequired

                    // Launch Spotify app to ensure user is logged in
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error launching Spotify app")
                    }
                }
                else -> {
                    _errorState.value = SpotifyError.ConnectionFailed(error)

                    // Only attempt reconnection for non-auth related errors
                    if (isScreensaverActive && spotifyPreferences.isAutoplayEnabled()) {
                        scope.launch {
                            kotlinx.coroutines.delay(RECONNECT_DELAY)
                            retry()
                        }
                    }
                }
            }
        }

        fun retry() {
            if (!spotifyPreferences.isEnabled()) {
                Timber.d("Cannot retry - Spotify is not enabled")
                return
            }

            Timber.d("Retrying Spotify connection")
            disconnect()
            connect()
        }

        fun disconnect() {
            try {
                if (isScreensaverActive && spotifyPreferences.isAutoplayEnabled()) {
                    pause()
                }
                // Clear any cached state
                spotifyAppRemote?.playerApi?.pause()
                    ?.setResultCallback {
                        spotifyAppRemote?.playerApi?.skipPrevious()
                            ?.setResultCallback {
                                SpotifyAppRemote.disconnect(spotifyAppRemote)
                            }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Error disconnecting from Spotify")
                SpotifyAppRemote.disconnect(spotifyAppRemote)
            } finally {
                spotifyAppRemote = null
                _connectionState.value = ConnectionState.Disconnected
                _playbackState.value = PlaybackState.Idle
                spotifyPreferences.setConnectionState(false)
            }
        }

        fun resume() {
            Log.e("SpotifyManager", "resume called")
            try {
                if (playbackState.value is PlaybackState.Idle) {
                    // If we're idle, try to start the selected playlist
                    spotifyPreferences.getSelectedPlaylist()?.let { uri ->
                        playPlaylist(uri)
                    } ?: run {
                        // If no playlist selected, use Recently Played
                        playPlaylist(RECENTLY_PLAYED_URI)
                    }
                } else {
                    // If we have an active track, just resume
                    spotifyAppRemote?.playerApi?.resume()
                }
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error resuming playback")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun pause() {
            Log.e("SpotifyManager", "pause called")
            try {
                spotifyAppRemote?.playerApi?.pause()
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error pausing playback")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun nextTrack() {
            Log.e("SpotifyManager", "nextTrack called")
            try {
                spotifyAppRemote?.playerApi?.skipNext()
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error skipping to next track")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun previousTrack() {
            Log.e("SpotifyManager", "previousTrack called")
            try {
                spotifyAppRemote?.playerApi?.skipPrevious()
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error skipping to previous track")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun getPlaylists(callback: (List<SpotifyPlaylist>) -> Unit, errorCallback: (Throwable) -> Unit) {
            val token = tokenManager.getAccessToken()
            if (token == null) {
                errorCallback(IllegalStateException("No access token available"))
                return
            }

            val recommendedPlaylists = listOf(
                SpotifyPlaylist(
                    title = "Liked Songs",
                    uri = "spotify:playlist:liked",
                    imageUri = null,
                    isRecommended = true
                ),
                SpotifyPlaylist(
                    title = "Recently Played",
                    uri = "spotify:playlist:RecentlyPlayed",
                    imageUri = null,
                    isRecommended = true
                ),
                SpotifyPlaylist(
                    title = "Discover Weekly",
                    uri = "spotify:playlist:discover-weekly",
                    imageUri = null,
                    isRecommended = true
                ),
                SpotifyPlaylist(
                    title = "Daily Mix 1",
                    uri = "spotify:playlist:daily-mix-1",
                    imageUri = null,
                    isRecommended = true
                )
            )

            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Handler(Looper.getMainLooper()).post {
                        errorCallback(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            Handler(Looper.getMainLooper()).post {
                                errorCallback(IOException("Unexpected response ${response.code}"))
                            }
                            return
                        }

                        val json = JSONObject(response.body?.string() ?: "")
                        val items = json.getJSONArray("items")
                        val userPlaylists = mutableListOf<SpotifyPlaylist>()

                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val images = item.getJSONArray("images")
                            val imageUri = if (images.length() > 0) images.getJSONObject(0).getString("url") else null

                            userPlaylists.add(
                                SpotifyPlaylist(
                                    title = item.getString("name"),
                                    uri = "spotify:playlist:${item.getString("id")}",
                                    imageUri = imageUri,
                                    isRecommended = false
                                )
                            )
                        }

                        // Combine recommended and user playlists
                        val allPlaylists = recommendedPlaylists + userPlaylists

                        Handler(Looper.getMainLooper()).post {
                            callback(allPlaylists)
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            errorCallback(e)
                        }
                    }
                }
            })
        }

        fun getPlaylistInfo(uri: String, callback: (SpotifyPlaylist?) -> Unit, errorCallback: (Throwable) -> Unit) {
            // Create ListItem using the correct constructor parameters
            val listItem = ListItem(
                "",         // title
                "",         // subtitle
                null,       // imageUri
                uri,        // uri
                "",         // category
                false,      // hasChildren
                true        // playable
            )

            // Now call getChildrenOfItem with all three required parameters
            spotifyAppRemote?.contentApi?.getChildrenOfItem(
                listItem,
                0,  // options flags
                0   // page
            )?.setResultCallback { result: ListItems ->
                val item = result.items.firstOrNull()
                if (item != null) {
                    callback(SpotifyPlaylist(
                        title = item.title,
                        uri = item.uri,
                        imageUri = item.imageUri.raw
                    ))
                } else {
                    callback(null)
                }
            }?.setErrorCallback { error ->
                errorCallback(error)
            } ?: errorCallback(Exception("Spotify not connected"))
        }

        fun playPlaylist(playlistUri: String) {
            Timber.d("Attempting to play playlist: $playlistUri")

            if (spotifyAppRemote?.isConnected != true) {
                val error = Exception("Spotify not connected")
                Timber.e(error)
                _errorState.value = SpotifyError.PlaybackFailed(error)
                connect()
                return
            }

            try {
                // First pause any current playback
                spotifyAppRemote?.playerApi?.pause()
                    ?.setResultCallback {
                        Timber.d("Paused current playback before starting new playlist")

                        // Set repeat mode
                        spotifyAppRemote?.playerApi?.setRepeat(Repeat.ALL)
                            ?.setResultCallback {
                                Timber.d("Set repeat mode to ALL")

                                // Set shuffle based on preference
                                val shuffleEnabled = spotifyPreferences.isShuffleEnabled()
                                spotifyAppRemote?.playerApi?.setShuffle(shuffleEnabled)
                                    ?.setResultCallback {
                                        Timber.d("Set shuffle mode to: $shuffleEnabled")

                                        // Play the new playlist
                                        spotifyAppRemote?.playerApi?.play(playlistUri)
                                            ?.setResultCallback {
                                                Timber.d("Successfully started playlist playback")
                                                refreshPlayerState()
                                            }
                                            ?.setErrorCallback { error ->
                                                Timber.e(error, "Failed to play playlist")
                                                _errorState.value = SpotifyError.PlaybackFailed(error)
                                            }
                                    }
                                    ?.setErrorCallback { error ->
                                        Timber.e(error, "Failed to set shuffle mode")
                                    }
                            }
                            ?.setErrorCallback { error ->
                                Timber.e(error, "Failed to set repeat mode")
                            }
                    }
                    ?.setErrorCallback { error ->
                        Timber.e(error, "Failed to pause before playing new playlist")
                        // Try playing anyway
                        spotifyAppRemote?.playerApi?.play(playlistUri)
                    }

            } catch (e: Exception) {
                Timber.e(e, "Fatal error playing playlist")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        private fun logPlayerState(state: PlayerState) {
            val currentPlaylist = spotifyPreferences.getSelectedPlaylist()?.let { uri ->
                when {
                    uri == RECENTLY_PLAYED_URI -> "Recently Played"
                    else -> "Custom Playlist (${uri.substringAfterLast(":")})"
                }
            } ?: "No playlist selected"

            Timber.d("""
        Player State:
        Is Paused: ${state.isPaused}
        Track: ${state.track?.name}
        Artist: ${state.track?.artist?.name}
        Duration: ${state.track?.duration}
        Position: ${state.playbackPosition}
        Is Active: ${state.track != null}
        Current Playlist: $currentPlaylist
    """.trimIndent())
        }

        private fun refreshPlayerState() {
            Timber.d("Refreshing player state")
            if (spotifyAppRemote?.isConnected != true) {
                Timber.w("Cannot refresh player state - Spotify not connected")
                return
            }

            spotifyAppRemote?.playerApi?.playerState
                ?.setResultCallback { state ->
                    logPlayerState(state)

                    if (state.track != null) {
                        _playbackState.value = PlaybackState.Playing(
                            isPlaying = !state.isPaused,
                            trackName = state.track.name,
                            artistName = state.track.artist.name,
                            trackDuration = state.track.duration,
                            playbackPosition = state.playbackPosition,
                            playlistTitle = spotifyPreferences.getSelectedPlaylistTitle()
                        )
                        Timber.d("Updated playback state to: ${_playbackState.value}")
                    } else {
                        if (spotifyAppRemote?.isConnected == true) {
                            Timber.w("Connected to Spotify but received null track")
                        }
                        _playbackState.value = PlaybackState.Idle
                        Timber.d("Set playback state to Idle")
                    }
                }
                ?.setErrorCallback { error ->
                    Timber.e(error, "Error refreshing player state")
                    _errorState.value = SpotifyError.PlaybackFailed(error)
                }
        }

        private fun initializePlayer() {
            try {
                Timber.d("Initializing player...")
                spotifyAppRemote?.playerApi?.playerState
                    ?.setResultCallback { playerState ->
                        Timber.d("Player state received during initialization")
                        // No need to play any track during initialization
                        refreshPlayerState()
                    }
                    ?.setErrorCallback { error ->
                        Timber.e(error, "Failed to get player state during initialization")
                        _errorState.value = SpotifyError.PlaybackFailed(error)
                    }
            } catch (e: Exception) {
                Timber.e(e, "Error initializing player")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun getCurrentUser(callback: (SpotifyUser?) -> Unit, errorCallback: (Throwable) -> Unit) {
            val token = tokenManager.getAccessToken()
            if (token == null) {
                errorCallback(IllegalStateException("No access token available"))
                return
            }

            // Use OkHttp or your preferred HTTP client
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    errorCallback(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        errorCallback(IOException("Unexpected response ${response.code}"))
                        return
                    }

                    try {
                        val json = JSONObject(response.body?.string() ?: "")
                        val user = SpotifyUser(
                            id = json.getString("id"),
                            displayName = json.optString("display_name").takeIf { it.isNotEmpty() },
                            email = json.optString("email").takeIf { it.isNotEmpty() },
                            images = json.optJSONArray("images")?.let { imagesArray ->
                                List(imagesArray.length()) { i ->
                                    val imageObj = imagesArray.getJSONObject(i)
                                    SpotifyUser.SpotifyImage(
                                        url = imageObj.getString("url"),
                                        width = imageObj.optInt("width").takeIf { it > 0 },
                                        height = imageObj.optInt("height").takeIf { it > 0 }
                                    )
                                }
                            }
                        )
                        callback(user)
                    } catch (e: Exception) {
                        errorCallback(e)
                    }
                }
            })
        }

        data class SpotifyUser(
            val id: String,
            val displayName: String?,
            val email: String?,
            val images: List<SpotifyImage>?
        ) {
            data class SpotifyImage(
                val url: String,
                val width: Int?,
                val height: Int?
            )
        }

        sealed class ConnectionState {
            object Connected : ConnectionState()
            object Disconnected : ConnectionState()
            data class Error(val error: Throwable) : ConnectionState()
        }

        sealed class PlaybackState {
            object Idle : PlaybackState()
            data class Playing(
                val isPlaying: Boolean = false,
                val trackName: String = "",
                val artistName: String = "",
                val trackDuration: Long,
                val playbackPosition: Long,
                val playlistTitle: String? = null
            ) : PlaybackState()
        }

        sealed class SpotifyError {
            object AppNotInstalled : SpotifyError()
            data class ConnectionFailed(val error: Throwable) : SpotifyError()
            data class PlaybackFailed(val error: Throwable) : SpotifyError()
            object AuthenticationRequired : SpotifyError()
            object PremiumRequired : SpotifyError()
        }

        data class SpotifyPlaylist(
            val title: String,
            val uri: String,
            val imageUri: String? = null,
            val isRecommended: Boolean = false
        )
    }