package com.example.screensaver.widgets

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.screensaver.R

class MusicControlWidgetBinding(
    private val container: ViewGroup
) {
    private var rootView: View? = null
    private var trackNameView: TextView? = null
    private var artistNameView: TextView? = null
    private var playPauseButton: ImageButton? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null

    companion object {
        private const val TAG = "MusicControlWidgetBinding"
    }

    fun inflate(): View {
        try {
            Log.d(TAG, "Starting inflate() for MusicControlWidgetBinding")
            if (rootView == null) {
                rootView = LayoutInflater.from(container.context)
                    .inflate(R.layout.widget_music_controls, container, false)
                Log.d(TAG, "Layout inflated")

                trackNameView = rootView?.findViewById(R.id.track_name)
                artistNameView = rootView?.findViewById(R.id.artist_name)
                playPauseButton = rootView?.findViewById(R.id.play_pause_button)
                previousButton = rootView?.findViewById(R.id.previous_button)
                nextButton = rootView?.findViewById(R.id.next_button)

                Log.d(TAG, "Views found - Track: ${trackNameView != null}, " +
                        "Artist: ${artistNameView != null}, " +
                        "PlayPause: ${playPauseButton != null}")

                // Add the view to container
                val params = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    // Default to BOTTOM_CENTER
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    setMargins(32, 32, 32, 32)
                }

                rootView?.layoutParams = params
                container.addView(rootView)
                Log.d(TAG, "Root view added to container with params")
            } else {
                Log.d(TAG, "Root view already exists")
            }
            return rootView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error in inflate()", e)
            throw e
        }
    }

    fun getTrackNameView(): TextView? = trackNameView
    fun getArtistNameView(): TextView? = artistNameView
    fun getPlayPauseButton(): ImageButton? = playPauseButton
    fun getPreviousButton(): ImageButton? = previousButton
    fun getNextButton(): ImageButton? = nextButton
    fun getRootView(): View? = rootView

    fun cleanup() {
        rootView = null
        trackNameView = null
        artistNameView = null
        playPauseButton = null
        previousButton = null
        nextButton = null
    }
}