package com.photostreamr.widgets

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.photostreamr.R

class MusicControlWidgetBinding(
    private val container: ViewGroup
) {
    private var rootView: View? = null
    private var trackNameView: TextView? = null
    private var artistNameView: TextView? = null
    private var playPauseButton: ImageButton? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var controlsContainer: ViewGroup? = null

    private var trackArtworkBackground: ImageView? = null

    companion object {
        private const val TAG = "MusicControlWidgetBinding"
    }

    fun inflate(): View {
        try {
            Log.d(TAG, "Starting inflate() for MusicControlWidgetBinding")
            if (rootView == null) {
                Log.d(TAG, "Inflating new root view")
                rootView = LayoutInflater.from(container.context)
                    .inflate(R.layout.widget_music_controls, container, false)
                Log.d(TAG, "Layout inflated successfully")

                // Find views
                trackArtworkBackground = rootView?.findViewById<ImageView>(R.id.track_artwork_background)?.also {
                    Log.d(TAG, "Found track_artwork_background view")
                }

                trackNameView = rootView?.findViewById<TextView>(R.id.track_name)?.also {
                    Log.d(TAG, "Found track_name view")
                }

                artistNameView = rootView?.findViewById<TextView>(R.id.artist_name)?.also {
                    Log.d(TAG, "Found artist_name view")
                }

                playPauseButton = rootView?.findViewById<ImageButton>(R.id.play_pause_button)?.also {
                    Log.d(TAG, "Found play_pause_button")
                }

                previousButton = rootView?.findViewById<ImageButton>(R.id.previous_button)?.also {
                    Log.d(TAG, "Found previous_button")
                }

                nextButton = rootView?.findViewById<ImageButton>(R.id.next_button)?.also {
                    Log.d(TAG, "Found next_button")
                }

                controlsContainer = rootView?.findViewById<ViewGroup>(R.id.controls_container)?.also {
                    Log.d(TAG, "Found controls_container")
                }

                // Create params
                val params = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                )
                rootView?.layoutParams = params
                Log.d(TAG, "Layout parameters set for root view")

                Log.d(TAG, """
                    Views initialization complete:
                    - Track artwork: ${trackArtworkBackground != null}
                    - Track name: ${trackNameView != null}
                    - Artist name: ${artistNameView != null}
                    - Play/Pause: ${playPauseButton != null}
                    - Previous: ${previousButton != null}
                    - Next: ${nextButton != null}
                    - Controls container: ${controlsContainer != null}
                """.trimIndent())
            } else {
                Log.d(TAG, "Root view already exists, reusing")
            }
            return rootView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating music control widget", e)
            throw e
        }
    }

    fun getLoadingIndicator(): ProgressBar? {
        return getRootView()?.findViewById(R.id.loading_indicator)
    }

    fun getTrackArtworkBackground(): ImageView? = trackArtworkBackground

    fun getTrackNameView(): TextView? = trackNameView.also {
        Log.e(TAG, "getTrackNameView called, returning ${it != null}")
    }

    fun getArtistNameView(): TextView? = artistNameView.also {
        Log.e(TAG, "getArtistNameView called, returning ${it != null}")
    }

    fun getPlayPauseButton(): ImageButton? = playPauseButton.also {
        Log.e(TAG, "getPlayPauseButton called, returning ${it != null}")
    }

    fun getPreviousButton(): ImageButton? = previousButton.also {
        Log.e(TAG, "getPreviousButton called, returning ${it != null}")
    }

    fun getNextButton(): ImageButton? = nextButton.also {
        Log.e(TAG, "getNextButton called, returning ${it != null}")
    }

    fun getRootView(): View? = rootView.also {
        Log.e(TAG, "getRootView called, returning ${it != null}")
    }

    fun cleanup() {
        Log.e(TAG, "Cleaning up binding")
        rootView = null
        trackNameView = null
        artistNameView = null
        playPauseButton = null
        previousButton = null
        nextButton = null
        trackArtworkBackground = null
    }
}