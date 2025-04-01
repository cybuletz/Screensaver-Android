package com.photostreamr.widgets

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.photostreamr.R
import com.photostreamr.music.LocalMusicManager
import com.photostreamr.music.LocalMusicPreferences
import com.photostreamr.music.SpotifyManager
import com.photostreamr.music.RadioManager
import com.photostreamr.music.RadioPreferences
import com.photostreamr.music.SpotifyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob

class MusicControlWidget(
    private val container: ViewGroup,
    var config: WidgetConfig.MusicConfig,
    private val spotifyManager: SpotifyManager,
    private val spotifyPreferences: SpotifyPreferences,
    private val radioManager: RadioManager,
    private val radioPreferences: RadioPreferences,
    private val localMusicManager: LocalMusicManager,
    private val localMusicPreferences: LocalMusicPreferences
) : ScreenWidget {
    private var binding: MusicControlWidgetBinding? = null
    private var isVisible = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressUpdateJob: Job? = null

    private var lastRadioStation: RadioManager.RadioStation? = null
    private var wasRadioPlaying: Boolean = false

    private var currentTrackUri: String? = null
    private var currentLocalTrackId: String? = null
    private var cachedTrackName: String? = null
    private var cachedArtistName: String? = null


    companion object {
        private const val TAG = "MusicControlWidget"
        private const val MUSIC_SOURCE_SPOTIFY = "spotify"
        private const val MUSIC_SOURCE_RADIO = "radio"
        private const val MUSIC_SOURCE_LOCAL = "local"
    }

    override fun init() {
        try {
            Log.d(TAG, "Initializing MusicControlWidget with config: $config")
            binding = MusicControlWidgetBinding(container).apply {
                inflate()

                // Get direct references to the buttons
                val playPauseButton = getRootView()?.findViewById<ImageButton>(R.id.play_pause_button)
                val previousButton = getRootView()?.findViewById<ImageButton>(R.id.previous_button)
                val nextButton = getRootView()?.findViewById<ImageButton>(R.id.next_button)

                // Set up click listeners directly
                playPauseButton?.setOnClickListener { _ ->
                    Log.d(TAG, "Play/Pause button direct click, source: ${getMusicSource()}")
                    when (getMusicSource()) {
                        MUSIC_SOURCE_SPOTIFY -> handleSpotifyPlayPause()
                        MUSIC_SOURCE_RADIO -> handleRadioPlayPause()
                        MUSIC_SOURCE_LOCAL -> handleLocalMusicPlayPause() // Add local music handler
                    }
                }

                previousButton?.setOnClickListener { view ->
                    Log.d(TAG, "Previous button direct click")
                    when (getMusicSource()) {
                        MUSIC_SOURCE_SPOTIFY -> {
                            if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                                view.isEnabled = false
                                spotifyManager.previousTrack()
                                view.postDelayed({ view.isEnabled = true }, 500)
                            }
                        }
                        MUSIC_SOURCE_LOCAL -> {
                            // Handle local music previous
                            localMusicManager.playPreviousTrack()
                        }
                    }
                }

                nextButton?.setOnClickListener { view ->
                    Log.d(TAG, "Next button direct click")
                    when (getMusicSource()) {
                        MUSIC_SOURCE_SPOTIFY -> {
                            if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                                view.isEnabled = false
                                spotifyManager.nextTrack()
                                view.postDelayed({ view.isEnabled = true }, 500)
                            }
                        }
                        MUSIC_SOURCE_LOCAL -> {
                            // Handle local music next
                            localMusicManager.playNextTrack()
                        }
                    }
                }

                // Enable all buttons
                playPauseButton?.isEnabled = true
                previousButton?.isEnabled = true
                nextButton?.isEnabled = true
            }

            updateConfiguration(config)

            // Spotify state observers (unchanged)
            scope.launch {
                spotifyManager.connectionState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_SPOTIFY) {
                        Log.d(TAG, "Spotify connection state update: $state")
                        when (state) {
                            is SpotifyManager.ConnectionState.Connected -> {
                                Log.d(TAG, "Spotify connected, enabling controls")
                                clearErrorState()
                                updatePlaybackState(spotifyManager.playbackState.value)
                            }
                            is SpotifyManager.ConnectionState.Disconnected -> {
                                Log.d(TAG, "Spotify disconnected, disabling controls")
                                updateErrorState("Spotify disconnected")
                            }
                            is SpotifyManager.ConnectionState.Error -> {
                                Log.d(TAG, "Spotify connection error: ${state.error}")
                                updateErrorState("Connection error")
                            }
                        }
                    }
                }
            }

            scope.launch {
                spotifyManager.playbackState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_SPOTIFY) {
                        Log.d(TAG, "Spotify playback state update received: $state")
                        if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                            Log.d(TAG, "Updating widget with new Spotify playback state")
                            updatePlaybackState(state)
                        } else {
                            Log.d(TAG, "Ignoring Spotify playback state update - not connected")
                        }
                    }
                }
            }

            // Radio state observers (unchanged)
            scope.launch {
                radioManager.connectionState.collect { state ->
                    handleRadioConnectionStateChange(state)
                }
            }

            scope.launch {
                radioManager.playbackState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_RADIO) {
                        Log.d(TAG, "Radio playback state update received: $state")
                        // Only update UI if we're connected or have cached state
                        if (radioManager.connectionState.value is RadioManager.ConnectionState.Connected
                            || lastRadioStation != null) {
                            updateRadioPlaybackState(state)
                        }
                    }
                }
            }

            // Add local music state observers
            scope.launch {
                localMusicManager.connectionState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_LOCAL) {
                        Log.d(TAG, "Local music connection state update: $state")
                        when (state) {
                            is LocalMusicManager.ConnectionState.Connected -> {
                                clearErrorState()
                                updateLocalMusicPlaybackState(localMusicManager.playbackState.value)
                            }
                            is LocalMusicManager.ConnectionState.Disconnected -> {
                                // Show friendly disconnected state
                                binding?.apply {
                                    getTrackNameView()?.text = "Local Music"
                                    getArtistNameView()?.text = "Select a track to play"
                                }
                                updateButtonState(true)
                            }
                            is LocalMusicManager.ConnectionState.Error -> {
                                updateErrorState("Local music error: ${state.error.message}")
                            }
                        }
                    }
                }
            }

            scope.launch {
                localMusicManager.playbackState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_LOCAL) {
                        Log.d(TAG, "Local music playback state update received: $state")
                        updateLocalMusicPlaybackState(state)
                    }
                }
            }

            if (config.enabled) {
                show()
            } else {
                hide()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MusicControlWidget", e)
        }
    }

    private fun updateProgressBarOnly(newPosition: Int) {
        binding?.getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
            progress = newPosition
        }
    }

    private fun getMusicSource(): String {
        return PreferenceManager.getDefaultSharedPreferences(container.context)
            .getString("music_source", MUSIC_SOURCE_SPOTIFY) ?: MUSIC_SOURCE_SPOTIFY
    }

    private fun handleLocalMusicPlayPause() {
        when (val state = localMusicManager.playbackState.value) {
            is LocalMusicManager.PlaybackState.Playing -> {
                if (state.isPlaying) {
                    localMusicManager.pause()
                } else {
                    localMusicManager.resume()
                }
            }
            is LocalMusicManager.PlaybackState.Idle -> {
                // Try to resume from last track
                localMusicPreferences.getLastTrack()?.let { track ->
                    localMusicManager.playTrack(track)
                }
            }
            LocalMusicManager.PlaybackState.Loading -> {
                // Do nothing during loading
                Log.d(TAG, "Ignoring play/pause while loading track")
            }
        }
    }

    private fun updateLocalMusicPlaybackState(state: LocalMusicManager.PlaybackState) {
        binding?.apply {
            when (state) {
                is LocalMusicManager.PlaybackState.Playing -> {
                    // Hide loading indicator
                    getLoadingIndicator()?.visibility = View.GONE

                    // Get track ID from current track
                    val trackId = localMusicManager.currentTrack.value?.id

                    // Only update artwork if the track has changed
                    if (trackId != currentLocalTrackId) {
                        currentLocalTrackId = trackId

                        // Reset text caches when track changes
                        cachedTrackName = null
                        cachedArtistName = null

                        // Show artwork if available and enabled
                        if (config.showArtwork) {
                            val coverArt = state.coverArt
                            if (coverArt != null) {
                                getTrackArtworkBackground()?.apply {
                                    // Stop any running animations
                                    animate().cancel()

                                    post {
                                        setImageBitmap(coverArt)
                                        alpha = 0f
                                        visibility = View.VISIBLE
                                        animate()
                                            .alpha(1.0f)
                                            .setDuration(300)
                                            .start()
                                    }
                                }
                            } else {
                                // Use generic music note icon
                                getTrackArtworkBackground()?.apply {
                                    // Stop any running animations
                                    animate().cancel()

                                    post {
                                        setImageResource(R.drawable.ic_music_note)
                                        alpha = 0f
                                        visibility = View.VISIBLE
                                        animate()
                                            .alpha(1.0f)
                                            .setDuration(300)
                                            .start()
                                    }
                                }
                            }
                        } else {
                            getTrackArtworkBackground()?.visibility = View.GONE
                        }
                    }

                    // Update text views only if they've changed
                    getTrackNameView()?.apply {
                        if (cachedTrackName != state.trackName) {
                            cachedTrackName = state.trackName
                            text = state.trackName
                            isSelected = true  // For marquee effect
                        }
                    }
                    getArtistNameView()?.apply {
                        if (cachedArtistName != state.artistName) {
                            cachedArtistName = state.artistName
                            text = state.artistName
                            isSelected = true  // For marquee effect
                        }
                    }

                    // Update play/pause button
                    getPlayPauseButton()?.apply {
                        setImageResource(
                            if (state.isPlaying) R.drawable.ic_music_pause
                            else R.drawable.ic_music_play
                        )
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                    }

                    // Show navigation buttons
                    getPreviousButton()?.apply {
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                        visibility = View.VISIBLE

                        // Make sure we have the right click listener
                        setOnClickListener {
                            Log.d(TAG, "Previous button click from update")
                            localMusicManager.playPreviousTrack()
                        }
                    }
                    getNextButton()?.apply {
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                        visibility = View.VISIBLE

                        // Make sure we have the right click listener
                        setOnClickListener {
                            Log.d(TAG, "Next button click from update")
                            localMusicManager.playNextTrack()
                        }
                    }

                    // Update progress bar
                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                        max = state.trackDuration.toInt()
                        progress = state.playbackPosition.toInt()
                        visibility = if (config.showProgress) View.VISIBLE else View.GONE
                    }

                    // Update progress updates
                    if (state.isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates()
                    }
                }

                is LocalMusicManager.PlaybackState.Idle -> {
                    // Reset caches
                    cachedTrackName = null
                    cachedArtistName = null

                    // Hide loading indicator
                    getLoadingIndicator()?.visibility = View.GONE

                    // Hide or reset artwork
                    getTrackArtworkBackground()?.apply {
                        animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction {
                                visibility = View.GONE
                                setImageBitmap(null)
                            }
                            .start()
                    }

                    // Update text views
                    getTrackNameView()?.apply {
                        text = "Local Music"
                        isSelected = false
                    }
                    getArtistNameView()?.apply {
                        text = "Select a track to play"
                        isSelected = false
                    }

                    // Update play button
                    getPlayPauseButton()?.apply {
                        setImageResource(R.drawable.ic_music_play)
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                    }

                    // Disable navigation buttons
                    getPreviousButton()?.apply {
                        isEnabled = false
                        isClickable = false
                        visibility = View.VISIBLE
                    }
                    getNextButton()?.apply {
                        isEnabled = false
                        isClickable = false
                        visibility = View.VISIBLE
                    }

                    // Reset progress bar
                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                        progress = 0
                        visibility = if (config.showProgress) View.VISIBLE else View.GONE
                    }

                    // Stop progress updates
                    stopProgressUpdates()
                }

                LocalMusicManager.PlaybackState.Loading -> {
                    // Reset caches
                    cachedTrackName = null
                    cachedArtistName = null

                    // Show loading indicator
                    getLoadingIndicator()?.visibility = View.VISIBLE

                    // Update text to show loading state
                    getTrackNameView()?.apply {
                        text = "Loading..."
                        isSelected = true
                    }

                    // Disable controls during loading
                    getPlayPauseButton()?.apply {
                        isEnabled = false
                        alpha = 0.5f
                    }
                    getPreviousButton()?.apply {
                        isEnabled = false
                        alpha = 0.5f
                    }
                    getNextButton()?.apply {
                        isEnabled = false
                        alpha = 0.5f
                    }
                }
            }
        }
    }

    private fun handleSpotifyPlayPause() {
        when (spotifyManager.connectionState.value) {
            !is SpotifyManager.ConnectionState.Connected -> {
                Log.e(TAG, "Spotify not connected - attempting to connect")
                spotifyManager.connect()
            }
            else -> {
                when (val state = spotifyManager.playbackState.value) {
                    is SpotifyManager.PlaybackState.Playing -> {
                        if (state.isPlaying) spotifyManager.pause() else spotifyManager.resume()
                    }
                    is SpotifyManager.PlaybackState.Idle -> spotifyManager.resume()
                }
            }
        }
    }

    private fun handleRadioPlayPause() {
        when (val state = radioManager.playbackState.value) {
            is RadioManager.PlaybackState.Playing -> {
                if (state.isPlaying) radioManager.pause() else radioManager.resume()
            }
            RadioManager.PlaybackState.Idle -> {
                // Check if we have a last station and radio was playing
                if (radioPreferences.wasPlaying()) {
                    radioPreferences.getLastStation()?.let { station ->
                        radioManager.playStation(station)
                    }
                } else {
                    // If no last station or wasn't playing, try to get any last station
                    radioPreferences.getLastStation()?.let { station ->
                        radioManager.playStation(station)
                    }
                }
            }
            RadioManager.PlaybackState.Loading -> {
                // Do nothing while loading - button should be disabled anyway
                Log.d(TAG, "Ignoring play/pause while station is loading")
            }
        }
    }

    private fun updatePlaybackState(state: SpotifyManager.PlaybackState) {
        Log.d(TAG, "Updating Spotify playback state: $state")

        if (spotifyManager.connectionState.value !is SpotifyManager.ConnectionState.Connected) {
            Log.d(TAG, "Not updating state - Spotify not connected")
            stopProgressUpdates()
            binding?.getTrackArtworkBackground()?.visibility = View.GONE
            return
        }

        binding?.apply {
            when (state) {
                is SpotifyManager.PlaybackState.Playing -> {
                    if (state.trackUri != currentTrackUri) {
                        currentTrackUri = state.trackUri

                        // Reset text caches when track changes
                        cachedTrackName = null
                        cachedArtistName = null

                        state.trackUri?.let { uri ->
                            // Only load and show artwork if enabled
                            if (config.showArtwork) {
                                spotifyManager.getTrackArtwork(uri) { bitmap ->
                                    if (bitmap != null) {
                                        getTrackArtworkBackground()?.apply {
                                            post {
                                                setImageBitmap(bitmap)
                                                alpha = 0f
                                                visibility = View.VISIBLE
                                                animate()
                                                    .alpha(1.0f)
                                                    .setDuration(300)
                                                    .start()
                                            }
                                        }
                                    }
                                }
                            } else {
                                getTrackArtworkBackground()?.visibility = View.GONE
                            }
                        }
                    }

                    Log.d(TAG, "Setting up Spotify Playing state UI")

                    // Update text views only if content changed
                    getTrackNameView()?.apply {
                        if (cachedTrackName != state.trackName) {
                            cachedTrackName = state.trackName
                            text = state.trackName
                            isSelected = true
                        }
                    }
                    getArtistNameView()?.apply {
                        if (cachedArtistName != state.artistName) {
                            cachedArtistName = state.artistName
                            text = state.artistName
                            isSelected = true
                        }
                    }

                    getPlayPauseButton()?.apply {
                        Log.d(TAG, "Configuring play/pause button - isEnabled will be true")
                        setImageResource(
                            if (state.isPlaying) R.drawable.ic_music_pause
                            else R.drawable.ic_music_play
                        )
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                    }
                    getPreviousButton()?.apply {
                        Log.d(TAG, "Configuring previous button - isEnabled will be true")
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                        visibility = View.VISIBLE
                    }
                    getNextButton()?.apply {
                        Log.d(TAG, "Configuring next button - isEnabled will be true")
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                        visibility = View.VISIBLE
                    }

                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                        max = state.trackDuration.toInt()
                        progress = state.playbackPosition.toInt()
                        visibility = if (config.showProgress) View.VISIBLE else View.GONE
                        Log.d(TAG, "Progress bar updated - duration: ${state.trackDuration}, position: ${state.playbackPosition}")
                    }

                    if (state.isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates()
                    }
                }
                SpotifyManager.PlaybackState.Idle -> {
                    // Reset caches
                    cachedTrackName = null
                    cachedArtistName = null

                    getTrackArtworkBackground()?.apply {
                        animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction {
                                visibility = View.GONE
                                setImageBitmap(null)
                            }
                            .start()
                    }
                    currentTrackUri = null

                    Log.d(TAG, "Setting up Spotify Idle state UI")
                    stopProgressUpdates()
                    getTrackNameView()?.apply {
                        text = if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected)
                            "Select a track to play"
                        else
                            "Not connected"
                        isSelected = false
                    }
                    getArtistNameView()?.apply {
                        text = ""
                        isSelected = false
                    }
                    getPlayPauseButton()?.apply {
                        val shouldBeEnabled = spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected
                        Log.d(TAG, "Configuring play/pause button - isEnabled will be $shouldBeEnabled")
                        setImageResource(R.drawable.ic_music_play)
                        isEnabled = shouldBeEnabled
                        isClickable = shouldBeEnabled
                        isFocusable = shouldBeEnabled
                    }
                    getPreviousButton()?.apply {
                        isEnabled = false
                        isClickable = false
                        visibility = View.VISIBLE
                    }
                    getNextButton()?.apply {
                        isEnabled = false
                        isClickable = false
                        visibility = View.VISIBLE
                    }

                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                        progress = 0
                        visibility = if (config.showProgress) View.VISIBLE else View.GONE
                        Log.d(TAG, "Progress bar reset to 0")
                    }
                }
            }
        } ?: Log.d(TAG, "Binding is null during updatePlaybackState!")
    }

    private fun updateRadioPlaybackState(state: RadioManager.PlaybackState) {
        binding?.apply {
            when (state) {
                is RadioManager.PlaybackState.Playing -> {
                    // Cache state when playing
                    lastRadioStation = radioManager.currentStation.value
                    wasRadioPlaying = state.isPlaying

                    // Hide loading indicator
                    getLoadingIndicator()?.visibility = View.GONE

                    // Handle station logo/artwork
                    if (config.showArtwork) {
                        radioManager.currentStation.value?.let { station ->
                            if (!station.favicon.isNullOrEmpty()) {
                                radioManager.loadStationLogo(station) { bitmap ->
                                    if (bitmap != null) {
                                        getTrackArtworkBackground()?.apply {
                                            post {
                                                setImageBitmap(bitmap)
                                                alpha = 0f
                                                visibility = View.VISIBLE
                                                animate()
                                                    .alpha(1.0f)
                                                    .setDuration(300)
                                                    .start()
                                            }
                                        }
                                    } else {
                                        getTrackArtworkBackground()?.visibility = View.GONE
                                    }
                                }
                            } else {
                                getTrackArtworkBackground()?.visibility = View.GONE
                            }
                        }
                    } else {
                        getTrackArtworkBackground()?.visibility = View.GONE
                    }

                    // Update text views only if content changed
                    getTrackNameView()?.apply {
                        if (cachedTrackName != state.stationName) {
                            cachedTrackName = state.stationName
                            text = state.stationName
                            isSelected = true
                        }
                    }
                    getArtistNameView()?.apply {
                        val genre = state.genre ?: ""
                        if (cachedArtistName != genre) {
                            cachedArtistName = genre
                            text = genre
                            isSelected = true
                        }
                    }

                    getPlayPauseButton()?.apply {
                        setImageResource(
                            if (state.isPlaying) R.drawable.ic_music_pause
                            else R.drawable.ic_music_play
                        )
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                    }
                    // Hide navigation buttons for radio
                    getPreviousButton()?.visibility = View.GONE
                    getNextButton()?.visibility = View.GONE

                    // Hide progress bar for radio
                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.visibility = View.GONE
                }

                RadioManager.PlaybackState.Loading -> {
                    // Reset caches
                    cachedTrackName = null
                    cachedArtistName = null

                    // Show loading indicator
                    getLoadingIndicator()?.visibility = View.VISIBLE

                    // Keep current station name but show loading state
                    getTrackNameView()?.apply {
                        text = "Loading station..."
                        isSelected = true
                    }
                    getArtistNameView()?.apply {
                        text = radioManager.currentStation.value?.name ?: ""
                        isSelected = true
                    }

                    // Disable controls during loading
                    getPlayPauseButton()?.apply {
                        setImageResource(R.drawable.ic_music_pause)
                        isEnabled = false
                        isClickable = false
                        isFocusable = false
                        alpha = 0.5f
                    }

                    // Hide navigation buttons
                    getPreviousButton()?.visibility = View.GONE
                    getNextButton()?.visibility = View.GONE

                    // Hide progress bar
                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.visibility = View.GONE

                    // Keep artwork but dim it during loading
                    getTrackArtworkBackground()?.apply {
                        if (visibility == View.VISIBLE) {
                            animate()
                                .alpha(0.5f)
                                .setDuration(300)
                                .start()
                        }
                    }
                }

                RadioManager.PlaybackState.Idle -> {
                    // Reset caches
                    cachedTrackName = null
                    cachedArtistName = null

                    // Hide loading indicator
                    getLoadingIndicator()?.visibility = View.GONE

                    // Clear artwork with animation
                    getTrackArtworkBackground()?.apply {
                        animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction {
                                visibility = View.GONE
                                setImageBitmap(null)
                            }
                            .start()
                    }

                    // Use cached state or get from preferences
                    val station = lastRadioStation ?: radioPreferences.getLastStation()

                    getTrackNameView()?.apply {
                        text = station?.name ?: "Select a radio station"
                        isSelected = station != null
                    }
                    getArtistNameView()?.apply {
                        text = station?.genre ?: ""
                        isSelected = station != null
                    }
                    getPlayPauseButton()?.apply {
                        setImageResource(R.drawable.ic_music_play)
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                        alpha = 1.0f
                    }
                    getPreviousButton()?.visibility = View.GONE
                    getNextButton()?.visibility = View.GONE
                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.visibility = View.GONE
                }
            }
        }
    }

    private fun handleRadioConnectionStateChange(state: RadioManager.ConnectionState) {
        if (getMusicSource() == MUSIC_SOURCE_RADIO) {
            Log.e(TAG, "Radio connection state update: $state")
            when (state) {
                is RadioManager.ConnectionState.Connected -> {
                    clearErrorState()
                    // No need to clear cached state here
                }
                is RadioManager.ConnectionState.Disconnected -> {
                    // Cache the current state before disconnect
                    if (radioManager.currentStation.value != null) {
                        lastRadioStation = radioManager.currentStation.value
                        wasRadioPlaying = (radioManager.playbackState.value as? RadioManager.PlaybackState.Playing)?.isPlaying ?: false
                    }

                    // Instead of showing error, show the last known state
                    binding?.apply {
                        getTrackNameView()?.apply {
                            text = lastRadioStation?.name ?: "Select a radio station"
                            isSelected = lastRadioStation != null
                        }
                        getArtistNameView()?.apply {
                            text = lastRadioStation?.genre ?: ""
                            isSelected = lastRadioStation != null
                        }
                    }
                    // Keep buttons enabled
                    updateButtonState(true)
                }
                is RadioManager.ConnectionState.Error -> {
                    updateErrorState("Radio error")
                }
            }
        }
    }

    private fun updateButtonState(enabled: Boolean) {
        binding?.apply {
            getPlayPauseButton()?.apply {
                isEnabled = enabled
                alpha = if (enabled) 1.0f else 0.7f
            }
            getPreviousButton()?.apply {
                isEnabled = enabled && getMusicSource() == MUSIC_SOURCE_SPOTIFY
                alpha = if (enabled) 1.0f else 0.7f
                visibility = if (getMusicSource() == MUSIC_SOURCE_SPOTIFY) View.VISIBLE else View.GONE
            }
            getNextButton()?.apply {
                isEnabled = enabled && getMusicSource() == MUSIC_SOURCE_SPOTIFY
                alpha = if (enabled) 1.0f else 0.7f
                visibility = if (getMusicSource() == MUSIC_SOURCE_SPOTIFY) View.VISIBLE else View.GONE
            }
        }
    }

    override fun update() {
        // Not needed for music widget as it updates via state observation
    }

    override fun show() {
        if (isVisible) {
            Log.d(TAG, "Widget already visible, skipping show()")
            return
        }

        binding?.let { binding ->
            Log.d(TAG, "Showing music widget")
            val rootView = binding.getRootView()
            rootView?.apply {
                // Remove from parent if already attached
                (parent as? ViewGroup)?.removeView(this)

                // Create layout params
                val params = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val margin = resources.getDimensionPixelSize(R.dimen.widget_margin)
                    val adSpace = 100 // Space for ad

                    // Apply constraints based on position
                    when (config.position) {
                        WidgetPosition.TOP_START -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.TOP_CENTER -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.TOP_END -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.CENTER_START -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.CENTER -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.CENTER_END -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.BOTTOM_START -> {
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.BOTTOM_CENTER -> {
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.BOTTOM_END -> {
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                    }
                }

                // Add to container with params
                (container as? ConstraintLayout)?.addView(this, params)

                // Configure view
                post {
                    visibility = View.VISIBLE
                    elevation = 5f
                    alpha = 1f
                    requestLayout()
                    invalidate()
                    Log.d(TAG, "Music widget view configured and visible at ${config.position}")
                }
            }
        }
        isVisible = true
    }

    override fun hide() {
        isVisible = false
        binding?.let { binding ->
            binding.getRootView()?.apply {
                (parent as? ViewGroup)?.removeView(this)
                visibility = View.GONE
                background = null
                alpha = 0f
            }
        }
    }

    override fun cleanup() {
        try {
            Log.d(TAG, "Starting music widget cleanup")
            stopProgressUpdates()

            // Cancel all coroutines
            (scope.coroutineContext[Job] as? CompletableJob)?.cancel()

            // Clear artwork and its state
            binding?.getTrackArtworkBackground()?.apply {
                animate().cancel()
                setImageBitmap(null)
                visibility = View.GONE
            }
            currentTrackUri = null

            // Store radio state before disconnecting if it's the current source
            if (getMusicSource() == MUSIC_SOURCE_RADIO) {
                val wasPlaying = radioManager.playbackState.value is RadioManager.PlaybackState.Playing
                radioPreferences.setWasPlaying(wasPlaying)
                // Store current station if playing
                if (wasPlaying) {
                    radioManager.currentStation.value?.let { station ->
                        radioPreferences.setLastStation(station)
                    }
                }
            }

            // Store local music state before disconnecting
            if (getMusicSource() == MUSIC_SOURCE_LOCAL) {
                val wasPlaying = localMusicManager.playbackState.value is LocalMusicManager.PlaybackState.Playing &&
                        (localMusicManager.playbackState.value as? LocalMusicManager.PlaybackState.Playing)?.isPlaying == true
                localMusicPreferences.setWasPlaying(wasPlaying)
            }

            // Disconnect the appropriate music source
            when (getMusicSource()) {
                MUSIC_SOURCE_SPOTIFY -> spotifyManager.disconnect()
                MUSIC_SOURCE_RADIO -> radioManager.disconnect()
                MUSIC_SOURCE_LOCAL -> localMusicManager.disconnect()
            }

            binding?.getRootView()?.let { view ->
                try {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.background = null
                    view.alpha = 0f
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing view from parent", e)
                }
            }

            binding?.cleanup()
            binding = null
            isVisible = false

            Log.d(TAG, "Music widget cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during music widget cleanup", e)
        }
    }

    override fun updateConfiguration(config: WidgetConfig) {
        if (config !is WidgetConfig.MusicConfig) return

        Log.d(TAG, "Updating music widget configuration: $config")

        val oldConfig = this.config
        this.config = config

        binding?.apply {
            // Handle visibility immediately
            if (config.enabled != oldConfig.enabled) {
                if (config.enabled) show() else hide()
            }

            getRootView()?.apply {
                // Handle artwork visibility based on both music source and showArtwork setting
                getTrackArtworkBackground()?.apply {
                    visibility = if (getMusicSource() == MUSIC_SOURCE_SPOTIFY &&
                        currentTrackUri != null &&
                        config.showArtwork) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }

                // Handle controls visibility
                findViewById<ViewGroup>(R.id.controls_container)?.apply {
                    visibility = if (config.showControls) View.VISIBLE else View.GONE
                }

                // Handle progress visibility
                findViewById<View>(R.id.track_progress)?.apply {
                    visibility = if (config.showProgress && getMusicSource() == MUSIC_SOURCE_SPOTIFY)
                        View.VISIBLE else View.GONE
                }

                // Update position if changed
                if (oldConfig.position != config.position) {
                    updatePosition(config.position)
                }

                // Force layout update
                requestLayout()
                invalidate()
            }
        }
    }

    override fun updatePosition(position: WidgetPosition) {
        binding?.getRootView()?.let { view ->
            val params = view.layoutParams as ConstraintLayout.LayoutParams

            // Clear existing constraints
            params.apply {
                topToTop = ConstraintLayout.LayoutParams.UNSET
                bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
            }

            // Get standard margin
            val marginValue = view.resources.getDimensionPixelSize(R.dimen.widget_margin)
            val adSpace = 100 // Extra space for ad at bottom

            // Apply new constraints based on position
            when (position) {
                WidgetPosition.TOP_START -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(marginValue, marginValue, 0, 0)
                    }
                }
                WidgetPosition.TOP_CENTER -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(marginValue, marginValue, marginValue, 0)
                    }
                }
                WidgetPosition.TOP_END -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(0, marginValue, marginValue, 0)
                    }
                }
                WidgetPosition.CENTER_START -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(marginValue, 0, 0, 0)
                    }
                }
                WidgetPosition.CENTER -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(marginValue, 0, marginValue, 0)
                    }
                }
                WidgetPosition.CENTER_END -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(0, 0, marginValue, 0)
                    }
                }
                WidgetPosition.BOTTOM_START -> {
                    params.apply {
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(marginValue, 0, 0, marginValue + adSpace)
                    }
                }
                WidgetPosition.BOTTOM_CENTER -> {
                    params.apply {
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(marginValue, 0, marginValue, marginValue + adSpace)
                    }
                }
                WidgetPosition.BOTTOM_END -> {
                    params.apply {
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(0, 0, marginValue, marginValue + adSpace)
                    }
                }
            }

            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT

            view.layoutParams = params
            view.requestLayout()

            Log.d(TAG, "Music widget position updated to: $position")
        }
    }

    private fun startProgressUpdates() {
        progressUpdateJob?.cancel() // Cancel any existing job
        progressUpdateJob = scope.launch {
            while (isActive) {
                binding?.getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                    val currentState = spotifyManager.playbackState.value
                    if (currentState is SpotifyManager.PlaybackState.Playing && currentState.isPlaying) {
                        // Only update the progress, not the whole state
                        val newProgress = (progress + 1000).coerceAtMost(max)
                        updateProgressBarOnly(newProgress)
                    }
                }
                delay(1000) // Update every second
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    private fun clearErrorState() {
        updateButtonState(true)
    }

    private fun updateErrorState(message: String) {
        binding?.apply {
            getTrackNameView()?.text = message
            getArtistNameView()?.text = ""
        }
        updateButtonState(false)
    }
}