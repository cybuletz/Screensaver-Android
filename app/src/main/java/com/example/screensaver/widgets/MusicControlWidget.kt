package com.example.screensaver.widgets

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.example.screensaver.R
import com.example.screensaver.music.SpotifyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

class MusicControlWidget(
    private val container: ViewGroup,
    var config: WidgetConfig.MusicConfig,
    private val spotifyManager: SpotifyManager
) : ScreenWidget {
    private var binding: MusicControlWidgetBinding? = null
    private var isVisible = false
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "MusicControlWidget"
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
                playPauseButton?.setOnClickListener { view ->
                    Log.e(TAG, "Play/Pause button direct click")
                    when (spotifyManager.connectionState.value) {
                        !is SpotifyManager.ConnectionState.Connected -> {
                            Log.e(TAG, "Not connected - attempting to connect")
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

                previousButton?.setOnClickListener { view ->
                    Log.e(TAG, "Previous button direct click")
                    if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                        view.isEnabled = false
                        spotifyManager.previousTrack()
                        view.postDelayed({ view.isEnabled = true }, 500)
                    }
                }

                nextButton?.setOnClickListener { view ->
                    Log.e(TAG, "Next button direct click")
                    if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
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

            scope.launch {
                spotifyManager.connectionState.collect { state ->
                    Log.e(TAG, "Connection state update: $state")
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

            scope.launch {
                spotifyManager.playbackState.collect { state ->
                    Log.e(TAG, "Playback state update received: $state")
                    if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                        Log.e(TAG, "Updating widget with new playback state")
                        updatePlaybackState(state)
                    } else {
                        Log.e(TAG, "Ignoring playback state update - not connected")
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

    private fun setupControls() {
        Log.e(TAG, "setupControls() called with binding = ${binding != null}")
        binding?.apply {
            getPlayPauseButton()?.also { button ->
                Log.e(TAG, "Setting up play/pause button")
                button.setOnClickListener {
                    Log.e(TAG, "Play/Pause button clicked")
                    Log.e(TAG, "Current Spotify connection state: ${spotifyManager.connectionState.value}")
                    Log.e(TAG, "Current playback state: ${spotifyManager.playbackState.value}")

                    when (val connectionState = spotifyManager.connectionState.value) {
                        !is SpotifyManager.ConnectionState.Connected -> {
                            Log.e(TAG, "Not connected - attempting to connect")
                            spotifyManager.connect()
                        }
                        else -> {
                            Log.e(TAG, "Connected - handling playback state")
                            val playbackState = spotifyManager.playbackState.value
                            Log.e(TAG, "Current playback state details: $playbackState")

                            when (playbackState) {
                                is SpotifyManager.PlaybackState.Playing -> {
                                    if (playbackState.isPlaying) {
                                        Log.e(TAG, "Currently playing - calling pause()")
                                        spotifyManager.pause()
                                    } else {
                                        Log.e(TAG, "Currently paused - calling resume()")
                                        spotifyManager.resume()
                                    }
                                }
                                is SpotifyManager.PlaybackState.Idle -> {
                                    Log.e(TAG, "Currently idle - calling resume()")
                                    spotifyManager.resume()
                                }
                                else -> {
                                    Log.e(TAG, "Unknown playback state: $playbackState")
                                }
                            }
                        }
                    }
                }
            }

            getPreviousButton()?.also { button ->
                Log.e(TAG, "Setting up previous button")
                button.setOnClickListener {
                    Log.e(TAG, "Previous button clicked")
                    Log.e(TAG, "Current Spotify connection state: ${spotifyManager.connectionState.value}")
                    if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                        Log.e(TAG, "Connected - calling previousTrack()")
                        button.isEnabled = false
                        spotifyManager.previousTrack()
                        button.postDelayed({
                            button.isEnabled = true
                            Log.e(TAG, "Previous button re-enabled")
                        }, 500)
                    } else {
                        Log.e(TAG, "Not connected - cannot play previous track")
                    }
                }
            }

            getNextButton()?.also { button ->
                Log.e(TAG, "Setting up next button")
                button.setOnClickListener {
                    Log.e(TAG, "Next button clicked")
                    Log.e(TAG, "Current Spotify connection state: ${spotifyManager.connectionState.value}")
                    if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                        Log.e(TAG, "Connected - calling nextTrack()")
                        button.isEnabled = false
                        spotifyManager.nextTrack()
                        button.postDelayed({
                            button.isEnabled = true
                            Log.e(TAG, "Next button re-enabled")
                        }, 500)
                    } else {
                        Log.e(TAG, "Not connected - cannot play next track")
                    }
                }
            }
        }

        // Verify initial states
        Log.e(TAG, "Initial Spotify connection state: ${spotifyManager.connectionState.value}")
        Log.e(TAG, "Initial playback state: ${spotifyManager.playbackState.value}")
    }

    private fun updatePlaybackState(state: SpotifyManager.PlaybackState) {
        Log.e(TAG, "Updating playback state: $state")

        if (spotifyManager.connectionState.value !is SpotifyManager.ConnectionState.Connected) {
            Log.e(TAG, "Not updating state - Spotify not connected")
            return
        }

        binding?.apply {
            when (state) {
                is SpotifyManager.PlaybackState.Playing -> {
                    Log.e(TAG, "Setting up Playing state UI")
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
                    }
                    getNextButton()?.apply {
                        Log.e(TAG, "Configuring next button - isEnabled will be true")
                        isEnabled = true
                        isClickable = true
                        isFocusable = true
                    }

                    // Update progress bar
                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                        max = state.trackDuration.toInt()
                        progress = state.playbackPosition.toInt()
                        visibility = if (config.showProgress) View.VISIBLE else View.GONE
                        Log.e(TAG, "Progress bar updated - duration: ${state.trackDuration}, position: ${state.playbackPosition}")
                    }
                }
                SpotifyManager.PlaybackState.Idle -> {
                    Log.e(TAG, "Setting up Idle state UI")
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
                    }
                    getNextButton()?.apply {
                        isEnabled = false
                        isClickable = false
                    }

                    // Reset progress bar
                    getRootView()?.findViewById<ProgressBar>(R.id.track_progress)?.apply {
                        progress = 0
                        visibility = if (config.showProgress) View.VISIBLE else View.GONE
                        Log.e(TAG, "Progress bar reset to 0")
                    }
                }
            }
        } ?: Log.e(TAG, "Binding is null during updatePlaybackState!")
    }

    private fun updateButtonState(enabled: Boolean) {
        binding?.apply {
            getPlayPauseButton()?.apply {
                isEnabled = enabled
                alpha = if (enabled) 1.0f else 0.7f
            }
            getPreviousButton()?.apply {
                isEnabled = enabled
                alpha = if (enabled) 1.0f else 0.7f
            }
            getNextButton()?.apply {
                isEnabled = enabled
                alpha = if (enabled) 1.0f else 0.7f
            }
        }
    }

    override fun update() {
        // Not needed for music widget as it updates via state observation
    }

    override fun show() {
        isVisible = true
        binding?.let { binding ->
            Log.d(TAG, "Showing music widget")
            val rootView = binding.getRootView()
            rootView?.apply {
                // Only add if not already added
                if (parent == null) {
                    Log.d(TAG, "Adding music widget view to container")
                    container.addView(this)
                }

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
            // Handle show music widget (enabled state)
            if (config.enabled) show() else hide()

            // Handle controls visibility
            getRootView()?.findViewById<ViewGroup>(R.id.controls_container)?.apply {
                visibility = if (config.showControls) View.VISIBLE else View.GONE
            }

            // Handle progress visibility
            getRootView()?.findViewById<View>(R.id.track_progress)?.apply {
                visibility = if (config.showProgress) View.VISIBLE else View.GONE
            }

            // Update position if changed
            if (oldConfig.position != config.position) {
                updatePosition(config.position)
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