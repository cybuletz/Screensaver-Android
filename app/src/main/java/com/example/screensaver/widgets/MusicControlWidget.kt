package com.example.screensaver.widgets

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.example.screensaver.R
import com.example.screensaver.music.PlaybackState
import com.example.screensaver.music.SpotifyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
            Log.d(TAG, "Initializing MusicControlWidget with config: $config")
            binding = MusicControlWidgetBinding(container).apply {
                inflate()
                setupControls()
            }
            updateConfiguration(config)

            observeConnectionState() // Add this
            observeErrors()         // Make sure this is called

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
        binding?.apply {
            getPlayPauseButton()?.setOnClickListener {
                if (spotifyManager.connectionState.value !is SpotifyManager.ConnectionState.Connected) {
                    Log.d(TAG, "Cannot control playback - Spotify not connected")
                    spotifyManager.connect()
                    return@setOnClickListener
                }

                val currentState = spotifyManager.playbackState.value
                when {
                    currentState is SpotifyManager.PlaybackState.Playing && currentState.isPlaying -> {
                        spotifyManager.pause()
                    }
                    else -> {
                        spotifyManager.resume()
                    }
                }
            }

            // Similar checks for previous/next buttons
            getPreviousButton()?.setOnClickListener {
                if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                    spotifyManager.previousTrack()
                }
            }

            getNextButton()?.setOnClickListener {
                if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                    spotifyManager.nextTrack()
                }
            }
        }

        // Start observing playback state
        scope.launch {
            spotifyManager.playbackState.collect { state ->
                updatePlaybackState(state)
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            spotifyManager.connectionState.collect { state ->
                when (state) {
                    is SpotifyManager.ConnectionState.Connected -> {
                        Log.d(TAG, "Spotify connected, enabling controls")
                        clearErrorState()
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

    private fun updatePlaybackState(state: SpotifyManager.PlaybackState) {
        binding?.apply {
            when (state) {
                is SpotifyManager.PlaybackState.Playing -> {
                    getTrackNameView()?.text = state.trackName
                    getArtistNameView()?.text = state.artistName
                    getPlayPauseButton()?.setImageResource(
                        if (state.isPlaying) R.drawable.ic_music_pause
                        else R.drawable.ic_music_play
                    )
                }
                SpotifyManager.PlaybackState.Idle -> {
                    getTrackNameView()?.text = ""
                    getArtistNameView()?.text = ""
                    getPlayPauseButton()?.setImageResource(R.drawable.ic_music_play)
                }
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
                if (parent == null) {
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

        val oldPosition = this.config.position
        this.config = config

        if (oldPosition != config.position) {
            updatePosition(config.position)
        }

        if (config.enabled) show() else hide()
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

    private fun observeErrors() {
        scope.launch {
            spotifyManager.errorState.collect { error ->
                when (error) {
                    is SpotifyManager.SpotifyError.AppNotInstalled -> {
                        updateErrorState("Spotify app not installed")
                    }
                    is SpotifyManager.SpotifyError.ConnectionFailed -> {
                        updateErrorState("Connection failed")
                        // Try to reconnect after delay
                        kotlinx.coroutines.delay(5000)
                        spotifyManager.connect()
                    }
                    is SpotifyManager.SpotifyError.PlaybackFailed -> {
                        updateErrorState("Playback error")
                        // Try to recover playback
                        kotlinx.coroutines.delay(2000)
                        spotifyManager.resume()
                    }
                    is SpotifyManager.SpotifyError.AuthenticationRequired -> {
                        updateErrorState("Authentication failed")
                    }
                    is SpotifyManager.SpotifyError.PremiumRequired -> {
                        updateErrorState("Premium account required")
                    }
                    null -> {
                        // Clear error state
                        clearErrorState()
                    }
                }
            }
        }
    }

    private fun updateErrorState(message: String) {
        binding?.apply {
            getTrackNameView()?.text = message
            getArtistNameView()?.text = ""
            getPlayPauseButton()?.isEnabled = false
            getPreviousButton()?.isEnabled = false
            getNextButton()?.isEnabled = false
        }
    }

    private fun clearErrorState() {
        binding?.apply {
            getPlayPauseButton()?.isEnabled = true
            getPreviousButton()?.isEnabled = true
            getNextButton()?.isEnabled = true
        }
    }
}