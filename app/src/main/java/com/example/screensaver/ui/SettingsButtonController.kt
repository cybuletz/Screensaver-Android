package com.example.screensaver.ui

import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.os.Handler
import android.os.Looper
import com.google.android.material.floatingactionbutton.FloatingActionButton

class SettingsButtonController(private val button: FloatingActionButton) {
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    companion object {
        private const val TAG = "SettingsButtonController"
        const val FADE_DURATION = 300L
        const val AUTO_HIDE_DELAY = 5000L
    }

    init {
        // Ensure initial state
        button.apply {
            alpha = 0f
            visibility = View.VISIBLE
            elevation = 6f
            translationZ = 6f
        }
    }

    fun show() {
        Log.d(TAG, "Show called")
        hideRunnable?.let { handler.removeCallbacks(it) }

        // Cancel any ongoing animations
        button.animate().cancel()

        // Make sure button is visible before animation
        button.visibility = View.VISIBLE

        button.animate()
            .alpha(1f)
            .setDuration(FADE_DURATION)
            .withStartAction {
                Log.d(TAG, "Show animation started, visibility: ${button.visibility}")
            }
            .withEndAction {
                Log.d(TAG, "Show animation completed, alpha: ${button.alpha}")
            }
            .start()

        scheduleHide()
    }

    private fun scheduleHide() {
        Log.d(TAG, "Scheduling hide")
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable { hide() }.also {
            handler.postDelayed(it, AUTO_HIDE_DELAY)
        }
    }

    fun hide() {
        Log.d(TAG, "Hide called")
        hideRunnable?.let { handler.removeCallbacks(it) }

        // Cancel any ongoing animations
        button.animate().cancel()

        button.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION)
            .withStartAction {
                Log.d(TAG, "Hide animation started, alpha: ${button.alpha}")
            }
            .withEndAction {
                button.visibility = View.GONE
                Log.d(TAG, "Hide animation completed, visibility: ${button.visibility}")
            }
            .start()
    }

    fun cleanup() {
        hideRunnable?.let { handler.removeCallbacks(it) }
    }
}