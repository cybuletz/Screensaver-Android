package com.example.screensaver.widgets

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.example.screensaver.R
import com.example.screensaver.music.SpotifyManager
import com.example.screensaver.music.RadioManager
import com.example.screensaver.music.RadioPreferences
import com.example.screensaver.music.SpotifyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.BitmapShader
import android.graphics.Shader
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob

class MusicControlWidget(
    private val container: ViewGroup,
    var config: WidgetConfig.MusicConfig,
    private val spotifyManager: SpotifyManager,
    private val spotifyPreferences: SpotifyPreferences,
    private val radioManager: RadioManager,
    private val radioPreferences: RadioPreferences
) : ScreenWidget {
    private var binding: MusicControlWidgetBinding? = null
    private var isVisible = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressUpdateJob: Job? = null

    private var lastRadioStation: RadioManager.RadioStation? = null
    private var wasRadioPlaying: Boolean = false

    private var currentTrackUri: String? = null

    companion object {
        private const val TAG = "MusicControlWidget"
        private const val MUSIC_SOURCE_SPOTIFY = "spotify"
        private const val MUSIC_SOURCE_RADIO = "radio"
    }

    override fun init() {
        try {
            Log.e(TAG, "Initializing MusicControlWidget with config: $config")
            binding = MusicControlWidgetBinding(container).apply {
                inflate()

                // Get direct references to the buttons
                val playPauseButton = getRootView()?.findViewById<ImageButton>(R.id.play_pause_button)
                val previousButton = getRootView()?.findViewById<ImageButton>(R.id.previous_button)
                val nextButton = getRootView()?.findViewById<ImageButton>(R.id.next_button)

                // Set up click listeners directly
                playPauseButton?.setOnClickListener { _ ->
                    Log.e(TAG, "Play/Pause button direct click")
                    when (getMusicSource()) {
                        MUSIC_SOURCE_SPOTIFY -> handleSpotifyPlayPause()
                        MUSIC_SOURCE_RADIO -> handleRadioPlayPause()
                    }
                }

                previousButton?.setOnClickListener { view ->
                    Log.e(TAG, "Previous button direct click")
                    if (getMusicSource() == MUSIC_SOURCE_SPOTIFY &&
                        spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                        view.isEnabled = false
                        spotifyManager.previousTrack()
                        view.postDelayed({ view.isEnabled = true }, 500)
                    }
                }

                nextButton?.setOnClickListener { view ->
                    Log.e(TAG, "Next button direct click")
                    if (getMusicSource() == MUSIC_SOURCE_SPOTIFY &&
                        spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                        view.isEnabled = false
                        spotifyManager.nextTrack()
                        view.postDelayed({ view.isEnabled = true }, 500)
                    }
                }

                // Enable all buttons
                playPauseButton?.isEnabled = true
                previousButton?.isEnabled = true
                nextButton?.isEnabled = true

                // Log button states
                Log.e(TAG, """
                Button states after direct setup:
                Play/Pause - enabled: ${playPauseButton?.isEnabled}, clickable: ${playPauseButton?.isClickable}
                Previous - enabled: ${previousButton?.isEnabled}, clickable: ${previousButton?.isClickable}
                Next - enabled: ${nextButton?.isEnabled}, clickable: ${nextButton?.isClickable}
                """.trimIndent())
            }

            updateConfiguration(config)

            // Spotify state observers
            scope.launch {
                spotifyManager.connectionState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_SPOTIFY) {
                        Log.e(TAG, "Spotify connection state update: $state")
                        when (state) {
                            is SpotifyManager.ConnectionState.Connected -> {
                                Log.e(TAG, "Spotify connected, enabling controls")
                                clearErrorState()
                                updatePlaybackState(spotifyManager.playbackState.value)
                            }
                            is SpotifyManager.ConnectionState.Disconnected -> {
                                Log.e(TAG, "Spotify disconnected, disabling controls")
                                updateErrorState("Spotify disconnected")
                            }
                            is SpotifyManager.ConnectionState.Error -> {
                                Log.e(TAG, "Spotify connection error: ${state.error}")
                                updateErrorState("Connection error")
                            }
                        }
                    }
                }
            }

            scope.launch {
                spotifyManager.playbackState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_SPOTIFY) {
                        Log.e(TAG, "Spotify playback state update received: $state")
                        if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                            Log.e(TAG, "Updating widget with new Spotify playback state")
                            updatePlaybackState(state)
                        } else {
                            Log.e(TAG, "Ignoring Spotify playback state update - not connected")
                        }
                    }
                }
            }

            // Radio state observers
            scope.launch {
                radioManager.connectionState.collect { state ->
                    handleRadioConnectionStateChange(state)
                }
            }

            scope.launch {
                radioManager.playbackState.collect { state ->
                    if (getMusicSource() == MUSIC_SOURCE_RADIO) {
                        Log.e(TAG, "Radio playback state update received: $state")
                        // Only update UI if we're connected or have cached state
                        if (radioManager.connectionState.value is RadioManager.ConnectionState.Connected
                            || lastRadioStation != null) {
                            updateRadioPlaybackState(state)
                        }
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

    private fun getMusicSource(): String {
        return PreferenceManager.getDefaultSharedPreferences(container.context)
            .getString("music_source", MUSIC_SOURCE_SPOTIFY) ?: MUSIC_SOURCE_SPOTIFY
    }

    private fun isMusicSourceEnabled(): Boolean {
        val source = getMusicSource()
        return when (source) {
            MUSIC_SOURCE_SPOTIFY -> spotifyPreferences.isEnabled()
            MUSIC_SOURCE_RADIO -> radioPreferences.isEnabled()
            else -> false
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
        Log.e(TAG, "Updating Spotify playback state: $state")

        if (spotifyManager.connectionState.value !is SpotifyManager.ConnectionState.Connected) {
            Log.e(TAG, "Not updating state - Spotify not connected")
            stopProgressUpdates()
            binding?.getTrackArtworkBackground()?.visibility = View.GONE
            return
        }

        binding?.apply {
            when (state) {
                is SpotifyManager.PlaybackState.Playing -> {
                    if (state.trackUri != currentTrackUri) {
                        currentTrackUri = state.trackUri
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

                    Log.e(TAG, "Setting up Spotify Playing state UI")
                    getTrackNameView()?.apply {
                        text = state.trackName
                        isSelected = true
                    }
                    getArtistNameView()?.apply {
                        text = state.artistName
                        isSelected = true
                    }
                    getPlayPauseButton()?.apply {
                        Log.e(TAG, "Configuring play/pause button - isEnabled will be true")
                        setImageResource(
                            if (state.isPlaying) R.drawable.ic_music_pause
                            else R.drawable.ic_music_play
                        )
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                    }
                    getPreviousButton()?.apply {
                        Log.e(TAG, "Configuring previous button - isEnabled will be true")
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                        visibility = View.VISIBLE
                    }
                    getNextButton()?.apply {
                        Log.e(TAG, "Configuring next button - isEnabled will be true")
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                        visibility = View.VISIBLE
                    }

                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                        max = state.trackDuration.toInt()
                        progress = state.playbackPosition.toInt()
                        visibility = if (config.showProgress) View.VISIBLE else View.GONE
                        Log.e(TAG, "Progress bar updated - duration: ${state.trackDuration}, position: ${state.playbackPosition}")
                    }

                    if (state.isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates()
                    }
                }
                SpotifyManager.PlaybackState.Idle -> {
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

                    Log.e(TAG, "Setting up Spotify Idle state UI")
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
                        Log.e(TAG, "Configuring play/pause button - isEnabled will be $shouldBeEnabled")
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
                        Log.e(TAG, "Progress bar reset to 0")
                    }
                }
            }
        } ?: Log.e(TAG, "Binding is null during updatePlaybackState!")
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

                    getTrackNameView()?.apply {
                        text = state.stationName
                        isSelected = true
                    }
                    getArtistNameView()?.apply {
                        text = state.genre ?: ""
                        isSelected = true
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

                // Add to container
                container.addView(this)

                // Configure view
                post {
                    visibility = View.VISIBLE
                    background = ContextCompat.getDrawable(context, R.drawable.widget_background)
                    alpha = 1f
                    bringToFront()
                    updatePosition(config.position)
                    requestLayout()
                    invalidate()
                    Log.d(TAG, "Music widget view configured and visible")
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

            when (getMusicSource()) {
                MUSIC_SOURCE_SPOTIFY -> spotifyManager.disconnect()
                MUSIC_SOURCE_RADIO -> radioManager.disconnect()
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
            val margin = view.resources.getDimensionPixelSize(R.dimen.widget_margin)

            // Apply new constraints based on position
            when (position) {
                WidgetPosition.TOP_START -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, margin, 0, 0)
                    }
                }
                // Add other positions similar to your ClockWidget implementation
                else -> {
                    // Default to BOTTOM_CENTER
                    params.apply {
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, 0, margin, margin)
                    }
                }
            }

            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT

            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun startProgressUpdates() {
        progressUpdateJob?.cancel() // Cancel any existing job
        progressUpdateJob = scope.launch {
            while (isActive) {
                binding?.getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                    val currentState = spotifyManager.playbackState.value
                    if (currentState is SpotifyManager.PlaybackState.Playing && currentState.isPlaying) {
                        progress = (progress + 1000).coerceAtMost(max) // Update every second
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