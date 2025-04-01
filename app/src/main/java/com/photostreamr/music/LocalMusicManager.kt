package com.photostreamr.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.photostreamr.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class LocalMusicManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: LocalMusicPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlaylist: List<LocalTrack> = emptyList()
    private var currentTrackIndex: Int = 0
    private var isScreensaverActive = false
    private var wasPlayingBeforeScreensaver = false
    private val handler = Handler(Looper.getMainLooper())
    private var updatePositionTask: Runnable? = null
    private var isShuffleEnabled = false
    private var repeatMode = RepeatMode.OFF

    private val albumArtCache = HashMap<String, Bitmap?>()
    private var originalPlaylistOrder: List<LocalTrack> = emptyList()

    // State flows
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentTrack = MutableStateFlow<LocalTrack?>(null)
    val currentTrack: StateFlow<LocalTrack?> = _currentTrack.asStateFlow()

    companion object {
        private const val TAG = "LocalMusicManager"
        private const val POSITION_UPDATE_INTERVAL = 1000L // 1 second
    }

    enum class RepeatMode {
        OFF, ONE, ALL
    }

    // Data classes
    data class LocalTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val path: String,
        val albumArtPath: String? = null
    )

    data class Playlist(
        val id: String,
        val name: String,
        val tracks: List<LocalTrack>
    )

    // Connection states
    sealed class ConnectionState {
        object Connected : ConnectionState()
        object Disconnected : ConnectionState()
        data class Error(val error: Throwable) : ConnectionState()
    }

    // Playback states
    sealed class PlaybackState {
        object Idle : PlaybackState()
        object Loading : PlaybackState()
        data class Playing(
            val trackName: String,
            val artistName: String,
            val albumName: String,
            val isPlaying: Boolean,
            val trackDuration: Long,
            val playbackPosition: Long,
            val playlistName: String? = null,
            val coverArt: Bitmap? = null
        ) : PlaybackState()
    }

    // Error states
    sealed class LocalMusicError {
        object NoMusicFound : LocalMusicError()
        data class PlaybackFailed(val exception: Throwable) : LocalMusicError()
        data class PermissionRequired(val permission: String) : LocalMusicError()
    }

    init {
        setupMediaPlayer()
    }

    fun initialize() {
        Timber.d("Initializing LocalMusic Manager")
        val isEnabled = preferences.isEnabled()
        val wasPlaying = preferences.wasPlaying()

        Timber.d("LocalMusic preferences - enabled: $isEnabled, wasPlaying: $wasPlaying")

        if (isEnabled) {
            _connectionState.value = ConnectionState.Connected
            restoreLastPlaybackState()
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun restoreLastPlaybackState() {
        val lastTrack = preferences.getLastTrack()
        val wasPlaying = preferences.wasPlaying()

        lastTrack?.let { track ->
            _currentTrack.value = track
            if (wasPlaying && preferences.isAutoplayEnabled()) {
                playTrack(track)
            } else {
                // Just show track info without playing
                updatePlaybackStateWithTrack(track, false)
            }
        }
    }

    fun initializeState() {
        if (!preferences.isEnabled()) return

        // On full app start, always read from preferences
        preferences.getLastTrack()?.let { track ->
            _currentTrack.value = track
            val wasPlaying = preferences.wasPlaying()

            _connectionState.value = ConnectionState.Connected
            updatePlaybackStateWithTrack(track, false)
            Timber.d("Initialized state with last track: ${track.title}, wasPlaying: $wasPlaying")
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

                // Update to playing state
                _currentTrack.value?.let { track ->
                    updatePlaybackStateWithTrack(track, true)
                    preferences.setWasPlaying(true)
                    startPositionUpdates()
                }
            }

            setOnErrorListener { _, what, extra ->
                val error = Exception("MediaPlayer error: what=$what, extra=$extra")
                Timber.e(error, "MediaPlayer error")
                _connectionState.value = ConnectionState.Error(error)
                true
            }

            setOnCompletionListener {
                Timber.d("MediaPlayer completed")
                handleTrackCompletion()
            }
        }
    }

    private fun handleTrackCompletion() {
        when (repeatMode) {
            RepeatMode.ONE -> {
                // Replay the current track
                _currentTrack.value?.let { playTrack(it) }
            }
            RepeatMode.ALL -> {
                // Play next track, or loop back to first if at end
                playNextTrack()
            }
            RepeatMode.OFF -> {
                // Play next track, or stop if at end
                if (currentTrackIndex < currentPlaylist.size - 1) {
                    playNextTrack()
                } else {
                    // Stop playback
                    updatePlaybackStateWithTrack(_currentTrack.value!!, false)
                    preferences.setWasPlaying(false)
                    stopPositionUpdates()
                }
            }
        }
    }

    private fun startPositionUpdates() {
        updatePositionTask?.let { handler.removeCallbacks(it) }
        updatePositionTask = object : Runnable {
            override fun run() {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val currentPosition = player.currentPosition.toLong()
                        // Instead of calling updatePlaybackStateWithTrack which updates everything,
                        // just update the progress
                        _playbackState.value = (_playbackState.value as? PlaybackState.Playing)?.copy(
                            playbackPosition = currentPosition
                        ) ?: _playbackState.value
                        handler.postDelayed(this, POSITION_UPDATE_INTERVAL)
                    }
                }
            }
        }
        handler.post(updatePositionTask!!)
    }

    private fun stopPositionUpdates() {
        updatePositionTask?.let { handler.removeCallbacks(it) }
        updatePositionTask = null
    }

    fun playTrack(track: LocalTrack) {
        Log.d(TAG, "Playing track: ${track.title}")
        scope.launch {
            try {
                // Clear any existing state
                mediaPlayer?.reset()

                // Update states to show loading
                _playbackState.value = PlaybackState.Loading
                _currentTrack.value = track
                _connectionState.value = ConnectionState.Connected

                // Save track but don't mark as playing yet
                preferences.setLastTrack(track)

                // Make sure the track is in the playlist for next/previous functionality
                if (!currentPlaylist.contains(track)) {
                    // If we have an empty playlist or it doesn't contain this track,
                    // initialize a new playlist with this track
                    currentPlaylist = listOf(track)
                    currentTrackIndex = 0

                    // Try to load more tracks from the same directory
                    scope.launch(Dispatchers.IO) {
                        try {
                            // Extract the directory from the track path
                            val trackPath = track.path
                            val directory = if (trackPath.startsWith("content://")) {
                                // For content URIs, we'll need to load the playlist differently
                                // For now, just use the single track
                                null
                            } else {
                                // For file paths, get the parent directory
                                File(trackPath).parentFile
                            }

                            if (directory != null && directory.exists()) {
                                // Scan the directory for more tracks
                                val tracks = scanDirectory(directory)
                                if (tracks.isNotEmpty()) {
                                    // Update playlist, keeping current track as the current index
                                    withContext(Dispatchers.Main) {
                                        val currentIndex = tracks.indexOfFirst { it.id == track.id }
                                        if (currentIndex >= 0) {
                                            currentPlaylist = tracks
                                            currentTrackIndex = currentIndex
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading additional tracks for playlist", e)
                        }
                    }
                }

                // Prepare media player
                withContext(Dispatchers.IO) {
                    mediaPlayer?.apply {
                        if (track.path.startsWith("content://")) {
                            // Handle content URI
                            setDataSource(context, Uri.parse(track.path))
                        } else {
                            // Handle file path
                            setDataSource(track.path)
                        }
                        prepareAsync() // This will trigger onPrepared when ready
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing track", e)
                _connectionState.value = ConnectionState.Error(e)
                _playbackState.value = PlaybackState.Idle
            }
        }
    }

    private fun updatePlaybackStateWithTrack(
        track: LocalTrack,
        isPlaying: Boolean,
        playbackPosition: Long = 0
    ) {
        // Load album art only if we don't have it cached already
        val coverArt = albumArtCache.getOrPut(track.id) {
            loadAlbumArt(track.path)
        }

        _playbackState.value = PlaybackState.Playing(
            trackName = track.title,
            artistName = track.artist,
            albumName = track.album,
            isPlaying = isPlaying,
            trackDuration = track.duration,
            playbackPosition = if (isPlaying) playbackPosition else 0,
            playlistName = preferences.getSelectedPlaylistName(),
            coverArt = coverArt
        )
    }

    fun loadAlbumArt(filePath: String): Bitmap? {
        try {
            val retriever = MediaMetadataRetriever()

            // Check if it's a content URI
            if (filePath.startsWith("content://")) {
                try {
                    // Use content resolver to open the file
                    val uri = Uri.parse(filePath)
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        val art = retriever.embeddedPicture
                        if (art != null) {
                            return BitmapFactory.decodeByteArray(art, 0, art.size)
                        }
                    }
                    // No embedded art found - return music note drawable
                    return getBitmapFromDrawable(R.drawable.ic_music_note)
                } catch (e: Exception) {
                    Timber.e(e, "Error loading album art from content URI")
                    return getBitmapFromDrawable(R.drawable.ic_music_note)
                }
            } else {
                // Regular file path
                try {
                    retriever.setDataSource(filePath)
                    val art = retriever.embeddedPicture
                    if (art != null) {
                        return BitmapFactory.decodeByteArray(art, 0, art.size)
                    }
                    // No embedded art found - return music note drawable
                    return getBitmapFromDrawable(R.drawable.ic_music_note)
                } catch (e: Exception) {
                    Timber.e(e, "Error loading album art from file path")
                    return getBitmapFromDrawable(R.drawable.ic_music_note)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading album art")
            return getBitmapFromDrawable(R.drawable.ic_music_note)
        }
    }

    // Helper method to convert a drawable resource into a bitmap
    private fun getBitmapFromDrawable(drawableId: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, drawableId)
        val bitmap = Bitmap.createBitmap(
            drawable?.intrinsicWidth ?: 200,
            drawable?.intrinsicHeight ?: 200,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable?.setBounds(0, 0, canvas.width, canvas.height)
        drawable?.draw(canvas)
        return bitmap
    }

    fun scanMusicFiles(callback: (List<LocalTrack>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val musicDirectoryPath = preferences.getMusicDirectory()
                Timber.d("Scanning music directory: $musicDirectoryPath")

                val tracks = if (musicDirectoryPath.startsWith("content://")) {
                    // Handle content URI
                    scanDocumentUri(Uri.parse(musicDirectoryPath))
                } else {
                    // Handle normal file path
                    val musicDirectory = File(musicDirectoryPath)
                    scanDirectory(musicDirectory)
                }

                // Sort tracks alphabetically by title for consistent ordering
                val sortedTracks = tracks.sortedBy { it.title }

                // Store the track list for playback
                if (sortedTracks.isNotEmpty()) {
                    // We can store this as our "detected" playlist
                    withContext(Dispatchers.Main) {
                        // Don't actually set as current playlist yet - we'll do that when a track is selected
                        Log.d(TAG, "Scanned ${sortedTracks.size} tracks")
                    }
                }

                withContext(Dispatchers.Main) {
                    callback(sortedTracks)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error scanning music files")
                withContext(Dispatchers.Main) {
                    callback(emptyList())
                }
            }
        }
    }

    private fun scanDirectory(directory: File): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()

        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                tracks.addAll(scanDirectory(file))
            } else if (isMusicFile(file.name)) {
                extractTrackMetadata(file)?.let { tracks.add(it) }
            }
        }

        return tracks
    }

    private fun isMusicFile(fileName: String): Boolean {
        val supportedExtensions = listOf(".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a")
        return supportedExtensions.any { fileName.lowercase().endsWith(it) }
    }

    private fun extractTrackMetadata(file: File): LocalTrack? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Unknown Album"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0"
            val duration = durationStr.toLongOrNull() ?: 0L

            LocalTrack(
                id = file.absolutePath.hashCode().toString(),
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                path = file.absolutePath
            )
        } catch (e: Exception) {
            Timber.e(e, "Error extracting metadata from ${file.absolutePath}")
            null
        }
    }

    fun setPlaylist(tracks: List<LocalTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) {
            Log.d(TAG, "Attempted to set empty playlist - ignoring")
            return
        }

        Log.d(TAG, "Setting playlist with ${tracks.size} tracks, starting at index $startIndex")

        // Store the original playlist order for un-shuffling later
        originalPlaylistOrder = tracks

        currentPlaylist = if (isShuffleEnabled) {
            // If shuffle is enabled, we need to ensure the selected track is first
            val selectedTrack = tracks.getOrNull(startIndex)
            if (selectedTrack != null) {
                val remainingTracks = tracks.filter { it != selectedTrack }.shuffled()
                listOf(selectedTrack) + remainingTracks
            } else {
                tracks.shuffled()
            }
        } else {
            tracks
        }

        currentTrackIndex = if (isShuffleEnabled) 0 else startIndex.coerceIn(0, currentPlaylist.size - 1)

        // Log the playlist for debugging
        Log.d(TAG, "Playlist set with ${currentPlaylist.size} tracks:")
        currentPlaylist.forEachIndexed { index, track ->
            Log.d(TAG, "[$index] ${track.title} by ${track.artist}")
        }
        Log.d(TAG, "Current track index: $currentTrackIndex")

        // Play the track at the current index
        playTrack(currentPlaylist[currentTrackIndex])
    }

    fun onScreensaverStarted() {
        isScreensaverActive = true
        if (preferences.isAutoplayEnabled()) {
            scope.launch {
                try {
                    // Store current playing state
                    wasPlayingBeforeScreensaver = (playbackState.value as? PlaybackState.Playing)?.isPlaying ?: false

                    // Start playback with selected playlist or resume current track
                    val selectedPlaylistId = preferences.getSelectedPlaylistId()
                    if (selectedPlaylistId != null) {
                        loadAndPlayPlaylist(selectedPlaylistId)
                    } else {
                        // If there's a current track but not playing, resume it
                        _currentTrack.value?.let { track ->
                            if (!wasPlayingBeforeScreensaver) {
                                playTrack(track)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error starting screensaver playback")
                }
            }
        }
    }

    fun onScreensaverStopped() {
        isScreensaverActive = false
        if (preferences.isAutoplayEnabled() && !wasPlayingBeforeScreensaver) {
            pause()
        }
        // Reset state
        wasPlayingBeforeScreensaver = false
    }

    private fun loadAndPlayPlaylist(playlistId: String) {
        scope.launch(Dispatchers.IO) {
            val playlist = preferences.getPlaylist(playlistId)
            if (playlist != null && playlist.tracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    setPlaylist(playlist.tracks)
                }
            }
        }
    }

    fun resume() {
        Log.d(TAG, "Resuming playback")
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                _currentTrack.value?.let { track ->
                    updatePlaybackStateWithTrack(track, true, player.currentPosition.toLong())
                    preferences.setWasPlaying(true)
                    startPositionUpdates()
                }
            }
        } ?: run {
            // If no media player or current track, try to restore from last session
            _currentTrack.value?.let { playTrack(it) }
        }
    }

    fun pause() {
        Log.d(TAG, "Pausing playback")
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _currentTrack.value?.let { track ->
                    updatePlaybackStateWithTrack(track, false, player.currentPosition.toLong())
                    preferences.setWasPlaying(false)
                    stopPositionUpdates()
                }
            }
        }
    }

    fun playNextTrack() {
        // Dump the current playlist state for debugging
        Log.d(TAG, "playNextTrack called")
        Log.d(TAG, "Current playlist has ${currentPlaylist.size} tracks")
        Log.d(TAG, "Current track index: $currentTrackIndex")
        currentPlaylist.forEachIndexed { index, track ->
            Log.d(TAG, "Playlist[$index]: ${track.title} by ${track.artist}")
        }

        if (currentPlaylist.isEmpty()) {
            Log.d(TAG, "Cannot play next track - playlist is empty")
            return
        }

        Log.d(TAG, "Playing next track. Current index: $currentTrackIndex, Playlist size: ${currentPlaylist.size}")

        currentTrackIndex = when {
            repeatMode == RepeatMode.ALL && currentTrackIndex >= currentPlaylist.size - 1 -> {
                Log.d(TAG, "Looping back to first track (repeat ALL mode)")
                0
            }
            else -> {
                val newIndex = (currentTrackIndex + 1) % currentPlaylist.size
                Log.d(TAG, "Moving to next track at index $newIndex")
                newIndex
            }
        }

        val nextTrack = currentPlaylist[currentTrackIndex]
        Log.d(TAG, "Next track: ${nextTrack.title} by ${nextTrack.artist}")
        playTrack(nextTrack)
    }

    fun playPreviousTrack() {
        // Dump the current playlist state for debugging
        Log.d(TAG, "playPreviousTrack called")
        Log.d(TAG, "Current playlist has ${currentPlaylist.size} tracks")
        Log.d(TAG, "Current track index: $currentTrackIndex")
        currentPlaylist.forEachIndexed { index, track ->
            Log.d(TAG, "Playlist[$index]: ${track.title} by ${track.artist}")
        }

        if (currentPlaylist.isEmpty()) {
            Log.d(TAG, "Cannot play previous track - playlist is empty")
            return
        }

        Log.d(TAG, "Playing previous track. Current index: $currentTrackIndex, Playlist size: ${currentPlaylist.size}")

        // If we're more than 3 seconds into a track, restart it instead of going to previous
        val currentPosition = mediaPlayer?.currentPosition ?: 0
        if (currentPosition > 3000) {
            Log.d(TAG, "Current position > 3 seconds, restarting current track")
            mediaPlayer?.seekTo(0)
            return
        }

        currentTrackIndex = when {
            currentTrackIndex > 0 -> {
                val newIndex = currentTrackIndex - 1
                Log.d(TAG, "Moving to previous track at index $newIndex")
                newIndex
            }
            repeatMode == RepeatMode.ALL -> {
                val newIndex = currentPlaylist.size - 1
                Log.d(TAG, "Looping to last track (repeat ALL mode)")
                newIndex
            }
            else -> {
                Log.d(TAG, "At first track, staying at index 0")
                0
            }
        }

        val prevTrack = currentPlaylist[currentTrackIndex]
        Log.d(TAG, "Previous track: ${prevTrack.title} by ${prevTrack.artist}")
        playTrack(prevTrack)
    }

    fun setShuffleMode(enabled: Boolean) {
        if (isShuffleEnabled != enabled) {
            Log.d(TAG, "Setting shuffle mode to $enabled")
            isShuffleEnabled = enabled
            preferences.setShuffleEnabled(enabled)

            // If we have a current playlist, shuffle it while keeping current track as first
            if (currentPlaylist.isNotEmpty() && _currentTrack.value != null) {
                val currentTrack = _currentTrack.value!!

                if (enabled) {
                    // Shuffle mode enabled - current track first, rest shuffled
                    val remainingTracks = currentPlaylist.filter { it.id != currentTrack.id }.shuffled()
                    currentPlaylist = listOf(currentTrack) + remainingTracks
                    currentTrackIndex = 0 // Current track is now at index 0
                    Log.d(TAG, "Playlist shuffled with ${currentPlaylist.size} tracks, current track as first")
                } else {
                    // Shuffle mode disabled - restore original order
                    currentPlaylist = originalPlaylistOrder
                    // Find where the current track is in the original order
                    currentTrackIndex = currentPlaylist.indexOfFirst { it.id == currentTrack.id }
                    if (currentTrackIndex < 0) currentTrackIndex = 0
                    Log.d(TAG, "Restored original playlist order, current track at index $currentTrackIndex")
                }

                // Update UI but don't change the playing track
                _currentTrack.value?.let { track ->
                    val isPlaying = (playbackState.value as? PlaybackState.Playing)?.isPlaying ?: false
                    updatePlaybackStateWithTrack(track, isPlaying, mediaPlayer?.currentPosition?.toLong() ?: 0)
                }
            }
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        preferences.setRepeatMode(mode.name)
    }

    fun seekTo(position: Long) {
        mediaPlayer?.seekTo(position.toInt())
    }

    fun disconnect() {
        // Store state before ANY changes
        val currentTrack = _currentTrack.value
        val isCurrentlyPlaying = (playbackState.value as? PlaybackState.Playing)?.isPlaying ?: false

        // Always update preferences first
        preferences.setWasPlaying(isCurrentlyPlaying)
        currentTrack?.let { track ->
            preferences.setLastTrack(track)
            Timber.d("Storing last track on disconnect: ${track.title}, wasPlaying: $isCurrentlyPlaying")
        }

        // Stop position updates
        stopPositionUpdates()

        // Now safe to disconnect
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            reset()
        }

        // Update states but maintain track info
        _connectionState.value = ConnectionState.Disconnected
        if (currentTrack != null) {
            updatePlaybackStateWithTrack(currentTrack, false)
        } else {
            _playbackState.value = PlaybackState.Idle
        }
    }

    private fun scanDocumentUri(treeUri: Uri): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()

        try {
            // Get the DocumentFile from the tree URI
            val documentFile = DocumentFile.fromTreeUri(context, treeUri)
            if (documentFile == null || !documentFile.exists() || !documentFile.isDirectory) {
                Timber.e("Invalid document tree URI: $treeUri")
                return emptyList()
            }

            // Scan all files in the directory tree
            scanDocumentDirectory(documentFile, tracks)

            Timber.d("Found ${tracks.size} music files in document tree")
            return tracks
        } catch (e: Exception) {
            Timber.e(e, "Error scanning document tree: $treeUri")
            return emptyList()
        }
    }

    private fun scanDocumentDirectory(directory: DocumentFile, tracks: MutableList<LocalTrack>) {
        try {
            // Process all files in this directory
            directory.listFiles().forEach { file ->
                if (file.isDirectory) {
                    // Recursively scan subdirectories
                    scanDocumentDirectory(file, tracks)
                } else if (isMusicFile(file.name ?: "")) {
                    // Process music file
                    extractDocumentTrackMetadata(file)?.let {
                        tracks.add(it)
                        Timber.d("Added track: ${it.title}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error scanning directory: ${directory.uri}")
        }
    }

    private fun extractDocumentTrackMetadata(file: DocumentFile): LocalTrack? {
        return try {
            val retriever = MediaMetadataRetriever()

            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: file.name?.substringBeforeLast('.')
                    ?: "Unknown Title"

                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: "Unknown Artist"

                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?: "Unknown Album"

                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?: "0"

                val duration = durationStr.toLongOrNull() ?: 0L

                // Create track with URI as path
                LocalTrack(
                    id = file.uri.toString().hashCode().toString(),
                    title = title,
                    artist = artist,
                    album = album,
                    duration = duration,
                    path = file.uri.toString()
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error extracting metadata from: ${file.uri}")
            null
        }
    }

    fun cleanup() {
        stopPositionUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}