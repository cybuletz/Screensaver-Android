package com.photostreamr.music

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMusicManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMusicPreferences: LocalMusicPreferences
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private var currentTracks = mutableListOf<LocalTrack>()
    private var currentTrackIndex = 0
    private var isScreensaverActive = false
    private val random = Random()
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
                _playbackPosition.value = mediaPlayer!!.currentPosition
                _playbackDuration.value = mediaPlayer!!.duration
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val RECONNECT_DELAY = 5000L // 5 seconds

    data class LocalTrack(
        val uri: Uri,
        val title: String?,
        val artist: String?,
        val album: String?,
        val duration: Long,
        val artworkUri: Uri? = null
    )

    sealed class ConnectionState {
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        object Disconnected : ConnectionState()
        data class Error(val error: Throwable? = null) : ConnectionState()
    }

    sealed class PlaybackState {
        data class Playing(val track: LocalTrack) : PlaybackState()
        data class Paused(val track: LocalTrack) : PlaybackState()
        object Idle : PlaybackState()
        data class Loading(val track: LocalTrack? = null) : PlaybackState()
        data class Error(val error: Throwable? = null) : PlaybackState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _playbackPosition = MutableLiveData<Int>(0)
    val playbackPosition: LiveData<Int> = _playbackPosition

    private val _playbackDuration = MutableLiveData<Int>(0)
    val playbackDuration: LiveData<Int> = _playbackDuration

    private val _currentTrack = MutableLiveData<LocalTrack?>(null)
    val currentTrack: LiveData<LocalTrack?> = _currentTrack

    fun setScreensaverActive(active: Boolean) {
        Timber.d("LocalMusicManager: Screensaver active: $active")
        this.isScreensaverActive = active

        if (active) {
            // Screensaver became active
            if (localMusicPreferences.isEnabled() && localMusicPreferences.isAutoplayEnabled()) {
                connect()
            }
        } else {
            // Screensaver became inactive
            if (_playbackState.value is PlaybackState.Playing) {
                localMusicPreferences.setWasPlaying(true)
                pause()
            } else {
                localMusicPreferences.setWasPlaying(false)
            }
            disconnect()
        }
    }

    fun connect() {
        if (_connectionState.value is ConnectionState.Connected) {
            Timber.d("LocalMusicManager already connected")
            return
        }

        val folderUri = localMusicPreferences.getMusicFolderUri()
        if (folderUri == null) {
            _connectionState.value = ConnectionState.Error(IllegalStateException("No music folder selected"))
            return
        }

        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            try {
                // Initialize MediaPlayer
                initializeMediaPlayer()

                // Load tracks from folder
                loadTracksFromFolder(folderUri)

                _connectionState.value = ConnectionState.Connected
                localMusicPreferences.setConnectionState(true)

                // If we should autoplay on screensaver activation
                if (isScreensaverActive && localMusicPreferences.isAutoplayEnabled()) {
                    if (currentTracks.isNotEmpty()) {
                        playTrack(0)
                    }
                } else if (localMusicPreferences.wasPlaying()) {
                    // Resume playback if music was playing before
                    play()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to local music")
                _connectionState.value = ConnectionState.Error(e)
                localMusicPreferences.setConnectionState(false)
            }
        }
    }

    private fun initializeMediaPlayer() {
        try {
            // Release any existing MediaPlayer
            releaseMediaPlayer()

            // Create new MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                setOnPreparedListener {
                    val currentLocalTrack = currentTracks.getOrNull(currentTrackIndex)
                    if (currentLocalTrack != null) {
                        _playbackState.value = PlaybackState.Paused(currentLocalTrack)
                        _currentTrack.value = currentLocalTrack
                        _playbackDuration.value = it.duration
                    }
                }

                setOnCompletionListener {
                    if (currentTracks.isNotEmpty()) {
                        playNext()
                    }
                }

                setOnErrorListener { _, what, extra ->
                    Timber.e("MediaPlayer error: $what, $extra")
                    _playbackState.value = PlaybackState.Error(Exception("MediaPlayer error: $what, $extra"))
                    true
                }
            }

            // Set volume from preferences
            setVolume(localMusicPreferences.getLastVolume())
        } catch (e: Exception) {
            Timber.e(e, "Error initializing MediaPlayer")
            throw e
        }
    }

    private suspend fun loadTracksFromFolder(folderUri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                currentTracks.clear()
                val folder = DocumentFile.fromTreeUri(context, folderUri)
                if (folder == null || !folder.exists() || !folder.isDirectory) {
                    throw IllegalStateException("Invalid music folder")
                }

                // Get all audio files in the folder
                val supportedFormats = arrayOf(".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a")
                folder.listFiles().forEach { file ->
                    if (file.isFile && file.name?.let { name ->
                            supportedFormats.any { format -> name.endsWith(format, ignoreCase = true) }
                        } == true) {
                        try {
                            val track = extractTrackMetadata(file.uri)
                            currentTracks.add(track)
                        } catch (e: Exception) {
                            Timber.e(e, "Error extracting metadata for ${file.name}")
                        }
                    }
                }

                // Save track URIs to preferences
                localMusicPreferences.saveTracksList(currentTracks.map { it.uri.toString() })

                // If shuffle is enabled, shuffle the tracks
                if (localMusicPreferences.isShuffleEnabled()) {
                    currentTracks.shuffle()
                }

                Timber.d("Loaded ${currentTracks.size} tracks from local folder")

                // Reset current track index
                currentTrackIndex = 0
            } catch (e: Exception) {
                Timber.e(e, "Error loading tracks from folder")
                throw e
            }
        }
    }

    private fun extractTrackMetadata(uri: Uri): LocalTrack {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Unknown Track"

            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            // For artwork, we could try to extract embedded album art, but for simplicity, we'll leave it null
            return LocalTrack(uri, title, artist, album, duration)
        } finally {
            retriever.release()
        }
    }

    fun playTrack(index: Int) {
        if (index < 0 || index >= currentTracks.size) {
            Timber.e("Invalid track index: $index")
            return
        }

        currentTrackIndex = index
        val track = currentTracks[index]
        _playbackState.value = PlaybackState.Loading(track)

        try {
            mediaPlayer?.apply {
                reset()
                setDataSource(context, track.uri)
                prepareAsync()
                setOnPreparedListener {
                    start()
                    _playbackState.value = PlaybackState.Playing(track)
                    _currentTrack.value = track
                    _playbackDuration.value = it.duration
                    // Start tracking progress
                    handler.removeCallbacks(progressRunnable)
                    handler.post(progressRunnable)
                }
            }

            // Save current track to preferences
            localMusicPreferences.setCurrentTrackUri(track.uri)
        } catch (e: Exception) {
            Timber.e(e, "Error playing track: ${track.title}")
            _playbackState.value = PlaybackState.Error(e)
        }
    }

    fun play() {
        val currentState = _playbackState.value

        when {
            currentState is PlaybackState.Paused -> {
                // Resume playback
                mediaPlayer?.start()
                _playbackState.value = PlaybackState.Playing(currentState.track)
                // Start tracking progress
                handler.removeCallbacks(progressRunnable)
                handler.post(progressRunnable)
            }
            currentState is PlaybackState.Idle -> {
                // Start new playback
                if (currentTracks.isNotEmpty()) {
                    playTrack(currentTrackIndex)
                }
            }
        }
    }

    fun pause() {
        val currentState = _playbackState.value

        if (currentState is PlaybackState.Playing) {
            mediaPlayer?.pause()
            _playbackState.value = PlaybackState.Paused(currentState.track)
            // Stop tracking progress
            handler.removeCallbacks(progressRunnable)
        }
    }

    fun playNext() {
        if (currentTracks.isEmpty()) return

        if (localMusicPreferences.isShuffleEnabled()) {
            // Play a random track other than the current one
            val nextIndex = if (currentTracks.size > 1) {
                var newIndex = random.nextInt(currentTracks.size)
                // Make sure we don't play the same track again
                while (newIndex == currentTrackIndex && currentTracks.size > 1) {
                    newIndex = random.nextInt(currentTracks.size)
                }
                newIndex
            } else {
                0
            }
            playTrack(nextIndex)
        } else {
            // Play next track in sequence
            val nextIndex = (currentTrackIndex + 1) % currentTracks.size
            playTrack(nextIndex)
        }
    }

    fun playPrevious() {
        if (currentTracks.isEmpty()) return

        // If we're more than 3 seconds into the song, restart it instead of going to previous
        if (mediaPlayer?.currentPosition ?: 0 > 3000) {
            mediaPlayer?.seekTo(0)
            return
        }

        if (localMusicPreferences.isShuffleEnabled()) {
            // Play a random track
            val prevIndex = random.nextInt(currentTracks.size)
            playTrack(prevIndex)
        } else {
            // Play previous track in sequence
            val prevIndex = if (currentTrackIndex > 0) currentTrackIndex - 1 else currentTracks.size - 1
            playTrack(prevIndex)
        }
    }

    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
        _playbackPosition.value = position
    }

    fun setVolume(volume: Float) {
        mediaPlayer?.setVolume(volume, volume)
        localMusicPreferences.setLastVolume(volume)
    }

    fun hasPermission(uri: Uri): Boolean {
        val contentResolver = context.contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        return try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            true
        } catch (e: SecurityException) {
            Timber.e(e, "No permission for uri: $uri")
            false
        }
    }

    fun disconnect() {
        // Stop tracking progress
        handler.removeCallbacks(progressRunnable)

        // Release MediaPlayer
        releaseMediaPlayer()

        // Clear current tracks and reset state
        currentTracks.clear()
        currentTrackIndex = 0
        _currentTrack.value = null
        _playbackState.value = PlaybackState.Idle
        _connectionState.value = ConnectionState.Disconnected
        _playbackPosition.value = 0
        _playbackDuration.value = 0

        localMusicPreferences.setConnectionState(false)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
    }

    fun getTrackByUri(uri: Uri): LocalTrack? {
        return currentTracks.find { it.uri == uri }
    }
}