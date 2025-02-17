package com.example.screensaver.transitions

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import com.example.screensaver.utils.AppPreferences
import android.animation.AnimatorSet

/**
 * Manages transitions between photos in the screensaver and lock screen.
 * Handles different types of animations and their configurations.
 */
class PhotoTransitionManager(
    private val container: ViewGroup,
    private val preferences: AppPreferences
) {

    private var currentView: ImageView? = null
    private var nextView: ImageView? = null
    private var currentAnimation: Animator? = null
    private var isTransitioning = false

    companion object {
        private const val DEFAULT_ANIMATION_DURATION = 500L // milliseconds
        private const val ZOOM_SCALE_FACTOR = 1.2f
        private const val SLIDE_OFFSET_FACTOR = 1.0f
    }

    /**
     * Initializes the transition manager with initial views
     */
    fun initialize(current: ImageView, next: ImageView) {
        currentView = current
        nextView = next
        resetViewStates()
    }

    /**
     * Transitions to the next photo with the configured animation
     */
    fun transitionToNext(onComplete: () -> Unit) {
        if (isTransitioning) return

        val currentImage = currentView ?: return
        val nextImage = nextView ?: return

        isTransitioning = true
        currentAnimation?.cancel()

        when (preferences.getTransitionAnimation()) {
            AppPreferences.TransitionAnimation.FADE -> fadeTransition(currentImage, nextImage, onComplete)
            AppPreferences.TransitionAnimation.SLIDE -> slideTransition(currentImage, nextImage, onComplete)
            AppPreferences.TransitionAnimation.ZOOM -> zoomTransition(currentImage, nextImage, onComplete)
        }
    }

    /**
     * Performs a fade transition between photos
     */
    private fun fadeTransition(
        currentImage: ImageView,
        nextImage: ImageView,
        onComplete: () -> Unit
    ) {
        nextImage.alpha = 0f
        nextImage.isVisible = true

        animatePhotoTransition(currentImage, nextImage, onComplete)
        currentAnimation = null
    }

    private fun animatePhotoTransition(currentView: ImageView, nextView: ImageView, onComplete: () -> Unit) {
        val duration = preferences.getTransitionDuration() * 1000L // Convert seconds to milliseconds

        val fadeOut = ObjectAnimator.ofFloat(currentView, View.ALPHA, 1f, 0f).apply {
            this.duration = duration
        }

        val fadeIn = ObjectAnimator.ofFloat(nextView, View.ALPHA, 0f, 1f).apply {
            this.duration = duration
        }

        AnimatorSet().apply {
            playTogether(fadeOut, fadeIn)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isTransitioning = false
                    currentView.alpha = 0f
                    nextView.alpha = 1f
                    // Swap references if needed
                    this@PhotoTransitionManager.currentView = nextView
                    this@PhotoTransitionManager.nextView = currentView
                    onComplete()  // Call the completion callback
                }
            })
            start()
        }
    }

    /**
     * Performs a slide transition between photos
     */
    private fun slideTransition(
        currentImage: ImageView,
        nextImage: ImageView,
        onComplete: () -> Unit
    ) {
        val screenWidth = container.width.toFloat()
        nextImage.translationX = screenWidth * SLIDE_OFFSET_FACTOR
        nextImage.isVisible = true

        val slideOutCurrent = ObjectAnimator.ofFloat(
            currentImage,
            View.TRANSLATION_X,
            0f,
            -screenWidth * SLIDE_OFFSET_FACTOR
        )
        val slideInNext = ObjectAnimator.ofFloat(
            nextImage,
            View.TRANSLATION_X,
            screenWidth * SLIDE_OFFSET_FACTOR,
            0f
        )

        slideOutCurrent.duration = DEFAULT_ANIMATION_DURATION
        slideInNext.duration = DEFAULT_ANIMATION_DURATION

        slideInNext.addListener(createAnimatorListener(onComplete))

        slideOutCurrent.start()
        slideInNext.start()
        currentAnimation = slideInNext
    }

    /**
     * Performs a zoom transition between photos
     */
    private fun zoomTransition(
        currentImage: ImageView,
        nextImage: ImageView,
        onComplete: () -> Unit
    ) {
        nextImage.scaleX = ZOOM_SCALE_FACTOR
        nextImage.scaleY = ZOOM_SCALE_FACTOR
        nextImage.alpha = 0f
        nextImage.isVisible = true

        val zoomOut = ObjectAnimator.ofFloat(currentImage, View.SCALE_X, 1f, 1/ZOOM_SCALE_FACTOR)
        val zoomOutY = ObjectAnimator.ofFloat(currentImage, View.SCALE_Y, 1f, 1/ZOOM_SCALE_FACTOR)
        val fadeOut = ObjectAnimator.ofFloat(currentImage, View.ALPHA, 1f, 0f)

        val zoomIn = ObjectAnimator.ofFloat(nextImage, View.SCALE_X, ZOOM_SCALE_FACTOR, 1f)
        val zoomInY = ObjectAnimator.ofFloat(nextImage, View.SCALE_Y, ZOOM_SCALE_FACTOR, 1f)
        val fadeIn = ObjectAnimator.ofFloat(nextImage, View.ALPHA, 0f, 1f)

        val duration = DEFAULT_ANIMATION_DURATION

        listOf(zoomOut, zoomOutY, fadeOut, zoomIn, zoomInY, fadeIn).forEach {
            it.duration = duration
        }

        fadeIn.addListener(createAnimatorListener(onComplete))

        listOf(zoomOut, zoomOutY, fadeOut, zoomIn, zoomInY, fadeIn).forEach { it.start() }
        currentAnimation = fadeIn
    }

    /**
     * Creates an animator listener for transition completion
     */
    private fun createAnimatorListener(onComplete: () -> Unit) = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            finishTransition()
            onComplete()
        }

        override fun onAnimationCancel(animation: Animator) {
            finishTransition()
        }
    }

    /**
     * Finishes the transition and resets states
     */
    private fun finishTransition() {
        isTransitioning = false
        currentAnimation = null
        swapViews()
        resetViewStates()
    }

    /**
     * Swaps current and next views
     */
    private fun swapViews() {
        val temp = currentView
        currentView = nextView
        nextView = temp
    }

    /**
     * Resets view states to default values
     */
    private fun resetViewStates() {
        currentView?.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            isVisible = true
        }

        nextView?.apply {
            alpha = 0f
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            isVisible = false
        }
    }

    /**
     * Cancels any ongoing transition
     */
    fun cancelTransition() {
        currentAnimation?.cancel()
        isTransitioning = false
        resetViewStates()
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        cancelTransition()
        currentView = null
        nextView = null
    }

    /**
     * Updates transition settings from preferences
     */
    fun updateFromPreferences() {
        // Animation type is read directly from preferences during transition
        resetViewStates()
    }
}