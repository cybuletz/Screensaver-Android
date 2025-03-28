package com.photostreamr.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
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
import com.photostreamr.data.SecureStorage
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

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
            private const val REDIRECT_URI = "photostreamr-spotify://callback"
            private const val RECONNECT_DELAY = 5000L
            private const val RECENTLY_PLAYED_URI = "spotify:playlist:RecentlyPlayed"

            private const val BROWSE_OPTIONS_ALL = 3
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
                        Timber.e(e, "Error starting photostreamr playback")
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
                                    // Get the cover art for the track
                                    spotifyAppRemote?.imagesApi?.getImage(track.imageUri)
                                        ?.setResultCallback { bitmap ->
                                            _playbackState.value = PlaybackState.Playing(
                                                isPlaying = !playerState.isPaused,
                                                trackName = track.name,
                                                artistName = track.artist.name,
                                                trackDuration = track.duration,
                                                playbackPosition = playerState.playbackPosition,
                                                playlistTitle = spotifyPreferences.getSelectedPlaylistTitle(),
                                                coverArt = bitmap,
                                                trackUri = track.uri  // Add the track URI here
                                            )
                                        }
                                        ?.setErrorCallback { error ->
                                            Timber.e(error, "Failed to get track cover art")
                                            // Update state without cover art if image fetch fails
                                            _playbackState.value = PlaybackState.Playing(
                                                isPlaying = !playerState.isPaused,
                                                trackName = track.name,
                                                artistName = track.artist.name,
                                                trackDuration = track.duration,
                                                playbackPosition = playerState.playbackPosition,
                                                playlistTitle = spotifyPreferences.getSelectedPlaylistTitle(),
                                                coverArt = null,
                                                trackUri = track.uri  // Add the track URI here
                                            )
                                        }

                                    // Verify shuffle state on track changes
                                    verifyAndMaintainShuffleState()
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

                        // If autoplay is enabled and we're in photostreamr mode, start playback
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

        fun setShuffleMode(enabled: Boolean) {
            Timber.d("Setting shuffle mode to: $enabled")
            spotifyAppRemote?.playerApi?.setShuffle(enabled)
                ?.setResultCallback {
                    Timber.d("Successfully set shuffle mode to: $enabled")
                }
                ?.setErrorCallback { error ->
                    Timber.e(error, "Failed to set shuffle mode")
                }
        }

        private fun logPlaylistDetails(playlist: SpotifyPlaylist) {
            Timber.d("""
                Playlist Details:
                Title: ${playlist.title}
                URI: ${playlist.uri}
                Image URI: ${playlist.imageUri}
                Is Recommended: ${playlist.isRecommended}
            """.trimIndent())
        }

        private fun getUserPlaylists(token: String, callback: (List<SpotifyPlaylist>) -> Unit) {
            val client = OkHttpClient()
            val playlistsRequest = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(playlistsRequest).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Handler(Looper.getMainLooper()).post {
                        callback(emptyList()) // Return empty list on failure
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            Handler(Looper.getMainLooper()).post {
                                callback(emptyList())
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

                        Handler(Looper.getMainLooper()).post {
                            callback(userPlaylists)
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            callback(emptyList())
                        }
                    }
                }
            })
        }

        fun getPlaylists(callback: (List<SpotifyPlaylist>) -> Unit, errorCallback: (Throwable) -> Unit) {
            val token = tokenManager.getAccessToken()
            if (token == null) {
                errorCallback(IllegalStateException("No access token available"))
                return
            }

            getCurrentUser(
                callback = { user ->
                    if (user == null) {
                        errorCallback(IllegalStateException("Could not get user information"))
                        return@getCurrentUser
                    }

                    // Create Liked Songs playlist first
                    val likedSongs = SpotifyPlaylist(
                        title = "Liked Songs",
                        uri = "spotify:user:${user.id}:saved:tracks",
                        imageUri = null,
                        isRecommended = false
                    )

                    val recentlyPlayed = SpotifyPlaylist(
                        title = "Recently Played",
                        uri = RECENTLY_PLAYED_URI,
                        imageUri = null,
                        isRecommended = false
                    )

                    // Get user playlists (personal playlists)
                    getUserPlaylists(token) { userPlaylists ->
                        // Get recommended playlists
                        if (spotifyAppRemote?.isConnected == true) {
                            spotifyAppRemote?.contentApi?.getRecommendedContentItems("default")
                                ?.setResultCallback { items: ListItems ->
                                    try {
                                        val recommendedPlaylists = items.items
                                            .asSequence()
                                            .filter { item ->
                                                val isValid = item != null && !item.title.isNullOrEmpty()
                                                isValid
                                            }
                                            .map { item ->
                                                SpotifyPlaylist(
                                                    title = item.title,
                                                    uri = item.uri,
                                                    imageUri = item.imageUri?.toString(),
                                                    isRecommended = true
                                                )
                                            }
                                            .toList()

                                        // Order playlists: Liked Songs first, then personal playlists, then recommendations
                                        val orderedPlaylists = listOf(likedSongs) +
                                                listOf(recentlyPlayed) +
                                                userPlaylists.sortedBy { it.title } +
                                                recommendedPlaylists
                                        callback(orderedPlaylists)
                                    } catch (e: Exception) {
                                        Timber.e(e, "Error processing recommended playlists")
                                        // If recommendations fail, still return other playlists
                                        val orderedPlaylists = listOf(likedSongs) +
                                                listOf(recentlyPlayed) +
                                                userPlaylists.sortedBy { it.title }
                                        callback(orderedPlaylists)
                                    }
                                }
                                ?.setErrorCallback { error ->
                                    Timber.e(error, "Error fetching recommended playlists")
                                    val orderedPlaylists = listOf(likedSongs) +
                                            listOf(recentlyPlayed) +
                                            userPlaylists.sortedBy { it.title }
                                    callback(orderedPlaylists)
                                }
                        } else {
                            val orderedPlaylists = listOf(likedSongs) +
                                    listOf(recentlyPlayed) +
                                    userPlaylists.sortedBy { it.title }
                            callback(orderedPlaylists)
                        }
                    }
                },
                errorCallback = { error ->
                    errorCallback(error)
                }
            )
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
                when {
                    // Handle Liked Songs
                    playlistUri.contains(":saved:tracks") -> {
                        val userId = playlistUri.split(":")[2]
                        playItemWithSetup("spotify:user:$userId:collection")
                    }
                    // Handle Recently Played
                    playlistUri == RECENTLY_PLAYED_URI -> {
                        playItemWithSetup(playlistUri)
                    }
                    // Handle section URIs
                    playlistUri.startsWith("spotify:section:") -> {
                        handleSectionPlayback(playlistUri)
                    }
                    // Handle regular playlist URIs
                    else -> {
                        playItemWithSetup(playlistUri)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Fatal error playing playlist")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        private fun playItemWithSetup(uri: String) {
            Timber.d("Setting up playback for URI: $uri")

            spotifyAppRemote?.playerApi?.pause()
                ?.setResultCallback {
                    Timber.d("Paused current playback before starting new content")

                    // Set repeat mode first
                    spotifyAppRemote?.playerApi?.setRepeat(Repeat.ALL)
                        ?.setResultCallback {
                            Timber.d("Set repeat mode to ALL")

                            // Then set shuffle mode BEFORE starting playback
                            val shuffleEnabled = spotifyPreferences.isShuffleEnabled()
                            spotifyAppRemote?.playerApi?.setShuffle(shuffleEnabled)
                                ?.setResultCallback {
                                    Timber.d("Set shuffle mode to: $shuffleEnabled")

                                    if (shuffleEnabled) {
                                        // For shuffle, we need to:
                                        // 1. Start playback
                                        // 2. Skip to a random position to properly shuffle
                                        // 3. Then skip back to start
                                        spotifyAppRemote?.playerApi?.play(uri)
                                            ?.setResultCallback {
                                                Timber.d("Started initial playback, now forcing shuffle")

                                                // Skip forward a few tracks to force queue shuffling
                                                repeat(3) { skipCount ->
                                                    spotifyAppRemote?.playerApi?.skipNext()
                                                        ?.setResultCallback {
                                                            Timber.d("Completed skip forward $skipCount")
                                                            if (skipCount == 2) {
                                                                // After last skip, go back to start
                                                                spotifyAppRemote?.playerApi?.skipPrevious()
                                                                    ?.setResultCallback {
                                                                        spotifyAppRemote?.playerApi?.skipPrevious()
                                                                            ?.setResultCallback {
                                                                                spotifyAppRemote?.playerApi?.skipPrevious()
                                                                                    ?.setResultCallback {
                                                                                        Timber.d("Shuffle sequence complete")
                                                                                        refreshPlayerState()
                                                                                    }
                                                                            }
                                                                    }
                                                            }
                                                        }
                                                }
                                            }
                                    } else {
                                        // For non-shuffle, just play normally
                                        spotifyAppRemote?.playerApi?.play(uri)
                                            ?.setResultCallback {
                                                Timber.d("Successfully started normal playback")
                                                refreshPlayerState()
                                            }
                                    }
                                }
                                ?.setErrorCallback { error ->
                                    Timber.e(error, "Failed to set shuffle mode")
                                    // Try to play anyway
                                    spotifyAppRemote?.playerApi?.play(uri)
                                }
                        }
                        ?.setErrorCallback { error ->
                            Timber.e(error, "Failed to set repeat mode")
                            // Try to play anyway
                            spotifyAppRemote?.playerApi?.play(uri)
                        }
                }
                ?.setErrorCallback { error ->
                    Timber.e(error, "Failed to pause before playing new content")
                    // Try playing anyway
                    spotifyAppRemote?.playerApi?.play(uri)
                }
        }

        private fun handleSectionPlayback(sectionUri: String) {
            Timber.d("Handling section playback: $sectionUri")

            // First try to browse the section using the content API
            spotifyAppRemote?.contentApi?.getRecommendedContentItems("default")
                ?.setResultCallback { rootItems ->
                    Timber.d("Got ${rootItems.items.size} root items")

                    // Find the matching section
                    val matchingSection = rootItems.items.find { item -> item.uri == sectionUri }
                    if (matchingSection != null) {
                        Timber.d("Found matching section: ${matchingSection.title}")

                        // Get the section's content
                        spotifyAppRemote?.contentApi?.getChildrenOfItem(
                            matchingSection,
                            BROWSE_OPTIONS_ALL, // Include all content types
                            0
                        )?.setResultCallback { contentItems ->
                            if (contentItems.items.isNotEmpty()) {
                                val firstItem = contentItems.items[0]
                                if (firstItem.playable) {
                                    Timber.d("Playing first playable item: ${firstItem.title}")
                                    playItemWithSetup(firstItem.uri)
                                } else {
                                    // Try to browse deeper
                                    spotifyAppRemote?.contentApi?.getChildrenOfItem(
                                        firstItem,
                                        BROWSE_OPTIONS_ALL,
                                        0
                                    )?.setResultCallback { subItems ->
                                        val playableItem = subItems.items.firstOrNull { item -> item.playable }
                                        if (playableItem != null) {
                                            Timber.d("Playing first playable sub-item: ${playableItem.title}")
                                            playItemWithSetup(playableItem.uri)
                                        } else {
                                            // If no playable items found, try the original section URI
                                            Timber.d("No playable items found, trying section URI directly")
                                            playItemWithSetup(sectionUri)
                                        }
                                    }?.setErrorCallback { error ->
                                        Timber.e(error, "Error browsing sub-items")
                                        // Fall back to section URI
                                        playItemWithSetup(sectionUri)
                                    }
                                }
                            } else {
                                Timber.d("No items found, trying alternate approach")
                                tryAlternatePlayback(sectionUri)
                            }
                        }?.setErrorCallback { error ->
                            Timber.e(error, "Error getting section content")
                            tryAlternatePlayback(sectionUri)
                        }
                    } else {
                        // If section not found in recommended items, try alternate approach
                        Timber.d("Section not found in recommended items")
                        tryAlternatePlayback(sectionUri)
                    }
                }?.setErrorCallback { error ->
                    Timber.e(error, "Error getting recommended items")
                    tryAlternatePlayback(sectionUri)
                }
        }

        private fun tryAlternatePlayback(sectionUri: String) {
            Timber.d("Trying alternate playback for: $sectionUri")

            // Try to get section content directly
            spotifyAppRemote?.contentApi?.getChildrenOfItem(
                ListItem(
                    "",         // title
                    "",         // subtitle
                    null,       // imageUri
                    sectionUri, // uri
                    "",         // category
                    true,       // hasChildren
                    true        // playable
                ),
                BROWSE_OPTIONS_ALL,
                0
            )?.setResultCallback { result ->
                if (result.items.isNotEmpty()) {
                    val playableItem = result.items.firstOrNull { item -> item.playable }
                    if (playableItem != null) {
                        Timber.d("Found playable item in section")
                        playItemWithSetup(playableItem.uri)
                    } else {
                        // Try first item's children
                        val firstItem = result.items[0]
                        spotifyAppRemote?.contentApi?.getChildrenOfItem(
                            firstItem,
                            BROWSE_OPTIONS_ALL,
                            0
                        )?.setResultCallback { children ->
                            val firstPlayable = children.items.firstOrNull { item -> item.playable }
                            if (firstPlayable != null) {
                                Timber.d("Found playable item in deeper browse")
                                playItemWithSetup(firstPlayable.uri)
                            } else {
                                // Try playing the first item directly
                                Timber.d("No playable items found, trying first item")
                                playItemWithSetup(firstItem.uri)
                            }
                        }?.setErrorCallback { error ->
                            Timber.e(error, "Error getting children")
                            // Try the original section as last resort
                            playItemWithSetup(sectionUri)
                        }
                    }
                } else {
                    // If no items found, try direct playback
                    Timber.d("No items found, trying direct playback")
                    playItemWithSetup(sectionUri)
                }
            }?.setErrorCallback { error ->
                Timber.e(error, "Error in alternate playback")
                playItemWithSetup(sectionUri)
            }
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

        private fun verifyAndMaintainShuffleState() {
            spotifyAppRemote?.playerApi?.playerState
                ?.setResultCallback { playerState ->
                    val expectedShuffle = spotifyPreferences.isShuffleEnabled()
                    val currentShuffle = playerState.playbackOptions.isShuffling

                    Timber.d("Verifying shuffle state - Expected: $expectedShuffle, Current: $currentShuffle")

                    if (currentShuffle != expectedShuffle) {
                        Timber.d("Shuffle state mismatch, resetting to: $expectedShuffle")
                        spotifyAppRemote?.playerApi?.setShuffle(expectedShuffle)
                            ?.setResultCallback {
                                // After setting shuffle, force a queue refresh by skipping
                                if (expectedShuffle) {
                                    spotifyAppRemote?.playerApi?.skipNext()
                                        ?.setResultCallback {
                                            spotifyAppRemote?.playerApi?.skipPrevious()
                                        }
                                }
                            }
                    }
                }
        }

        private fun refreshPlayerState() {
            Timber.d("Refreshing player state")
            if (spotifyAppRemote?.isConnected != true) {
                Timber.w("Cannot refresh player state - Spotify not connected")
                return
            }

            // Add this line to verify shuffle state
            verifyAndMaintainShuffleState()

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

                        // Add this: Verify shuffle state after track changes
                        verifyAndMaintainShuffleState()
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

        fun checkAndRefreshTokenIfNeeded() {
            val token = tokenManager.getAccessToken()
            if (token == null) {
                _errorState.value = SpotifyError.AuthenticationRequired
                spotifyPreferences.setEnabled(false)
                return
            }

            // Get current connection state
            when (connectionState.value) {
                is ConnectionState.Error -> {
                    // If there was an error, try to reconnect
                    connect()
                }
                is ConnectionState.Disconnected -> {
                    // If disconnected, try to connect
                    connect()
                }
                is ConnectionState.Connected -> {
                    // Already connected, verify connection is valid
                    spotifyAppRemote?.playerApi?.playerState
                        ?.setResultCallback { state ->
                            if (state == null) {
                                Timber.d("Connection appears invalid, reconnecting...")
                                disconnect()
                                connect()
                            }
                        }
                        ?.setErrorCallback { error ->
                            if (error is UserNotAuthorizedException || error is NotLoggedInException) {
                                _errorState.value = SpotifyError.AuthenticationRequired
                                spotifyPreferences.setEnabled(false)
                                tokenManager.clearToken()
                            } else {
                                Timber.d("Connection error, reconnecting...")
                                disconnect()
                                connect()
                            }
                        }
                }
            }
        }

        fun getCurrentUser(callback: (SpotifyUser?) -> Unit, errorCallback: (Throwable) -> Unit) {
            val token = tokenManager.getAccessToken()
            if (token == null) {
                Handler(Looper.getMainLooper()).post {
                    errorCallback(IllegalStateException("No access token available"))
                }
                return
            }

            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Handler(Looper.getMainLooper()).post {
                        errorCallback(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Handler(Looper.getMainLooper()).post {
                            errorCallback(IOException("Unexpected response ${response.code}"))
                        }
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
                        Handler(Looper.getMainLooper()).post {
                            callback(user)
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            errorCallback(e)
                        }
                    }
                }
            })
        }

        private fun createRoundedBitmap(bitmap: Bitmap): Bitmap {
            val output = Bitmap.createBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(output)
            val paint = Paint().apply {
                isAntiAlias = true
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }

            // Use fixed corner radius in pixels
            val cornerRadius = 50f

            val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            return output
        }

        fun getTrackArtwork(trackUri: String, callback: (Bitmap?) -> Unit) {
            spotifyAppRemote?.playerApi?.playerState
                ?.setResultCallback { playerState ->
                    val imageUri = playerState.track?.imageUri
                    if (imageUri != null) {
                        spotifyAppRemote?.imagesApi?.getImage(imageUri)
                            ?.setResultCallback { bitmap ->
                                // Use the class method here
                                val roundedBitmap = bitmap?.let { createRoundedBitmap(it) }
                                callback(roundedBitmap)
                            }
                            ?.setErrorCallback { throwable ->
                                Timber.e(throwable, "Error getting track cover")
                                callback(null)
                            }
                    } else {
                        callback(null)
                    }
                }
                ?.setErrorCallback { throwable ->
                    Timber.e(throwable, "Error getting player state")
                    callback(null)
                }
        }

        fun getPlaylistCover(playlist: SpotifyPlaylist, callback: (android.graphics.Bitmap?) -> Unit) {
            if (playlist.uri.startsWith("spotify:section:")) {
                // For sections, we need to get the first child item's image
                val listItem = ListItem(
                    "",         // title
                    "",         // subtitle
                    null,       // imageUri
                    playlist.uri, // uri
                    "",         // category
                    true,       // hasChildren
                    true        // playable
                )

                spotifyAppRemote?.contentApi?.getChildrenOfItem(
                    listItem,
                    BROWSE_OPTIONS_ALL,
                    0
                )?.setResultCallback { result ->
                    val firstItem = result.items.firstOrNull()
                    if (firstItem?.imageUri != null) {
                        spotifyAppRemote?.imagesApi?.getImage(firstItem.imageUri)
                            ?.setResultCallback { bitmap ->
                                callback(bitmap)
                            }
                            ?.setErrorCallback { throwable ->
                                Timber.e(throwable, "Error getting section item cover")
                                callback(null)
                            }
                    } else {
                        callback(null)
                    }
                }?.setErrorCallback { error ->
                    Timber.e(error, "Error getting section children")
                    callback(null)
                }
            } else {
                // Regular playlists use the existing method
                playlist.imageUri?.let { uri ->
                    spotifyAppRemote?.imagesApi?.getImage(com.spotify.protocol.types.ImageUri(uri))
                        ?.setResultCallback { bitmap ->
                            callback(bitmap)
                        }
                        ?.setErrorCallback { throwable ->
                            Timber.e(throwable, "Error getting playlist cover")
                            callback(null)
                        }
                } ?: callback(null)
            }
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
                val playlistTitle: String? = null,
                val coverArt: android.graphics.Bitmap? = null,
                val trackUri: String? = null
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