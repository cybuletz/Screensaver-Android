package com.photostreamr.sharing

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Controller for managing the share button visibility and animations
 * Created by cybuletz on 2025-05-27
 */
class ShareButtonController(
    private val shareButton: FloatingActionButton
) {
    private var isVisible = false

    /**
     * Show the share button with animation
     */
    fun show() {
        if (isVisible) return

        isVisible = true

        shareButton.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
            translationY = 50f

            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay(150) // Slight delay after settings button
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    /**
     * Hide the share button with animation
     */
    fun hide() {
        if (!isVisible) return

        isVisible = false

        shareButton.animate()
            .alpha(0f)
            .scaleX(0.3f)
            .scaleY(0.3f)
            .translationY(50f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                shareButton.visibility = View.GONE
            }
            .start()
    }

    /**
     * Check if the share button is currently visible
     */
    fun isVisible(): Boolean = isVisible

    /**
     * Set click listener for the share button
     */
    fun setOnClickListener(listener: View.OnClickListener) {
        shareButton.setOnClickListener(listener)
    }

    /**
     * Hide immediately without animation (for quick cleanup)
     */
    fun hideImmediately() {
        isVisible = false
        shareButton.visibility = View.GONE
        shareButton.alpha = 0f
    }
}