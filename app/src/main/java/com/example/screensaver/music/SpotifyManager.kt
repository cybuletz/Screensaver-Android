    package com.example.screensaver.music

    import android.app.Activity
    import android.content.Context
    import android.content.Intent
    import android.content.pm.PackageManager
    import com.spotify.android.appremote.api.ConnectionParams
    import com.spotify.android.appremote.api.Connector
    import com.spotify.android.appremote.api.SpotifyAppRemote
    import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
    import com.spotify.android.appremote.api.error.NotLoggedInException
    import com.spotify.protocol.types.PlayerState
    import dagger.hilt.android.qualifiers.ApplicationContext
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import timber.log.Timber
    import javax.inject.Inject
    import javax.inject.Singleton
    import com.example.screensaver.BuildConfig
    import com.example.screensaver.data.SecureStorage
    import com.example.screensaver.music.SpotifyAuthManager.Companion
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.SupervisorJob
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.flow.asStateFlow
    import com.spotify.android.appremote.api.error.UserNotAuthorizedException
    import android.os.Handler
    import android.os.Looper
    import com.spotify.protocol.types.ListItem
    import com.spotify.protocol.types.ListItems



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

        fun connect() {
            // Don't try to connect if Spotify is not enabled
            if (!spotifyPreferences.isEnabled()) {
                return
            }

            if (spotifyAppRemote?.isConnected == true) {
                Timber.d("Already connected to Spotify")
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
                val connectionParams = ConnectionParams.Builder(CLIENT_ID)
                    .setRedirectUri(REDIRECT_URI)
                    .showAuthView(true)
                    .build()

                // Launch Spotify app first to ensure it's running
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)

                        // Give Spotify app time to launch before connecting
                        Handler(Looper.getMainLooper()).postDelayed({
                            SpotifyAppRemote.connect(context, connectionParams, object : Connector.ConnectionListener {
                                override fun onConnected(appRemote: SpotifyAppRemote) {
                                    spotifyAppRemote = appRemote
                                    _connectionState.value = ConnectionState.Connected
                                    _errorState.value = null
                                    Timber.d("Connected to Spotify")
                                    observePlayerState()
                                }

                                override fun onFailure(error: Throwable) {
                                    handleConnectionError(error)
                                }
                            })
                        }, 1000) // 1 second delay
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error launching Spotify app")
                    handleConnectionError(e)
                }
            } catch (e: Exception) {
                handleConnectionError(e)
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
                    // Launch Spotify app to ensure user is logged in
                    try {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            // Retry connection after a delay
                            Handler(Looper.getMainLooper()).postDelayed({
                                retry()
                            }, 2000)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error launching Spotify app")
                        _errorState.value = SpotifyError.AuthenticationRequired
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

        fun playPlaylist(playlistUri: String) {
            try {
                spotifyAppRemote?.playerApi?.play(playlistUri)
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error playing playlist")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun retry() {
            _errorState.value = null
            connect()
        }

        private fun observePlayerState() {
            spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback { playerState ->
                updatePlaybackState(playerState)
            }
        }

        private fun updatePlaybackState(playerState: PlayerState) {
            _playbackState.value = PlaybackState.Playing(
                isPlaying = !playerState.isPaused,
                trackName = playerState.track.name,
                artistName = playerState.track.artist.name,
                trackDuration = playerState.track.duration
            )
        }

        fun disconnect() {
            try {
                if (isScreensaverActive && spotifyPreferences.isAutoplayEnabled()) {
                    pause()
                }
                SpotifyAppRemote.disconnect(spotifyAppRemote)
            } catch (e: Exception) {
                Timber.e(e, "Error disconnecting from Spotify")
            } finally {
                spotifyAppRemote = null
                _connectionState.value = ConnectionState.Disconnected
                _playbackState.value = PlaybackState.Idle
            }
        }

        fun resume() {
            try {
                spotifyAppRemote?.playerApi?.resume()
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error resuming playback")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun pause() {
            try {
                spotifyAppRemote?.playerApi?.pause()
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error pausing playback")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun nextTrack() {
            try {
                spotifyAppRemote?.playerApi?.skipNext()
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error skipping to next track")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun previousTrack() {
            try {
                spotifyAppRemote?.playerApi?.skipPrevious()
                _errorState.value = null
            } catch (e: Exception) {
                Timber.e(e, "Error skipping to previous track")
                _errorState.value = SpotifyError.PlaybackFailed(e)
            }
        }

        fun getPlaylists(
            callback: (List<SpotifyPlaylist>) -> Unit,
            errorCallback: (Throwable) -> Unit
        ) {
            spotifyAppRemote?.contentApi?.getRecommendedContentItems("default")
                ?.setResultCallback { items: ListItems ->
                    val playlists = items.items
                        .filter { item: ListItem -> item.playable }
                        .map { item: ListItem ->
                            SpotifyPlaylist(
                                title = item.title,
                                uri = item.uri,
                                imageUri = item.imageUri.raw
                            )
                        }
                    callback(playlists)
                }
                ?.setErrorCallback { error ->
                    errorCallback(error)
                } ?: errorCallback(Exception("Spotify not connected"))
        }

        fun getPlaylistInfo(
            uri: String,
            callback: (SpotifyPlaylist?) -> Unit,
            errorCallback: (Throwable) -> Unit
        ) {
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
                val trackDuration: Long = 0
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
            val imageUri: String? = null
        )
    }