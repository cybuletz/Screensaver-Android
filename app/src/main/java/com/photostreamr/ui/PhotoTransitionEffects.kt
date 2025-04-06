package com.photostreamr.ui

import android.animation.*
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.*
import android.widget.FrameLayout
import android.widget.ImageView
import java.lang.Math.sin
import kotlin.random.Random
import android.graphics.Path
import android.view.ViewOutlineProvider
import android.graphics.Outline


class PhotoTransitionEffects(
    private val context: android.content.Context
) {
    companion object {
        private const val TAG = "PhotoTransitionEffects"
    }

    data class TransitionViews(
        val primaryView: ImageView,
        val overlayView: ImageView,
        val container: View,
        val topLetterboxView: ImageView? = null,
        val bottomLetterboxView: ImageView? = null
    )

    interface TransitionCompletionCallback {
        fun onTransitionCompleted(resource: Drawable, nextIndex: Int)
    }
    /**
     * Calculates the content area rectangle, accounting for letterboxing
     * @return Rectangle coordinates for the actual image content area
     */
    private fun calculateContentArea(views: TransitionViews): RectF {
        val containerWidth = views.container.width.toFloat()
        val containerHeight = views.container.height.toFloat()

        var topY = 0f
        var bottomY = containerHeight

        // Adjust top and bottom if letterboxing is visible
        if (views.topLetterboxView?.visibility == View.VISIBLE) {
            topY = views.topLetterboxView.height.toFloat()
        }

        if (views.bottomLetterboxView?.visibility == View.VISIBLE) {
            bottomY = containerHeight - views.bottomLetterboxView.height.toFloat()
        }

        return RectF(0f, topY, containerWidth, bottomY)
    }


    fun performTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionEffect: String,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        when (transitionEffect) {
            "fade" -> performFadeTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "slide" -> performSlideTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "zoom" -> performZoomTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "flip" -> performFlipTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "rotate" -> performRotateTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "depth" -> performDepthTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "cube" -> performCubeTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "blur" -> performBlurTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "mosaic" -> performMosaicTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "dissolve" -> performDissolveTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "shatter" -> performShatterTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "droplet" -> performDropletTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "spiral" -> performSpiralTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "pageCurl" -> performPageCurlTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "split" -> performSplitTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "wave" -> performWaveTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "blinds" -> performBlindsTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )


            "bounce" -> performBounceTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "glitch" -> performGlitchTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "doorway" -> performDoorwayTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "origami" -> performOrigamiTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "ripple" -> performRippleTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "kaleidoscope" -> performKaleidoscopeTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "wipe" -> performWipeTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "checkerboard" -> performCheckerboardTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "elastic" -> performElasticTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "crystallize" -> performCrystallizeTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "clockwise" -> performClockwiseTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "diagonal" -> performDiagonalTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "stretch" -> performStretchTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "circle" -> performCircleTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "crossFade" -> performCrossFadeTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "crossFadeGrayscale" -> performCrossFadeGrayscaleTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "cube3d" -> performCube3dTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "simpleFade" -> performSimpleFadeTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "flash" -> performFlashTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "illusion" -> performIllusionTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "radial" -> performRadialTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "slideExtended" -> performSlideExtendedTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "wind" -> performWindTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "star" -> performStarTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            "swap" -> performSwapTransition(
                views,
                resource,
                nextIndex,
                transitionDuration,
                callback
            )

            // Default fallback
            else -> performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performFadeTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        views.overlayView.apply {
            alpha = 0f
            setImageDrawable(resource)
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(transitionDuration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { callback.onTransitionCompleted(resource, nextIndex) }
                .start()
        }
    }

    private fun performSlideTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        views.overlayView.apply {
            alpha = 1f
            translationX = width.toFloat()
            setImageDrawable(resource)
            visibility = View.VISIBLE
            animate()
                .translationX(0f)
                .setDuration(transitionDuration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { callback.onTransitionCompleted(resource, nextIndex) }
                .start()
        }
    }

    private fun performZoomTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        views.overlayView.apply {
            alpha = 0f
            scaleX = 1.2f
            scaleY = 1.2f
            setImageDrawable(resource)
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(transitionDuration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { callback.onTransitionCompleted(resource, nextIndex) }
                .start()
        }
    }

    private fun performFlipTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Ensure both views are visible and have correct initial state
        views.primaryView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 0f
        }

        views.overlayView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 90f
            setImageDrawable(resource)
        }

        // Set camera distance to prevent clipping
        val distance = views.overlayView.width * 3f
        views.primaryView.cameraDistance = distance
        views.overlayView.cameraDistance = distance

        // Create AnimatorSet for synchronized animations
        val animatorSet = AnimatorSet()

        // Create overlay view animation (new image)
        val overlayFlip = ObjectAnimator.ofFloat(views.overlayView, View.ROTATION_Y, 90f, 0f)

        // Create primary view animation (old image)
        val primaryFlip = ObjectAnimator.ofFloat(views.primaryView, View.ROTATION_Y, 0f, -90f)

        // Configure animations
        animatorSet.apply {
            playTogether(overlayFlip, primaryFlip)
            duration = transitionDuration
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })
        }

        // Start the animation
        animatorSet.start()
    }

    private fun performRotateTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        views.overlayView.apply {
            alpha = 0f
            rotation = -180f
            scaleX = 0.5f
            scaleY = 0.5f
            setImageDrawable(resource)
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(transitionDuration)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { callback.onTransitionCompleted(resource, nextIndex) }
                .start()
        }
    }

    private fun performDepthTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set initial states
        views.primaryView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }

        views.overlayView.apply {
            visibility = View.VISIBLE
            setImageDrawable(resource)
            alpha = 0f
            scaleX = 1.5f
            scaleY = 1.5f
            translationZ = -1000f
        }

        // Create AnimatorSet for synchronized animations
        val animatorSet = AnimatorSet()

        // Animations for the new image (overlay)
        val overlayAlpha = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)
        val overlayScaleX = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 1.5f, 1f)
        val overlayScaleY = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 1.5f, 1f)
        val overlayZ = ObjectAnimator.ofFloat(views.overlayView, View.TRANSLATION_Z, -1000f, 0f)

        // Animations for the old image (primary)
        val primaryAlpha = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
        val primaryScaleX = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_X, 1f, 0.5f)
        val primaryScaleY = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_Y, 1f, 0.5f)
        val primaryZ = ObjectAnimator.ofFloat(views.primaryView, View.TRANSLATION_Z, 0f, -500f)

        // Configure animations
        animatorSet.apply {
            playTogether(
                overlayAlpha, overlayScaleX, overlayScaleY, overlayZ,
                primaryAlpha, primaryScaleX, primaryScaleY, primaryZ
            )
            duration = transitionDuration
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })
        }

        // Start the animation
        animatorSet.start()
    }

    private fun performCubeTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Ensure both views are visible and have correct initial state
        views.primaryView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 0f
            translationX = 0f
        }

        views.overlayView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 90f
            translationX = width.toFloat()
            setImageDrawable(resource)
        }

        // Create AnimatorSet for synchronized animations
        val animatorSet = AnimatorSet()

        // Create overlay view animations
        val overlayAnim = ObjectAnimator.ofFloat(views.overlayView, View.ROTATION_Y, 90f, 0f)
        val overlayTranslation = ObjectAnimator.ofFloat(
            views.overlayView, View.TRANSLATION_X,
            views.overlayView.width.toFloat(), 0f
        )

        // Create primary view animations
        val primaryAnim = ObjectAnimator.ofFloat(views.primaryView, View.ROTATION_Y, 0f, -90f)
        val primaryTranslation = ObjectAnimator.ofFloat(
            views.primaryView, View.TRANSLATION_X,
            0f, -views.primaryView.width.toFloat()
        )

        // Configure animations
        animatorSet.apply {
            playTogether(overlayAnim, overlayTranslation, primaryAnim, primaryTranslation)
            duration = transitionDuration
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })
        }

        // Start the animation
        animatorSet.start()
    }

    private fun performBlurTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Create a temporary bitmap from the primary view (current image)
        val originalBitmap = (views.primaryView.drawable as? BitmapDrawable)?.bitmap
        if (originalBitmap == null) {
            // Fall back to fade transition if we can't get the bitmap
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
            return
        }

        // Set the overlay view to the new image
        views.overlayView.setImageDrawable(resource)
        views.overlayView.alpha = 0f
        views.overlayView.visibility = View.VISIBLE

        // Create an AnimatorSet for the transition
        val animatorSet = AnimatorSet()

        // Create a ValueAnimator for the blur effect
        val blurAnimator = ValueAnimator.ofFloat(0f, 25f).apply {
            duration = transitionDuration
            addUpdateListener { animator ->
                val blurRadius = animator.animatedValue as Float
                try {
                    // Apply blur to the primary view as the animation progresses
                    val blurredBitmap = originalBitmap.let {
                        val rs = RenderScript.create(context)
                        val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
                        val alloc = Allocation.createFromBitmap(rs, it)
                        val blurredBitmap = Bitmap.createBitmap(it.width, it.height, it.config)
                        val outAlloc = Allocation.createFromBitmap(rs, blurredBitmap)

                        blurScript.setRadius(blurRadius)
                        blurScript.setInput(alloc)
                        blurScript.forEach(outAlloc)
                        outAlloc.copyTo(blurredBitmap)

                        rs.destroy()
                        blurredBitmap
                    }
                    views.primaryView.setImageBitmap(blurredBitmap)
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blur effect", e)
                }
            }
        }

        // Create the alpha animation for the new image
        val fadeInAnimator = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f).apply {
            duration = transitionDuration
        }

        // Play both animations together
        animatorSet.playTogether(blurAnimator, fadeInAnimator)
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        // Start the animations
        animatorSet.start()
    }

    private fun performMosaicTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set the overlay view to the new image
        views.overlayView.setImageDrawable(resource)
        views.overlayView.alpha = 0f
        views.overlayView.visibility = View.VISIBLE

        // Get the original bitmap from the primary view
        val originalBitmap = (views.primaryView.drawable as? BitmapDrawable)?.bitmap
        if (originalBitmap == null) {
            // Fall back to fade transition if we can't get the bitmap
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
            return
        }

        // Create a ValueAnimator for the pixelation effect
        val pixelateAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionDuration
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float

                try {
                    // Apply pixelation effect to the current image
                    val pixelSize = (50 * progress).toInt().coerceAtLeast(1)
                    val pixelatedBitmap = pixelate(originalBitmap, pixelSize)
                    views.primaryView.setImageBitmap(pixelatedBitmap)

                    // Fade in the new image as we progress
                    views.overlayView.alpha = progress
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying pixelate effect", e)
                }
            }
        }

        // Start the animation and handle completion
        pixelateAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })
        pixelateAnimator.start()
    }

    private fun pixelate(bitmap: Bitmap, pixelSize: Int): Bitmap {
        if (pixelSize <= 1) return bitmap

        val width = bitmap.width
        val height = bitmap.height

        // Create a scaled-down version
        val scaledBitmap = Bitmap.createScaledBitmap(
            bitmap,
            width / pixelSize,
            height / pixelSize,
            false
        )

        // Scale it back up to see the pixelation
        return Bitmap.createScaledBitmap(
            scaledBitmap,
            width,
            height,
            false
        )
    }

    private fun performDissolveTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set the overlay view to the new image
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        // Create a custom dissolve animation
        val dissolveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionDuration
            interpolator = LinearInterpolator()

            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float

                // Apply dissolve effect by randomly showing pixels
                try {
                    // Set the alpha of the new image based on progress
                    views.overlayView.alpha = progress

                    // Create a dissolve effect on the primary view
                    if (views.primaryView.drawable is BitmapDrawable) {
                        val noiseAlpha = (255 * (1 - progress)).toInt()
                        val paint = Paint().apply {
                            color = Color.argb(noiseAlpha, 255, 255, 255)
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        }

                        // Create a noise bitmap
                        val width = views.primaryView.width
                        val height = views.primaryView.height
                        val noise = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(noise)

                        // Draw random dissolve pattern
                        val random = Random.Default
                        for (x in 0 until width step 4) {
                            for (y in 0 until height step 4) {
                                if (random.nextFloat() < progress) {
                                    canvas.drawRect(
                                        x.toFloat(), y.toFloat(),
                                        (x + 4).toFloat(), (y + 4).toFloat(), paint
                                    )
                                }
                            }
                        }

                        // Apply the noise to the primary view
                        val layerPaint = Paint().apply {
                            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                        }

                        val resultBitmap =
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val resultCanvas = Canvas(resultBitmap)
                        resultCanvas.drawBitmap(
                            (views.primaryView.drawable as BitmapDrawable).bitmap,
                            0f,
                            0f,
                            null
                        )
                        resultCanvas.drawBitmap(noise, 0f, 0f, layerPaint)

                        views.primaryView.setImageBitmap(resultBitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in dissolve transition", e)
                    // If there's an error, fall back to simple fade
                    views.primaryView.alpha = 1f - progress
                    views.overlayView.alpha = progress
                }
            }
        }

        dissolveAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        dissolveAnimator.start()
    }

    private fun performShatterTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // First ensure the primary view displays the old image
        views.primaryView.visibility = View.VISIBLE

        // We'll create multiple small views to represent the shattered pieces
        val fragmentSize = 50 // Size of each fragment
        val container = views.container as ViewGroup
        val screenWidth = container.width
        val screenHeight = container.height

        try {
            // Get the current bitmap
            val originalBitmap = if (views.primaryView.drawable is BitmapDrawable) {
                (views.primaryView.drawable as BitmapDrawable).bitmap
            } else {
                // Fall back to fade transition if we can't get the bitmap
                performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
                return
            }

            // Hide the primary view as we'll show fragments instead
            views.primaryView.visibility = View.INVISIBLE

            // Prepare the overlay view with the new image
            views.overlayView.apply {
                setImageDrawable(resource)
                alpha = 0f
                visibility = View.VISIBLE
            }

            // Create a list to store all the fragment views
            val fragments = mutableListOf<ImageView>()

            // Create the fragments
            for (x in 0 until screenWidth step fragmentSize) {
                for (y in 0 until screenHeight step fragmentSize) {
                    val width = fragmentSize.coerceAtMost(screenWidth - x)
                    val height = fragmentSize.coerceAtMost(screenHeight - y)

                    // Skip very small fragments
                    if (width < 10 || height < 10) continue

                    // Create a bitmap for this fragment
                    val fragmentBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        x * originalBitmap.width / screenWidth,
                        y * originalBitmap.height / screenHeight,
                        width * originalBitmap.width / screenWidth,
                        height * originalBitmap.height / screenHeight
                    )

                    // Create an ImageView for this fragment
                    val fragmentView = ImageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(width, height).apply {
                            leftMargin = x
                            topMargin = y
                        }
                        setImageBitmap(fragmentBitmap)
                    }

                    // Add it to the container
                    container.addView(fragmentView)
                    fragments.add(fragmentView)
                }
            }

            // Animate all fragments flying away
            val random = Random.Default
            val animatorSet = AnimatorSet()
            val animators = fragments.map { fragment ->
                val endX = random.nextInt(-screenWidth, screenWidth * 2)
                val endY = random.nextInt(-screenHeight, screenHeight * 2)
                val rotation = random.nextInt(-720, 720).toFloat()

                // Create property animators for this fragment
                val translateX = ObjectAnimator.ofFloat(
                    fragment,
                    View.TRANSLATION_X,
                    0f,
                    (endX - fragment.left).toFloat()
                )
                val translateY = ObjectAnimator.ofFloat(
                    fragment,
                    View.TRANSLATION_Y,
                    0f,
                    (endY - fragment.top).toFloat()
                )
                val rotate = ObjectAnimator.ofFloat(fragment, View.ROTATION, 0f, rotation)
                val alpha = ObjectAnimator.ofFloat(fragment, View.ALPHA, 1f, 0f)

                AnimatorSet().apply {
                    playTogether(translateX, translateY, rotate, alpha)
                    duration = transitionDuration
                    interpolator = DecelerateInterpolator()
                }
            }

            // Fade in the new image
            val fadeInAnimator =
                ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f).apply {
                    duration = transitionDuration
                }

            // Play all animations together
            animatorSet.playTogether(animators + fadeInAnimator)
            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove all fragment views
                    fragments.forEach { container.removeView(it) }
                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })

            animatorSet.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in shatter transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performDropletTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            scaleX = 0f
            scaleY = 0f
            alpha = 1f
            visibility = View.VISIBLE
        }

        // Apply a circular reveal effect
        views.overlayView.post {
            try {
                // Create a path for the droplet shape
                val width = views.overlayView.width.toFloat()
                val height = views.overlayView.height.toFloat()
                val centerX = width / 2
                val centerY = height / 2

                // Scale animation with bounce effect
                val scaleAnimator = AnimatorSet().apply {
                    val scaleX =
                        ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 0f, 1.1f, 1f)
                    val scaleY =
                        ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 0f, 1.1f, 1f)

                    playTogether(scaleX, scaleY)
                    duration = transitionDuration
                    interpolator = BounceInterpolator()
                }

                // Add a ripple effect around the droplet (optional)
                val rippleView = View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(100, 255, 255, 255))
                    }
                    alpha = 0.5f
                    scaleX = 0f
                    scaleY = 0f
                    visibility = View.VISIBLE
                }

                // Add the ripple view to the container
                (views.container as ViewGroup).addView(rippleView)

                // Animate the ripple
                val rippleAnimator = AnimatorSet().apply {
                    val rippleScaleX = ObjectAnimator.ofFloat(rippleView, View.SCALE_X, 0f, 1.5f)
                    val rippleScaleY = ObjectAnimator.ofFloat(rippleView, View.SCALE_Y, 0f, 1.5f)
                    val rippleAlpha = ObjectAnimator.ofFloat(rippleView, View.ALPHA, 0.5f, 0f)

                    playTogether(rippleScaleX, rippleScaleY, rippleAlpha)
                    duration = transitionDuration
                    interpolator = DecelerateInterpolator()
                }

                // Play both animations together
                val animatorSet = AnimatorSet()
                animatorSet.playTogether(scaleAnimator, rippleAnimator)
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Remove the ripple view
                        (views.container as ViewGroup).removeView(rippleView)
                        // Complete the transition
                        callback.onTransitionCompleted(resource, nextIndex)
                    }
                })

                animatorSet.start()

            } catch (e: Exception) {
                Log.e(TAG, "Error in droplet transition", e)
                // Fall back to fade transition
                performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
            }
        }
    }

    private fun performSpiralTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            rotation = -720f // Start with 2 full rotations
            visibility = View.VISIBLE
        }

        // Create the spiral animation
        val animatorSet = AnimatorSet()

        // Animate the old image
        val oldRotation = ObjectAnimator.ofFloat(views.primaryView, View.ROTATION, 0f, 720f)
        val oldScaleX = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_X, 1f, 2f)
        val oldScaleY = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_Y, 1f, 2f)
        val oldAlpha = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)

        // Animate the new image
        val newRotation = ObjectAnimator.ofFloat(views.overlayView, View.ROTATION, -720f, 0f)
        val newScaleX = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 0.5f, 1f)
        val newScaleY = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 0.5f, 1f)
        val newAlpha = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)

        // Play the animations together
        animatorSet.playTogether(
            oldRotation, oldScaleX, oldScaleY, oldAlpha,
            newRotation, newScaleX, newScaleY, newAlpha
        )

        // Configure the animation
        animatorSet.duration = transitionDuration
        animatorSet.interpolator = DecelerateInterpolator()

        // Handle animation completion
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        // Start the animation
        animatorSet.start()
    }

    private fun performPageCurlTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // This is a complex effect that ideally uses OpenGL or custom rendering
        // For a simplified version, we'll use rotation and scaling to simulate a page curl

        // Set up the views
        views.primaryView.visibility = View.VISIBLE

        // Create a parent view for the 3D rotation effect
        val parentView = FrameLayout(context)
        parentView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Create a new image view for the page being turned
        val pageView = ImageView(context)
        pageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Set the current image to the page view
        if (views.primaryView.drawable != null) {
            pageView.setImageDrawable(views.primaryView.drawable.constantState?.newDrawable())
        }

        // Set the new image to the overlay view
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.VISIBLE
        }

        // Add the page view to the parent
        parentView.addView(pageView)

        // Add the parent to the container
        (views.container as ViewGroup).addView(parentView)

        // Set the camera distance to avoid clipping
        pageView.cameraDistance = 20000f

        // Define the pivot point (right edge of the screen)
        pageView.pivotX = pageView.width.toFloat()
        pageView.pivotY = pageView.height.toFloat() / 2

        // Create the page curl animation
        val rotateY = ObjectAnimator.ofFloat(pageView, View.ROTATION_Y, 0f, -90f)

        // Create a shadow effect that darkens the page as it turns
        val shadowPaint = Paint()
        pageView.setLayerType(View.LAYER_TYPE_HARDWARE, shadowPaint)

        val shadowAnimator = ValueAnimator.ofFloat(0f, 1f)
        shadowAnimator.addUpdateListener { animator ->
            val value = animator.animatedValue as Float
            shadowPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setScale(1f - value * 0.5f, 1f - value * 0.5f, 1f - value * 0.5f, 1f)
            })
            pageView.invalidate()
        }

        // Combine the animations
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(rotateY, shadowAnimator)
        animatorSet.duration = transitionDuration
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Remove the page curl view
                (views.container as ViewGroup).removeView(parentView)
                // Complete the transition
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performSplitTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.VISIBLE
        }

        // Create top and bottom halves of the current image
        val currentDrawable = views.primaryView.drawable
        if (currentDrawable == null) {
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
            return
        }

        // Create bitmap from the current drawable
        val currentBitmap = if (currentDrawable is BitmapDrawable) {
            currentDrawable.bitmap
        } else {
            val bitmap = Bitmap.createBitmap(
                views.primaryView.width,
                views.primaryView.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            currentDrawable.setBounds(0, 0, canvas.width, canvas.height)
            currentDrawable.draw(canvas)
            bitmap
        }

        // Create top half
        val topHalfView = ImageView(context)
        topHalfView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            views.primaryView.height / 2
        )

        // Create bottom half
        val bottomHalfView = ImageView(context)
        bottomHalfView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            views.primaryView.height / 2
        ).apply {
            topMargin = views.primaryView.height / 2
        }

        // Create bitmaps for top and bottom halves
        val topHalfBitmap = Bitmap.createBitmap(
            currentBitmap,
            0,
            0,
            currentBitmap.width,
            currentBitmap.height / 2
        )

        val bottomHalfBitmap = Bitmap.createBitmap(
            currentBitmap,
            0,
            currentBitmap.height / 2,
            currentBitmap.width,
            currentBitmap.height / 2
        )

        // Set the bitmaps to the views
        topHalfView.setImageBitmap(topHalfBitmap)
        bottomHalfView.setImageBitmap(bottomHalfBitmap)

        // Add the views to the container
        (views.container as ViewGroup).addView(topHalfView)
        (views.container as ViewGroup).addView(bottomHalfView)

        // Hide the primary view since we're showing the halves
        views.primaryView.visibility = View.INVISIBLE

        // Create the split animation
        val topAnimator = ObjectAnimator.ofFloat(
            topHalfView,
            View.TRANSLATION_Y,
            0f,
            -views.container.height.toFloat()
        )

        val bottomAnimator = ObjectAnimator.ofFloat(
            bottomHalfView,
            View.TRANSLATION_Y,
            0f,
            views.container.height.toFloat()
        )

        // Combine the animations
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(topAnimator, bottomAnimator)
        animatorSet.duration = transitionDuration
        animatorSet.interpolator = AccelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Remove the half views
                (views.container as ViewGroup).removeView(topHalfView)
                (views.container as ViewGroup).removeView(bottomHalfView)
                // Complete the transition
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performBlindsTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val blindsMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val blindsMaskCanvas = Canvas(blindsMaskBitmap)
        val blindsPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold just the blinds-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Blinds configuration
        val blindsCount = 12 // Number of horizontal blinds

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the blinds mask canvas
            blindsMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial blinds position - just starting to open
            val initialProgress = 0.01f

            // Draw initial blinds shape (tiny slivers)
            blindsPaint.color = Color.WHITE
            blindsPaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()
            val blindHeight = height / blindsCount

            // Draw tiny initial blinds
            for (i in 0 until blindsCount) {
                val top = i * blindHeight
                val blindOpenAmount = initialProgress * width

                blindsMaskCanvas.drawRect(0f, top, blindOpenAmount, top + blindHeight, blindsPaint)
            }

            // Prepare initial image
            if (resource is BitmapDrawable) {
                val resultBitmap = Bitmap.createBitmap(
                    views.container.width,
                    views.container.height,
                    Bitmap.Config.ARGB_8888
                )
                val resultCanvas = Canvas(resultBitmap)

                val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                val destRect = calculateProperDestRect(
                    resource.bitmap.width,
                    resource.bitmap.height,
                    resultBitmap.width,
                    resultBitmap.height
                )

                resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                val maskPaint = Paint()
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                resultCanvas.drawBitmap(blindsMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing blinds transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the blinds mask canvas
                blindsMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the blinds shape
                blindsPaint.color = Color.WHITE
                blindsPaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()
                val blindHeight = height / blindsCount

                // Draw alternating blinds that open from left and right
                for (i in 0 until blindsCount) {
                    val top = i * blindHeight

                    // Stagger the blinds slightly
                    val staggerFactor = 0.1f * (i % 3) // Small delay between groups of blinds
                    val adjustedProgress = Math.max(0f, progressCurved - staggerFactor)

                    if (i % 2 == 0) {
                        // Open from left to right
                        val left = 0f
                        val right = width * Math.min(1f, adjustedProgress * 1.2f) // Slightly faster

                        blindsMaskCanvas.drawRect(left, top, right, top + blindHeight, blindsPaint)
                    } else {
                        // Open from right to left
                        val right = width
                        val left = width - (width * Math.min(1f, adjustedProgress * 1.2f))

                        blindsMaskCanvas.drawRect(left, top, right, top + blindHeight, blindsPaint)
                    }
                }

                // Create a copy of the new image, masked by the blinds shape
                if (resource is BitmapDrawable) {
                    val resultBitmap = Bitmap.createBitmap(
                        views.container.width,
                        views.container.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val resultCanvas = Canvas(resultBitmap)

                    val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                    val destRect = calculateProperDestRect(
                        resource.bitmap.width,
                        resource.bitmap.height,
                        resultBitmap.width,
                        resultBitmap.height
                    )

                    resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                    val maskPaint = Paint()
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    resultCanvas.drawBitmap(blindsMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in blinds transition", e)
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    // Remove the temporary view
                    (views.container as ViewGroup).removeView(revealImageView)

                    // Make the new image fully visible
                    views.overlayView.visibility = View.VISIBLE
                    views.overlayView.alpha = 1f

                    // Clean up resources
                    blindsMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up blinds transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    blindsMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled blinds transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun performCheckerboardTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.INVISIBLE // We'll use a checkerboard pattern instead
        }

        // Dimensions of the checkerboard
        val numRows = 8
        val numCols = 8
        val tileViews = mutableListOf<View>()

        // Container dimensions
        val containerWidth = views.container.width
        val containerHeight = views.container.height

        // Tile dimensions
        val tileWidth = containerWidth / numCols
        val tileHeight = containerHeight / numRows

        // Create a bitmap from the new drawable
        val newBitmap = if (resource is BitmapDrawable) {
            resource.bitmap
        } else {
            val bitmap = Bitmap.createBitmap(
                containerWidth,
                containerHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            resource.setBounds(0, 0, canvas.width, canvas.height)
            resource.draw(canvas)
            bitmap
        }

        // Create the checkerboard tiles
        for (row in 0 until numRows) {
            for (col in 0 until numCols) {
                // Create a tile image view
                val tileView = ImageView(context)
                tileView.layoutParams = FrameLayout.LayoutParams(
                    tileWidth,
                    tileHeight
                ).apply {
                    leftMargin = col * tileWidth
                    topMargin = row * tileHeight
                }

                // Cut out the portion of the bitmap for this tile
                val tileBitmap = Bitmap.createBitmap(
                    newBitmap,
                    col * newBitmap.width / numCols,
                    row * newBitmap.height / numRows,
                    newBitmap.width / numCols,
                    newBitmap.height / numRows
                )

                tileView.setImageBitmap(tileBitmap)
                tileView.scaleX = 0f
                tileView.scaleY = 0f

                (views.container as ViewGroup).addView(tileView)
                tileViews.add(tileView)
            }
        }

        // Create the animation
        val animatorSet = AnimatorSet()
        val animators = mutableListOf<Animator>()

        // Create animation for each tile with a pattern-based delay
        val maxDelay = transitionDuration / 2 // Maximum delay as a fraction of total duration

        for (row in 0 until numRows) {
            for (col in 0 until numCols) {
                val index = row * numCols + col
                val tileView = tileViews[index]

                // Checkerboard pattern: calculate delay based on position
                val isEven = (row + col) % 2 == 0
                val distanceFromCenter = Math.sqrt(
                    Math.pow((row - numRows / 2.0), 2.0) +
                            Math.pow((col - numCols / 2.0), 2.0)
                ) / Math.sqrt(
                    Math.pow(numRows / 2.0, 2.0) +
                            Math.pow(numCols / 2.0, 2.0)
                )

                val delay = if (isEven) {
                    (distanceFromCenter * maxDelay).toLong()
                } else {
                    (maxDelay - distanceFromCenter * maxDelay).toLong()
                }

                // Scale animation
                val scaleX = ObjectAnimator.ofFloat(tileView, View.SCALE_X, 0f, 1f)
                val scaleY = ObjectAnimator.ofFloat(tileView, View.SCALE_Y, 0f, 1f)

                val tileAnimator = AnimatorSet()
                tileAnimator.playTogether(scaleX, scaleY)
                tileAnimator.duration = transitionDuration / 2
                tileAnimator.startDelay = delay

                animators.add(tileAnimator)
            }
        }

        // Play the animations together
        animatorSet.playTogether(animators)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Remove all tile views
                tileViews.forEach { (views.container as ViewGroup).removeView(it) }
                // Complete the transition
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performKaleidoscopeTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        // Create triangular segments for the kaleidoscope effect
        val numSegments = 12
        val segmentViews = mutableListOf<ImageView>()
        val container = views.container as ViewGroup

        // Center point of the container
        val centerX = container.width / 2f
        val centerY = container.height / 2f
        val radius = Math.hypot(centerX.toDouble(), centerY.toDouble()).toFloat()

        try {
            // Get the bitmap from the new drawable
            val newBitmap = if (resource is BitmapDrawable) {
                resource.bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    container.width,
                    container.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                resource.setBounds(0, 0, canvas.width, canvas.height)
                resource.draw(canvas)
                bitmap
            }

            // Create triangle segments
            for (i in 0 until numSegments) {
                // Calculate the segment angle
                val startAngle = i * (360f / numSegments)
                val endAngle = (i + 1) * (360f / numSegments)

                // Create a path for the triangle segment
                val path = Path()
                path.moveTo(centerX, centerY)
                path.lineTo(
                    centerX + radius * Math.cos(Math.toRadians(startAngle.toDouble())).toFloat(),
                    centerY + radius * Math.sin(Math.toRadians(startAngle.toDouble())).toFloat()
                )
                path.lineTo(
                    centerX + radius * Math.cos(Math.toRadians(endAngle.toDouble())).toFloat(),
                    centerY + radius * Math.sin(Math.toRadians(endAngle.toDouble())).toFloat()
                )
                path.close()

                // Create a bitmap for this segment
                val segmentBitmap = Bitmap.createBitmap(
                    container.width,
                    container.height,
                    Bitmap.Config.ARGB_8888
                )
                val segmentCanvas = Canvas(segmentBitmap)

                // Create a paint with a shader for the new image
                val paint = Paint().apply {
                    isAntiAlias = true
                    shader = BitmapShader(
                        newBitmap,
                        Shader.TileMode.CLAMP,
                        Shader.TileMode.CLAMP
                    )
                }

                // Draw the segment
                segmentCanvas.drawPath(path, paint)

                // Create an ImageView for the segment
                val segmentView = ImageView(context)
                segmentView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                segmentView.setImageBitmap(segmentBitmap)

                // Set initial properties
                segmentView.pivotX = centerX
                segmentView.pivotY = centerY
                segmentView.alpha = 0f
                segmentView.rotation = -90f

                container.addView(segmentView)
                segmentViews.add(segmentView)
            }

            // Create the animation
            val animatorSet = AnimatorSet()
            val animators = mutableListOf<Animator>()

            // Create animations for each segment
            for (i in 0 until numSegments) {
                val segmentView = segmentViews[i]

                // Unique rotation for each segment
                val startRotation = -90f
                val endRotation = 270f

                // Create animators
                val rotate =
                    ObjectAnimator.ofFloat(segmentView, View.ROTATION, startRotation, endRotation)
                val alphaIn = ObjectAnimator.ofFloat(segmentView, View.ALPHA, 0f, 1f)
                val alphaOut = ObjectAnimator.ofFloat(segmentView, View.ALPHA, 1f, 0f)

                // Set up a sequential animation for each segment
                val segmentAnimator = AnimatorSet()
                segmentAnimator.playSequentially(
                    alphaIn,
                    rotate,
                    alphaOut
                )

                // Delay based on segment position
                segmentAnimator.startDelay = (i * (transitionDuration / numSegments) / 4)
                segmentAnimator.duration = transitionDuration - segmentAnimator.startDelay

                animators.add(segmentAnimator)
            }

            // Fade in the overlay view at the end
            val fadeInOverlay =
                ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f).apply {
                    duration = transitionDuration / 3
                    startDelay = transitionDuration * 2 / 3
                }

            // Play the animations together
            animatorSet.playTogether(animators + fadeInOverlay)

            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove all segment views
                    segmentViews.forEach { container.removeView(it) }
                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })

            animatorSet.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in kaleidoscope transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performBounceTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            // Start the overlay view off-screen at the bottom
            translationY = height.toFloat()
            visibility = View.VISIBLE
        }

        // Create the animation
        val animatorSet = AnimatorSet()

        // Bounce in the new image
        val bounceIn = ObjectAnimator.ofFloat(
            views.overlayView,
            View.TRANSLATION_Y,
            views.overlayView.height.toFloat(),
            0f
        ).apply {
            interpolator = BounceInterpolator()
            duration = transitionDuration
        }

        // Fade out the old image
        val fadeOut = ObjectAnimator.ofFloat(
            views.primaryView,
            View.ALPHA,
            1f,
            0f
        ).apply {
            duration = transitionDuration / 2
            startDelay = transitionDuration / 2
        }

        // Combine the animations
        animatorSet.playTogether(bounceIn, fadeOut)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performElasticTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            // Start with the overlay stretched
            scaleX = 3f
            scaleY = 0.3f
            visibility = View.VISIBLE
        }

        // Create a custom elastic interpolator for more pronounced effect
        val elasticInterpolator = object : TimeInterpolator {
            override fun getInterpolation(input: Float): Float {
                // Elastic equation: https://easings.net/#easeOutElastic
                return if (input == 0f) {
                    0f
                } else if (input == 1f) {
                    1f
                } else {
                    val p = 0.3f
                    val s = p / 4f
                    val power = Math.pow(2.0, (-10 * input).toDouble()).toFloat()
                    power * sin((input - s) * (2 * Math.PI) / p).toFloat() + 1
                }
            }
        }

        // Create the animation
        val animatorSet = AnimatorSet()

        // First move the primary view out
        val moveOut = ObjectAnimator.ofFloat(
            views.primaryView,
            View.TRANSLATION_X,
            0f,
            -views.primaryView.width.toFloat()
        ).apply {
            duration = transitionDuration / 2
            interpolator = AccelerateInterpolator()
        }

        // Elastic animation for the overlay view
        val scaleXAnimator = ObjectAnimator.ofFloat(
            views.overlayView,
            View.SCALE_X,
            3f,
            1f
        )
        val scaleYAnimator = ObjectAnimator.ofFloat(
            views.overlayView,
            View.SCALE_Y,
            0.3f,
            1f
        )

        // Group the elastic animations
        val elasticAnimator = AnimatorSet().apply {
            playTogether(scaleXAnimator, scaleYAnimator)
            interpolator = elasticInterpolator
            duration = transitionDuration
        }

        // Play the animations sequentially
        animatorSet.playSequentially(moveOut, elasticAnimator)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performOrigamiTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.INVISIBLE // We'll use custom folding views
        }

        val container = views.container as ViewGroup
        val containerWidth = container.width
        val containerHeight = container.height

        try {
            // Get the bitmap from the current image
            val currentBitmap = if (views.primaryView.drawable is BitmapDrawable) {
                (views.primaryView.drawable as BitmapDrawable).bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    containerWidth,
                    containerHeight,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                views.primaryView.drawable.setBounds(0, 0, canvas.width, canvas.height)
                views.primaryView.drawable.draw(canvas)
                bitmap
            }

            // Number of folds
            val numFolds = 4
            val foldWidth = containerWidth / numFolds

            // Create fold views
            val foldViews = mutableListOf<View>()
            for (i in 0 until numFolds) {
                // Create a section of the current image
                val foldBitmap = Bitmap.createBitmap(
                    currentBitmap,
                    i * currentBitmap.width / numFolds,
                    0,
                    currentBitmap.width / numFolds,
                    currentBitmap.height
                )

                // Create a view for this fold
                val foldView = ImageView(context)
                foldView.layoutParams = FrameLayout.LayoutParams(
                    foldWidth,
                    containerHeight
                ).apply {
                    leftMargin = i * foldWidth
                }
                foldView.setImageBitmap(foldBitmap)

                // Set initial rotation for 3D effect
                foldView.pivotX = if (i < numFolds / 2) 0f else foldWidth.toFloat()
                foldView.pivotY = containerHeight / 2f
                foldView.cameraDistance = 8000f

                container.addView(foldView)
                foldViews.add(foldView)
            }

            // Create the animation
            val animatorSet = AnimatorSet()
            val animators = mutableListOf<Animator>()

            // Create animations for each fold with a sequential delay
            for (i in 0 until numFolds) {
                val foldView = foldViews[i]
                val rotationAxis = if (i < numFolds / 2) View.ROTATION_Y else View.ROTATION_Y
                val rotationValue = if (i < numFolds / 2) 90f else -90f

                val rotateAnimator =
                    ObjectAnimator.ofFloat(foldView, rotationAxis, 0f, rotationValue)
                rotateAnimator.duration = transitionDuration / 2
                rotateAnimator.startDelay = (i * transitionDuration / (numFolds * 2))
                rotateAnimator.interpolator = AccelerateInterpolator()

                animators.add(rotateAnimator)
            }

            // Play the fold animations together
            animatorSet.playTogether(animators)

            // Create a shadow effect to enhance the folding illusion
            val shadowPaint = Paint()
            foldViews.forEach { it.setLayerType(View.LAYER_TYPE_HARDWARE, shadowPaint) }

            val shadowAnimator = ValueAnimator.ofFloat(0f, 1f)
            shadowAnimator.addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                shadowPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                    setScale(1f - value * 0.3f, 1f - value * 0.3f, 1f - value * 0.3f, 1f)
                })
                foldViews.forEach { it.invalidate() }
            }
            shadowAnimator.duration = transitionDuration / 2

            // Create the second part of the animation - revealing the new image
            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove all fold views
                    foldViews.forEach { container.removeView(it) }

                    // Show the new image and create unfolding animation
                    views.overlayView.visibility = View.VISIBLE

                    // Get the bitmap from the new image
                    val newBitmap = if (resource is BitmapDrawable) {
                        resource.bitmap
                    } else {
                        val bitmap = Bitmap.createBitmap(
                            containerWidth,
                            containerHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bitmap)
                        resource.setBounds(0, 0, canvas.width, canvas.height)
                        resource.draw(canvas)
                        bitmap
                    }

                    // Create new fold views for unfolding
                    val unfoldViews = mutableListOf<View>()
                    for (i in 0 until numFolds) {
                        // Create a section of the new image
                        val foldBitmap = Bitmap.createBitmap(
                            newBitmap,
                            i * newBitmap.width / numFolds,
                            0,
                            newBitmap.width / numFolds,
                            newBitmap.height
                        )

                        // Create a view for this fold
                        val foldView = ImageView(context)
                        foldView.layoutParams = FrameLayout.LayoutParams(
                            foldWidth,
                            containerHeight
                        ).apply {
                            leftMargin = i * foldWidth
                        }
                        foldView.setImageBitmap(foldBitmap)

                        // Set initial rotation
                        foldView.pivotX = if (i < numFolds / 2) foldWidth.toFloat() else 0f
                        foldView.pivotY = containerHeight / 2f
                        foldView.rotationY = if (i < numFolds / 2) -90f else 90f
                        foldView.cameraDistance = 8000f

                        container.addView(foldView)
                        unfoldViews.add(foldView)
                    }

                    // Hide the overlay view since we're using fold views
                    views.overlayView.visibility = View.INVISIBLE

                    // Create unfold animations
                    val unfoldAnimators = mutableListOf<Animator>()

                    for (i in 0 until numFolds) {
                        val foldView = unfoldViews[i]
                        val rotateAnimator = ObjectAnimator.ofFloat(
                            foldView, View.ROTATION_Y,
                            if (i < numFolds / 2) -90f else 90f, 0f
                        )
                        rotateAnimator.duration = transitionDuration / 2
                        rotateAnimator.startDelay =
                            ((numFolds - i - 1) * transitionDuration / (numFolds * 2))
                        rotateAnimator.interpolator = DecelerateInterpolator()

                        unfoldAnimators.add(rotateAnimator)
                    }

                    // Shadow effect for unfolding
                    val unfoldShadowPaint = Paint()
                    unfoldViews.forEach {
                        it.setLayerType(
                            View.LAYER_TYPE_HARDWARE,
                            unfoldShadowPaint
                        )
                    }

                    val unfoldShadowAnimator = ValueAnimator.ofFloat(1f, 0f)
                    unfoldShadowAnimator.addUpdateListener { animator ->
                        val value = animator.animatedValue as Float
                        unfoldShadowPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                            setScale(1f - value * 0.3f, 1f - value * 0.3f, 1f - value * 0.3f, 1f)
                        })
                        unfoldViews.forEach { it.invalidate() }
                    }
                    unfoldShadowAnimator.duration = transitionDuration / 2

                    // Combine unfold animations
                    val unfoldSet = AnimatorSet()
                    unfoldSet.playTogether(unfoldAnimators + unfoldShadowAnimator)

                    unfoldSet.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            // Remove unfold views
                            unfoldViews.forEach { container.removeView(it) }
                            // Complete the transition
                            callback.onTransitionCompleted(resource, nextIndex)
                        }
                    })

                    unfoldSet.start()
                }
            })

            // Play the first part of the animation
            animatorSet.playTogether(animators + shadowAnimator)
            animatorSet.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in origami transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performWaveTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val waveMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val waveMaskCanvas = Canvas(waveMaskBitmap)
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold just the wave-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Wave configuration
        val waveCount = 3 // Number of wave cycles across the screen
        val waveAmplitude = views.container.height * 0.1f // Height of wave peaks

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the wave mask canvas
            waveMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial wave position - just starting to appear from left
            val initialProgress = 0.01f

            // Draw initial wave shape
            wavePaint.color = Color.WHITE
            wavePaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()

            val path = Path()
            path.moveTo(0f, height) // Start at bottom-left
            path.lineTo(0f, height) // Bottom left corner

            // Draw just the very beginning of the wave on the left edge
            val initialPosition = width * initialProgress
            path.lineTo(initialPosition, height)
            path.lineTo(initialPosition, height - waveAmplitude / 2)
            path.lineTo(0f, height - waveAmplitude / 2)

            path.close()
            waveMaskCanvas.drawPath(path, wavePaint)

            // Prepare initial image
            if (resource is BitmapDrawable) {
                val resultBitmap = Bitmap.createBitmap(
                    views.container.width,
                    views.container.height,
                    Bitmap.Config.ARGB_8888
                )
                val resultCanvas = Canvas(resultBitmap)

                val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                val destRect = calculateProperDestRect(
                    resource.bitmap.width,
                    resource.bitmap.height,
                    resultBitmap.width,
                    resultBitmap.height
                )

                resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                val maskPaint = Paint()
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                resultCanvas.drawBitmap(waveMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing wave transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the wave mask canvas
                waveMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the wave shape on the mask canvas
                wavePaint.color = Color.WHITE
                wavePaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()

                val path = Path()
                path.moveTo(0f, height) // Start at bottom-left
                path.lineTo(0f, 0f) // Top left corner
                path.lineTo(width * progressCurved, 0f) // Top edge up to current position

                // Draw the wavy leading edge
                val segments = 100
                for (i in 0..segments) {
                    val x = width * progressCurved
                    val segmentHeight = height * i / segments

                    // Sine wave pattern that moves as progress increases
                    val waveOffset = Math.sin(i * waveCount * Math.PI / segments + progressCurved * 10).toFloat()
                    val adjustedX = x + waveOffset * waveAmplitude

                    path.lineTo(adjustedX, segmentHeight)
                }

                path.lineTo(0f, height) // Back to start
                path.close()
                waveMaskCanvas.drawPath(path, wavePaint)

                // Create a copy of the new image, masked by the wave shape
                if (resource is BitmapDrawable) {
                    val resultBitmap = Bitmap.createBitmap(
                        views.container.width,
                        views.container.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val resultCanvas = Canvas(resultBitmap)

                    val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                    val destRect = calculateProperDestRect(
                        resource.bitmap.width,
                        resource.bitmap.height,
                        resultBitmap.width,
                        resultBitmap.height
                    )

                    resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                    val maskPaint = Paint()
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    resultCanvas.drawBitmap(waveMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in wave transition", e)
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    // Remove the temporary view
                    (views.container as ViewGroup).removeView(revealImageView)

                    // Make the new image fully visible
                    views.overlayView.visibility = View.VISIBLE
                    views.overlayView.alpha = 1f

                    // Clean up resources
                    waveMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up wave transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    waveMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled wave transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun performGlitchTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        val random = Random.Default
        val handler = Handler(Looper.getMainLooper())
        val glitchCallbacks = mutableListOf<Runnable>()

        // Pre-create bitmap for noise effect - do this once instead of during animation
        val noiseView = ImageView(views.primaryView.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            alpha = 0.3f
            visibility = View.GONE
        }

        // Add the noise view once
        (views.container as? ViewGroup)?.addView(noiseView)

        // Reduce number of glitches
        val numGlitches = 8  // Reduced from 15

        for (i in 0 until numGlitches) {
            val glitchTime = (random.nextInt(1, 10) * transitionDuration / 10)

            val glitchRunnable = Runnable {
                try {
                    // Limit to simpler, less expensive effects
                    val glitchType = random.nextInt(3) // Removed the expensive split effect (case 2)

                    when (glitchType) {
                        0 -> { // Color channel shift - inexpensive
                            val colorMatrix = ColorMatrix()
                            colorMatrix.setScale(
                                random.nextFloat() * 2,
                                random.nextFloat() * 2,
                                random.nextFloat() * 2,
                                1f
                            )
                            val filter = ColorMatrixColorFilter(colorMatrix)
                            views.primaryView.colorFilter = filter
                            views.overlayView.colorFilter = filter
                        }

                        1 -> { // Position glitch - relatively inexpensive
                            val offsetX = random.nextInt(-20, 20)
                            val offsetY = random.nextInt(-20, 20)
                            views.primaryView.translationX = offsetX.toFloat()
                            views.primaryView.translationY = offsetY.toFloat()

                            if (views.overlayView.alpha > 0.3f) {
                                views.overlayView.translationX = -offsetX.toFloat()
                                views.overlayView.translationY = -offsetY.toFloat()
                            }
                        }

                        2 -> { // Simplified noise effect
                            // Instead of creating a new bitmap each time, just show/hide the noiseView
                            if (noiseView.drawable == null) {
                                // Create noise bitmap only once
                                val noise = createNoiseBitmap(views.primaryView.width, views.primaryView.height, random)
                                noiseView.setImageBitmap(noise)
                            }
                            noiseView.visibility = View.VISIBLE

                            // Hide after a short delay
                            handler.postDelayed({
                                noiseView.visibility = View.GONE
                            }, 100)
                        }
                    }

                    // Reset after a short time
                    handler.postDelayed({
                        views.primaryView.colorFilter = null
                        views.overlayView.colorFilter = null
                        views.primaryView.translationX = 0f
                        views.primaryView.translationY = 0f
                        views.overlayView.translationX = 0f
                        views.overlayView.translationY = 0f
                    }, 100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in glitch effect", e)
                }
            }

            glitchCallbacks.add(glitchRunnable)
            handler.postDelayed(glitchRunnable, glitchTime)
        }

        // Fade in animation
        val fadeAnimator = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)
        fadeAnimator.duration = transitionDuration
        fadeAnimator.interpolator = AccelerateDecelerateInterpolator()

        fadeAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Clean up
                glitchCallbacks.forEach { handler.removeCallbacks(it) }
                views.primaryView.colorFilter = null
                views.overlayView.colorFilter = null
                views.primaryView.translationX = 0f
                views.primaryView.translationY = 0f
                views.overlayView.translationX = 0f
                views.overlayView.translationY = 0f

                // Remove the noise view
                (views.container as? ViewGroup)?.removeView(noiseView)

                // Complete transition
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        fadeAnimator.start()
    }

    private fun createNoiseBitmap(width: Int, height: Int, random: Random): Bitmap {
        // Create a smaller bitmap (1/4 size) for better performance
        val scaleFactor = 4
        val scaledWidth = width / scaleFactor
        val scaledHeight = height / scaleFactor

        val noise = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(noise)
        val noisePaint = Paint().apply { style = Paint.Style.FILL }

        // Draw less frequent noise pixels
        for (x in 0 until scaledWidth) {
            for (y in 0 until scaledHeight) {
                if (random.nextFloat() < 0.7f) continue // Skip 70% of pixels

                noisePaint.color = Color.argb(
                    random.nextInt(50, 150),
                    random.nextInt(255),
                    random.nextInt(255),
                    random.nextInt(255)
                )
                canvas.drawRect(
                    x.toFloat(), y.toFloat(),
                    (x + 1).toFloat(), (y + 1).toFloat(), noisePaint
                )
            }
        }

        // Create the final bitmap scaled back up
        val finalBitmap = Bitmap.createScaledBitmap(noise, width, height, false)
        noise.recycle() // Recycle the smaller bitmap

        return finalBitmap
    }

    private data class Point(val x: Int, val y: Int)

    private fun performCrystallizeTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        val container = views.container as ViewGroup
        val containerWidth = container.width
        val containerHeight = container.height

        try {
            // Create a Delaunay triangulation effect
            // Define number of points for the triangulation
            val numPoints = 50
            val random = Random.Default

            // Generate random points for triangulation
            val points = mutableListOf<Point>()
            // Add corners and center point for stability
            points.add(Point(0, 0))
            points.add(Point(containerWidth, 0))
            points.add(Point(0, containerHeight))
            points.add(Point(containerWidth, containerHeight))
            points.add(Point(containerWidth / 2, containerHeight / 2))

            // Add random points
            for (i in 0 until numPoints) {
                points.add(
                    Point(
                        random.nextInt(containerWidth),
                        random.nextInt(containerHeight)
                    )
                )
            }

            // Create triangle segments for the effect
            val triangles = mutableListOf<Triple<Point, Point, Point>>()

            // Simple triangulation algorithm (this is a simplified version, not true Delaunay)
            // In a real app, you might want to use a library for proper Delaunay triangulation
            for (i in 0 until points.size - 2) {
                for (j in i + 1 until points.size - 1) {
                    for (k in j + 1 until points.size) {
                        // Create a triangle
                        triangles.add(Triple(points[i], points[j], points[k]))

                        // Limit the number of triangles to prevent performance issues
                        if (triangles.size > 100) break
                    }
                    if (triangles.size > 100) break
                }
                if (triangles.size > 100) break
            }

            // Get the bitmap from the new image
            val newBitmap = if (resource is BitmapDrawable) {
                resource.bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    containerWidth,
                    containerHeight,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                resource.setBounds(0, 0, canvas.width, canvas.height)
                resource.draw(canvas)
                bitmap
            }

            // Create a view for each triangle
            val triangleViews = mutableListOf<ImageView>()
            for (triangle in triangles) {
                // Create a path for the triangle
                val path = Path()
                path.moveTo(triangle.first.x.toFloat(), triangle.first.y.toFloat())
                path.lineTo(triangle.second.x.toFloat(), triangle.second.y.toFloat())
                path.lineTo(triangle.third.x.toFloat(), triangle.third.y.toFloat())
                path.close()

                // Calculate bounding box for this triangle
                val left = minOf(triangle.first.x, triangle.second.x, triangle.third.x)
                val top = minOf(triangle.first.y, triangle.second.y, triangle.third.y)
                val right = maxOf(triangle.first.x, triangle.second.x, triangle.third.x)
                val bottom = maxOf(triangle.first.y, triangle.second.y, triangle.third.y)

                val width = right - left
                val height = bottom - top

                if (width <= 0 || height <= 0) continue

                // Create a bitmap for this triangle
                val triangleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(triangleBitmap)

                // Adjust the path to the bitmap coordinates
                val adjustedPath = Path()
                adjustedPath.moveTo(
                    triangle.first.x - left.toFloat(),
                    triangle.first.y - top.toFloat()
                )
                adjustedPath.lineTo(
                    triangle.second.x - left.toFloat(),
                    triangle.second.y - top.toFloat()
                )
                adjustedPath.lineTo(
                    triangle.third.x - left.toFloat(),
                    triangle.third.y - top.toFloat()
                )
                adjustedPath.close()

                // Create a shader from the new image
                val shader = BitmapShader(
                    newBitmap,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP
                )

                // Create a matrix to position the shader correctly
                val matrix = Matrix()
                matrix.setTranslate(-left.toFloat(), -top.toFloat())
                shader.setLocalMatrix(matrix)

                // Draw the triangle with the shader
                val paint = Paint().apply {
                    isAntiAlias = true
                    this.shader = shader
                }

                canvas.drawPath(adjustedPath, paint)

                // Create a view for this triangle
                val triangleView = ImageView(context)
                triangleView.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                    leftMargin = left
                    topMargin = top
                }
                triangleView.setImageBitmap(triangleBitmap)

                // Initial state
                triangleView.alpha = 0f
                triangleView.scaleX = 0.5f
                triangleView.scaleY = 0.5f

                container.addView(triangleView)
                triangleViews.add(triangleView)
            }

            // Create the animation
            val animatorSet = AnimatorSet()
            val animators = mutableListOf<Animator>()

            // Animate each triangle
            for (i in triangleViews.indices) {
                val triangleView = triangleViews[i]

                // Calculate delay based on distance from center
                val centerX = containerWidth / 2
                val centerY = containerHeight / 2
                val viewCenterX = triangleView.left + triangleView.width / 2
                val viewCenterY = triangleView.top + triangleView.height / 2

                val distance = Math.hypot(
                    (viewCenterX - centerX).toDouble(),
                    (viewCenterY - centerY).toDouble()
                )

                val maxDistance = Math.hypot(
                    (containerWidth / 2).toDouble(),
                    (containerHeight / 2).toDouble()
                )

                val delayFactor = distance / maxDistance
                val delay = (delayFactor * transitionDuration / 2).toLong()

                // Create animators for this triangle
                val alphaAnimator = ObjectAnimator.ofFloat(triangleView, View.ALPHA, 0f, 1f)
                val scaleXAnimator = ObjectAnimator.ofFloat(triangleView, View.SCALE_X, 0.5f, 1f)
                val scaleYAnimator = ObjectAnimator.ofFloat(triangleView, View.SCALE_Y, 0.5f, 1f)

                val triangleAnimator = AnimatorSet()
                triangleAnimator.playTogether(alphaAnimator, scaleXAnimator, scaleYAnimator)
                triangleAnimator.duration = transitionDuration / 2
                triangleAnimator.startDelay = delay

                animators.add(triangleAnimator)
            }

            // Fade out the old image
            val fadeOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
            fadeOutAnimator.duration = transitionDuration / 2

            // Play all animations together
            animatorSet.playTogether(animators + fadeOutAnimator)

            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove all triangle views
                    triangleViews.forEach { container.removeView(it) }

                    // Show the complete new image
                    views.overlayView.alpha = 1f

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })

            animatorSet.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in crystallize transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performClockwiseTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback,
        isClockwise: Boolean = true
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE

        // Create a circular reveal effect
        views.overlayView.post {
            try {
                // Set the new image
                views.overlayView.setImageDrawable(resource)
                views.overlayView.visibility = View.INVISIBLE

                // Get dimensions
                val width = views.overlayView.width
                val height = views.overlayView.height

                // Create a bitmap for the mask
                val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val maskCanvas = Canvas(maskBitmap)

                // Create a custom image view for the masked image
                val maskedImageView = ImageView(context)
                maskedImageView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // Set the same image as the overlay
                if (resource is BitmapDrawable) {
                    maskedImageView.setImageBitmap(resource.bitmap)
                } else {
                    maskedImageView.setImageDrawable(resource.constantState?.newDrawable())
                }

                // Add the masked image view
                (views.container as ViewGroup).addView(maskedImageView)

                // Create an animator for the clock hand angle
                val startAngle = if (isClockwise) -90f else 270f
                val endAngle = if (isClockwise) 270f else -90f

                val angleAnimator = ValueAnimator.ofFloat(startAngle, endAngle)
                angleAnimator.duration = transitionDuration
                angleAnimator.interpolator = LinearInterpolator()

                angleAnimator.addUpdateListener { animator ->
                    val angle = animator.animatedValue as Float

                    // Clear the mask
                    maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                    // Create a path for the current angle
                    val path = Path()
                    val centerX = width / 2f
                    val centerY = height / 2f

                    // Start at the center
                    path.moveTo(centerX, centerY)

                    // Draw a line to the edge at the start angle
                    val startX =
                        centerX + Math.cos(Math.toRadians(startAngle.toDouble())).toFloat() * width
                    val startY =
                        centerY + Math.sin(Math.toRadians(startAngle.toDouble())).toFloat() * height
                    path.lineTo(startX, startY)

                    // Draw an arc from the start angle to the current angle
                    val radius = Math.hypot(width.toDouble(), height.toDouble()).toFloat()
                    val rect = RectF(
                        centerX - radius,
                        centerY - radius,
                        centerX + radius,
                        centerY + radius
                    )

                    val sweepAngle = if (isClockwise) {
                        (angle - startAngle) % 360
                    } else {
                        (startAngle - angle) % 360
                    }

                    path.arcTo(rect, startAngle, sweepAngle)

                    // Close the path back to the center
                    path.lineTo(centerX, centerY)
                    path.close()

                    // Draw the path to the mask
                    val paint = Paint().apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                    }
                    maskCanvas.drawPath(path, paint)

                    // Apply the mask
                    maskedImageView.setImageBitmap(maskBitmap)
                }

                // Fade out the old image
                val fadeOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
                fadeOutAnimator.duration = transitionDuration / 2
                fadeOutAnimator.startDelay = transitionDuration / 2

                // Create animator set
                val animatorSet = AnimatorSet()
                animatorSet.playTogether(angleAnimator, fadeOutAnimator)

                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Remove the masked image view
                        (views.container as ViewGroup).removeView(maskedImageView)

                        // Complete the transition
                        callback.onTransitionCompleted(resource, nextIndex)
                    }
                })

                animatorSet.start()

            } catch (e: Exception) {
                Log.e(TAG, "Error in clockwise transition", e)
                // Fall back to fade transition
                performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
            }
        }
    }

    private fun performDiagonalTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.INVISIBLE // We'll create a diagonal reveal effect
        }

        val container = views.container as ViewGroup
        val containerWidth = container.width
        val containerHeight = container.height

        try {
            // Create a mask that moves diagonally across the screen
            val maskPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

            // Create a bitmap for the mask
            val maskBitmap =
                Bitmap.createBitmap(containerWidth, containerHeight, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(maskBitmap)

            // Create a custom image view for the masked image
            val maskedImageView = ImageView(context)
            maskedImageView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Set the same image as the overlay
            if (resource is BitmapDrawable) {
                maskedImageView.setImageBitmap(resource.bitmap)
            } else {
                maskedImageView.setImageDrawable(resource.constantState?.newDrawable())
            }

            // Add the masked image view
            container.addView(maskedImageView)

            // Create the animation
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = transitionDuration
            animator.interpolator = LinearInterpolator()

            animator.addUpdateListener { animation ->
                val progress = animation.animatedValue as Float

                // Clear the mask
                maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Calculate the diagonal line position
                val diagonalLength =
                    Math.hypot(containerWidth.toDouble(), containerHeight.toDouble()).toFloat()
                val diagonalProgress = progress * (diagonalLength + containerWidth)

                // Create a path for the revealed area
                val path = Path()
                path.moveTo(0f, 0f)

                // Diagonal line from top-left to bottom-right
                path.lineTo(diagonalProgress, 0f)
                path.lineTo(0f, diagonalProgress)
                path.close()

                // Draw the path to the mask
                maskCanvas.drawPath(path, maskPaint)

                // Apply the mask to show only the revealed portion of the new image
                val xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                val layerPaint = Paint().apply {
                    this.xfermode = xfermode
                }

                // Create a new bitmap with the mask applied
                val resultBitmap =
                    Bitmap.createBitmap(containerWidth, containerHeight, Bitmap.Config.ARGB_8888)
                val resultCanvas = Canvas(resultBitmap)

                // Draw the new image
                if (resource is BitmapDrawable) {
                    resultCanvas.drawBitmap(resource.bitmap, 0f, 0f, null)
                } else {
                    resource.setBounds(0, 0, resultCanvas.width, resultCanvas.height)
                    resource.draw(resultCanvas)
                }

                // Apply the mask
                resultCanvas.drawBitmap(maskBitmap, 0f, 0f, layerPaint)

                // Update the masked image view
                maskedImageView.setImageBitmap(resultBitmap)
            }

            // Fade out the old image as we progress
            val fadeOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
            fadeOutAnimator.duration = transitionDuration

            // Play both animations together
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(animator, fadeOutAnimator)

            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove the masked image view
                    container.removeView(maskedImageView)

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })

            animatorSet.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in diagonal transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performStretchTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            scaleX = 0.01f  // Very narrow to start
            scaleY = 2f     // Taller than normal
            alpha = 1f
            visibility = View.VISIBLE
        }

        // Create the animation
        val animatorSet = AnimatorSet()

        // Stretch animation for the new image
        val stretchX = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 0.01f, 1f)
        val stretchY = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 2f, 1f)

        // Compress animation for the old image
        val compressX = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_X, 1f, 2f)
        val compressY = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_Y, 1f, 0.01f)
        val fadeOut = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)

        // Combine the animations
        animatorSet.playTogether(stretchX, stretchY, compressX, compressY, fadeOut)
        animatorSet.duration = transitionDuration
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performCircleTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.INVISIBLE // We'll use a custom masked view
        }

        val container = views.container as ViewGroup
        val containerWidth = container.width
        val containerHeight = container.height

        try {
            // Create a mask that expands as a circle
            val maskPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

            // Create a bitmap for the mask
            val maskBitmap =
                Bitmap.createBitmap(containerWidth, containerHeight, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(maskBitmap)

            // Create a custom image view for the masked image
            val maskedImageView = ImageView(context)
            maskedImageView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Set the same image as the overlay
            if (resource is BitmapDrawable) {
                maskedImageView.setImageBitmap(resource.bitmap)
            } else {
                maskedImageView.setImageDrawable(resource.constantState?.newDrawable())
            }

            // Add the masked image view
            container.addView(maskedImageView)

            // Create the animation
            val centerX = containerWidth / 2f
            val centerY = containerHeight / 2f
            val maxRadius = Math.hypot(
                Math.max(centerX, containerWidth - centerX).toDouble(),
                Math.max(centerY, containerHeight - centerY).toDouble()
            ).toFloat()

            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = transitionDuration
            animator.interpolator = DecelerateInterpolator()

            animator.addUpdateListener { animation ->
                val progress = animation.animatedValue as Float

                // Calculate the current radius
                val currentRadius = progress * maxRadius

                // Clear the mask
                maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Draw the circular mask
                maskCanvas.drawCircle(centerX, centerY, currentRadius, maskPaint)

                // Apply the mask to show only the revealed portion of the new image
                val xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                val layerPaint = Paint().apply {
                    this.xfermode = xfermode
                }

                // Create a new bitmap with the mask applied
                val resultBitmap =
                    Bitmap.createBitmap(containerWidth, containerHeight, Bitmap.Config.ARGB_8888)
                val resultCanvas = Canvas(resultBitmap)

                // Draw the new image
                if (resource is BitmapDrawable) {
                    resultCanvas.drawBitmap(resource.bitmap, 0f, 0f, null)
                } else {
                    resource.setBounds(0, 0, resultCanvas.width, resultCanvas.height)
                    resource.draw(resultCanvas)
                }

                // Apply the mask
                resultCanvas.drawBitmap(maskBitmap, 0f, 0f, layerPaint)

                // Update the masked image view
                maskedImageView.setImageBitmap(resultBitmap)
            }

            // Fade out the old image as we progress
            val fadeOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
            fadeOutAnimator.duration = transitionDuration / 2
            fadeOutAnimator.startDelay = transitionDuration / 2

            // Play both animations together
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(animator, fadeOutAnimator)

            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove the masked image view
                    container.removeView(maskedImageView)

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })

            animatorSet.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in circle transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performCrossFadeTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // This is a simple cross fade where both images are visible simultaneously
        // with the old one fading out while the new one fades in

        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        // Create the animations
        val fadeOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
        fadeOutAnimator.duration = transitionDuration
        fadeOutAnimator.interpolator = AccelerateInterpolator()

        val fadeInAnimator = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)
        fadeInAnimator.duration = transitionDuration
        fadeInAnimator.interpolator = DecelerateInterpolator()

        // Combine the animations
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(fadeOutAnimator, fadeInAnimator)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performCrossFadeGrayscaleTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE

            // Start with grayscale
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f) // 0 means fully grayscale
            })
        }

        // Create the animations
        val animatorSet = AnimatorSet()

        // First convert the primary view to grayscale
        val primaryGrayscaleAnimator = ValueAnimator.ofFloat(1f, 0f)
        primaryGrayscaleAnimator.duration = transitionDuration / 2
        primaryGrayscaleAnimator.addUpdateListener { animator ->
            val saturation = animator.animatedValue as Float
            views.primaryView.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(saturation)
            })
        }

        // Then fade out the primary view and fade in the overlay view
        val primaryFadeOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
        primaryFadeOutAnimator.duration = transitionDuration / 2
        primaryFadeOutAnimator.startDelay = transitionDuration / 2

        val overlayFadeInAnimator = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)
        overlayFadeInAnimator.duration = transitionDuration / 2
        overlayFadeInAnimator.startDelay = transitionDuration / 2

        // Finally, restore color to the overlay view
        val overlayColorAnimator = ValueAnimator.ofFloat(0f, 1f)
        overlayColorAnimator.duration = transitionDuration / 2
        overlayColorAnimator.startDelay = transitionDuration / 2
        overlayColorAnimator.addUpdateListener { animator ->
            val saturation = animator.animatedValue as Float
            views.overlayView.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(saturation)
            })
        }

        // Combine the animations
        animatorSet.playTogether(
            primaryGrayscaleAnimator,
            primaryFadeOutAnimator,
            overlayFadeInAnimator,
            overlayColorAnimator
        )

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Remove color filters
                views.primaryView.colorFilter = null
                views.overlayView.colorFilter = null

                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performCube3dTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Note: This is a more sophisticated version of the cube transition

        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.VISIBLE

            // Initialize for 3D rotation
            translationX = width.toFloat()
            rotationY = 90f
        }

        // Set the camera distance to avoid clipping
        val distance = views.primaryView.width * 5f
        views.primaryView.cameraDistance = distance
        views.overlayView.cameraDistance = distance

        // Create the animations
        val animatorSet = AnimatorSet()

        // Rotate the primary view out
        val primaryRotateAnimator =
            ObjectAnimator.ofFloat(views.primaryView, View.ROTATION_Y, 0f, -90f)
        val primaryTranslateAnimator = ObjectAnimator.ofFloat(
            views.primaryView,
            View.TRANSLATION_X,
            0f,
            -views.primaryView.width.toFloat()
        )

        // Rotate the overlay view in
        val overlayRotateAnimator =
            ObjectAnimator.ofFloat(views.overlayView, View.ROTATION_Y, 90f, 0f)
        val overlayTranslateAnimator = ObjectAnimator.ofFloat(
            views.overlayView,
            View.TRANSLATION_X,
            views.overlayView.width.toFloat(),
            0f
        )

        // Add lighting effects for 3D realism
        val shadowPaint = Paint()
        views.primaryView.setLayerType(View.LAYER_TYPE_HARDWARE, shadowPaint)
        views.overlayView.setLayerType(View.LAYER_TYPE_HARDWARE, shadowPaint)

        val shadowAnimator = ValueAnimator.ofFloat(0f, 1f)
        shadowAnimator.addUpdateListener { animator ->
            val progress = animator.animatedValue as Float

            // Apply shadow to the back face of the cube
            val primaryShadow =
                0.5f + 0.5f * Math.cos(Math.toRadians(views.primaryView.rotationY.toDouble()))
                    .toFloat()
            val overlayShadow =
                0.5f + 0.5f * Math.cos(Math.toRadians(views.overlayView.rotationY.toDouble()))
                    .toFloat()

            views.primaryView.alpha = primaryShadow

            // Apply a color matrix for lighting effects
            views.primaryView.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setScale(primaryShadow, primaryShadow, primaryShadow, 1f)
            })

            views.overlayView.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setScale(overlayShadow, overlayShadow, overlayShadow, 1f)
            })
        }
        shadowAnimator.duration = transitionDuration

        // Combine the animations
        animatorSet.playTogether(
            primaryRotateAnimator,
            primaryTranslateAnimator,
            overlayRotateAnimator,
            overlayTranslateAnimator,
            shadowAnimator
        )

        animatorSet.duration = transitionDuration
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Reset the layer type and remove color filters
                views.primaryView.setLayerType(View.LAYER_TYPE_NONE, null)
                views.overlayView.setLayerType(View.LAYER_TYPE_NONE, null)
                views.primaryView.colorFilter = null
                views.overlayView.colorFilter = null

                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performDoorwayTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val doorMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val doorMaskCanvas = Canvas(doorMaskBitmap)
        val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold just the door-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the door mask canvas
            doorMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial door position - just starting to open
            val initialProgress = 0.01f

            // Draw initial door shape (closed doors with tiny opening in middle)
            doorPaint.color = Color.WHITE
            doorPaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()
            val centerX = width / 2

            // Left door - starting at center with tiny gap
            val leftDoorPath = Path()
            leftDoorPath.moveTo(centerX - (initialProgress * centerX), 0f)
            leftDoorPath.lineTo(0f, 0f)
            leftDoorPath.lineTo(0f, height)
            leftDoorPath.lineTo(centerX - (initialProgress * centerX), height)
            leftDoorPath.close()

            // Right door - starting at center with tiny gap
            val rightDoorPath = Path()
            rightDoorPath.moveTo(centerX + (initialProgress * centerX), 0f)
            rightDoorPath.lineTo(width, 0f)
            rightDoorPath.lineTo(width, height)
            rightDoorPath.lineTo(centerX + (initialProgress * centerX), height)
            rightDoorPath.close()

            doorMaskCanvas.drawPath(leftDoorPath, doorPaint)
            doorMaskCanvas.drawPath(rightDoorPath, doorPaint)

            // Prepare initial image
            if (resource is BitmapDrawable) {
                val resultBitmap = Bitmap.createBitmap(
                    views.container.width,
                    views.container.height,
                    Bitmap.Config.ARGB_8888
                )
                val resultCanvas = Canvas(resultBitmap)

                val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                val destRect = calculateProperDestRect(
                    resource.bitmap.width,
                    resource.bitmap.height,
                    resultBitmap.width,
                    resultBitmap.height
                )

                resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                val maskPaint = Paint()
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                resultCanvas.drawBitmap(doorMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing door transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the door mask canvas
                doorMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation - ease out for door opening
                val progressCurved = Math.pow(progress.toDouble(), 0.6).toFloat()

                // Draw the door shapes on the mask canvas
                doorPaint.color = Color.WHITE
                doorPaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()
                val centerX = width / 2

                // Calculate how far the doors have opened
                val leftEdge = progressCurved * centerX
                val rightEdge = width - (progressCurved * centerX)

                // Left door
                val leftDoorPath = Path()
                leftDoorPath.moveTo(0f, 0f)
                leftDoorPath.lineTo(leftEdge, 0f)
                leftDoorPath.lineTo(leftEdge, height)
                leftDoorPath.lineTo(0f, height)
                leftDoorPath.close()

                // Right door
                val rightDoorPath = Path()
                rightDoorPath.moveTo(width, 0f)
                rightDoorPath.lineTo(rightEdge, 0f)
                rightDoorPath.lineTo(rightEdge, height)
                rightDoorPath.lineTo(width, height)
                rightDoorPath.close()

                doorMaskCanvas.drawPath(leftDoorPath, doorPaint)
                doorMaskCanvas.drawPath(rightDoorPath, doorPaint)

                // Create a copy of the new image, masked by the door shape
                if (resource is BitmapDrawable) {
                    val resultBitmap = Bitmap.createBitmap(
                        views.container.width,
                        views.container.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val resultCanvas = Canvas(resultBitmap)

                    val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                    val destRect = calculateProperDestRect(
                        resource.bitmap.width,
                        resource.bitmap.height,
                        resultBitmap.width,
                        resultBitmap.height
                    )

                    resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                    val maskPaint = Paint()
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    resultCanvas.drawBitmap(doorMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in door transition", e)
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    // Remove the temporary view
                    (views.container as ViewGroup).removeView(revealImageView)

                    // Make the new image fully visible
                    views.overlayView.visibility = View.VISIBLE
                    views.overlayView.alpha = 1f

                    // Clean up resources
                    doorMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up door transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    doorMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled door transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun performSimpleFadeTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // This is a simple fade where the old image completely disappears
        // before the new one appears

        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        // Create the animations - fade out then fade in
        val fadeOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
        fadeOutAnimator.duration = transitionDuration / 2
        fadeOutAnimator.interpolator = AccelerateInterpolator()

        val fadeInAnimator = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)
        fadeInAnimator.duration = transitionDuration / 2
        fadeInAnimator.interpolator = DecelerateInterpolator()

        // Combine the animations sequentially
        val animatorSet = AnimatorSet()
        animatorSet.playSequentially(fadeOutAnimator, fadeInAnimator)

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performFlashTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        // Create a white flash overlay
        val flashView = View(context)
        flashView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        flashView.setBackgroundColor(Color.WHITE)
        flashView.alpha = 0f

        // Add the flash view
        (views.container as ViewGroup).addView(flashView)

        // Create the animations
        val animatorSet = AnimatorSet()

        // Flash animation: quickly fade in and out a white overlay
        val flashInAnimator = ObjectAnimator.ofFloat(flashView, View.ALPHA, 0f, 1f)
        flashInAnimator.duration = transitionDuration / 4
        flashInAnimator.interpolator = AccelerateInterpolator()

        val flashOutAnimator = ObjectAnimator.ofFloat(flashView, View.ALPHA, 1f, 0f)
        flashOutAnimator.duration = transitionDuration / 4
        flashOutAnimator.interpolator = DecelerateInterpolator()

        // Switch images during the flash
        val switchImagesRunnable = Runnable {
            views.primaryView.alpha = 0f
            views.overlayView.alpha = 1f
        }

        // Combine the animations
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                // Schedule the image switch in the middle of the flash
                views.container.postDelayed(switchImagesRunnable, transitionDuration / 4)
            }

            override fun onAnimationEnd(animation: Animator) {
                // Remove the flash view
                (views.container as ViewGroup).removeView(flashView)
                // Remove any pending callbacks
                views.container.removeCallbacks(switchImagesRunnable)

                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.playSequentially(flashInAnimator, flashOutAnimator)
        animatorSet.start()
    }

    private fun performIllusionTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 0f
            visibility = View.VISIBLE
        }

        // Create the animation
        val animatorSet = AnimatorSet()

        // Distort the old image
        val scaleXAnimator =
            ObjectAnimator.ofFloat(views.primaryView, View.SCALE_X, 1f, 0.8f, 1.2f, 0.5f)
        val scaleYAnimator =
            ObjectAnimator.ofFloat(views.primaryView, View.SCALE_Y, 1f, 1.2f, 0.8f, 0.5f)
        val rotationAnimator =
            ObjectAnimator.ofFloat(views.primaryView, View.ROTATION, 0f, 5f, -5f, 0f)
        val alphaOutAnimator = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)

        // Reveal the new image with a twist
        val overlayAlphaAnimator = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)
        val overlayScaleXAnimator =
            ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 1.5f, 1f)
        val overlayScaleYAnimator =
            ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 1.5f, 1f)
        val overlayRotationAnimator =
            ObjectAnimator.ofFloat(views.overlayView, View.ROTATION, -5f, 0f)

        // Group primary view animations
        val primaryAnimatorSet = AnimatorSet()
        primaryAnimatorSet.playTogether(
            scaleXAnimator,
            scaleYAnimator,
            rotationAnimator,
            alphaOutAnimator
        )
        primaryAnimatorSet.duration = transitionDuration / 2

        // Group overlay view animations
        val overlayAnimatorSet = AnimatorSet()
        overlayAnimatorSet.playTogether(
            overlayAlphaAnimator,
            overlayScaleXAnimator,
            overlayScaleYAnimator,
            overlayRotationAnimator
        )
        overlayAnimatorSet.duration = transitionDuration / 2

        // Play the animation sets sequentially
        animatorSet.playSequentially(primaryAnimatorSet, overlayAnimatorSet)

        // Custom interpolator for a more fluid, "magical" effect
        val customInterpolator = object : TimeInterpolator {
            override fun getInterpolation(input: Float): Float {
                // Custom curve that starts fast, slows in the middle, then speeds up again
                return (1 - Math.cos(input * Math.PI * 2)).toFloat() / 2f
            }
        }

        scaleXAnimator.interpolator = customInterpolator
        scaleYAnimator.interpolator = customInterpolator
        rotationAnimator.interpolator = customInterpolator

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performRadialTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val radialMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val radialMaskCanvas = Canvas(radialMaskBitmap)
        val radialPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold just the radial-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Calculate the center point of the screen
        val centerX = views.container.width.toFloat() / 2
        val centerY = views.container.height.toFloat() / 2

        // Number of slices in the radial pattern
        val sliceCount = 12

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the radial mask canvas
            radialMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial radial position - just starting to reveal
            val initialProgress = 0.01f

            // Draw initial radial shape (small wedges)
            radialPaint.color = Color.WHITE
            radialPaint.style = Paint.Style.FILL

            // Define the maximum size of the radial pattern
            val maxRadius = Math.sqrt(
                Math.pow(views.container.width.toDouble(), 2.0) +
                        Math.pow(views.container.height.toDouble(), 2.0)
            ).toFloat()

            // Draw tiny initial wedges
            val initialRadius = maxRadius * initialProgress * 0.2f
            val initialAngle = 360f * initialProgress

            for (i in 0 until sliceCount) {
                val startAngle = i * (360f / sliceCount)
                val sweepAngle = initialAngle / sliceCount

                val path = Path()
                path.moveTo(centerX, centerY)

                // Create an arc
                val oval = RectF(
                    centerX - initialRadius,
                    centerY - initialRadius,
                    centerX + initialRadius,
                    centerY + initialRadius
                )
                path.arcTo(oval, startAngle, sweepAngle)
                path.close()

                radialMaskCanvas.drawPath(path, radialPaint)
            }

            // Prepare initial image
            if (resource is BitmapDrawable) {
                val resultBitmap = Bitmap.createBitmap(
                    views.container.width,
                    views.container.height,
                    Bitmap.Config.ARGB_8888
                )
                val resultCanvas = Canvas(resultBitmap)

                val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                val destRect = calculateProperDestRect(
                    resource.bitmap.width,
                    resource.bitmap.height,
                    resultBitmap.width,
                    resultBitmap.height
                )

                resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                val maskPaint = Paint()
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                resultCanvas.drawBitmap(radialMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing radial transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the radial mask canvas
                radialMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the radial shape
                radialPaint.color = Color.WHITE
                radialPaint.style = Paint.Style.FILL

                // Define the maximum size of the radial pattern
                val maxRadius = Math.sqrt(
                    Math.pow(views.container.width.toDouble(), 2.0) +
                            Math.pow(views.container.height.toDouble(), 2.0)
                ).toFloat()

                // Calculate current radius and sweep angle based on progress
                val currentRadius = maxRadius * progressCurved
                val sweepAngle = 360f / sliceCount

                for (i in 0 until sliceCount) {
                    val startAngle = i * sweepAngle

                    // Create a path for each slice
                    val path = Path()
                    path.moveTo(centerX, centerY)

                    // Create an arc
                    val oval = RectF(
                        centerX - currentRadius,
                        centerY - currentRadius,
                        centerX + currentRadius,
                        centerY + currentRadius
                    )

                    // As progress increases, the wedges get wider
                    val currentSweepAngle = sweepAngle * Math.min(1f, progressCurved * 3)
                    path.arcTo(oval, startAngle, currentSweepAngle)
                    path.close()

                    radialMaskCanvas.drawPath(path, radialPaint)
                }

                // Create a copy of the new image, masked by the radial shape
                if (resource is BitmapDrawable) {
                    val resultBitmap = Bitmap.createBitmap(
                        views.container.width,
                        views.container.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val resultCanvas = Canvas(resultBitmap)

                    val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                    val destRect = calculateProperDestRect(
                        resource.bitmap.width,
                        resource.bitmap.height,
                        resultBitmap.width,
                        resultBitmap.height
                    )

                    resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                    val maskPaint = Paint()
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    resultCanvas.drawBitmap(radialMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in radial transition", e)
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    // Remove the temporary view
                    (views.container as ViewGroup).removeView(revealImageView)

                    // Make the new image fully visible
                    views.overlayView.visibility = View.VISIBLE
                    views.overlayView.alpha = 1f

                    // Clean up resources
                    radialMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up radial transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    radialMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled radial transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun performRippleTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // IMPORTANT: Don't change visibility or properties of the primary view
        // until the animation is fully set up to prevent flicker

        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE // Start as INVISIBLE instead of setting alpha=0
            alpha = 1f  // Keep alpha at 1 to avoid any alpha transitions
        }

        // Calculate the center point of the screen
        val screenCenterX = views.container.width.toFloat() / 2
        val screenCenterY = views.container.height.toFloat() / 2

        // Calculate the maximum possible radius for the ripple
        val maxRadius = Math.sqrt(
            Math.pow(views.container.width.toDouble(), 2.0) +
                    Math.pow(views.container.height.toDouble(), 2.0)
        ).toFloat() * 0.6f // Make it slightly larger than needed to ensure full coverage

        // Create a bitmap for the reveal effect that will cover the new image
        val rippleMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val rippleMaskCanvas = Canvas(rippleMaskBitmap)
        val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold just the ripple-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)

        // Slow down animation
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the ripple mask canvas
            rippleMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial ripple size - very small to start with
            val initialProgress = 0.01f // Tiny starting progress
            val progressCurved = Math.pow(initialProgress.toDouble(), 0.8).toFloat()
            val currentRadius = progressCurved * maxRadius

            // Draw the initial ripple shape - a simple circle
            ripplePaint.color = Color.WHITE
            ripplePaint.style = Paint.Style.FILL

            rippleMaskCanvas.drawCircle(screenCenterX, screenCenterY, currentRadius, ripplePaint)

            // Prepare initial image
            if (resource is BitmapDrawable) {
                val resultBitmap = Bitmap.createBitmap(
                    views.container.width,
                    views.container.height,
                    Bitmap.Config.ARGB_8888
                )
                val resultCanvas = Canvas(resultBitmap)

                val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                val destRect = calculateProperDestRect(
                    resource.bitmap.width,
                    resource.bitmap.height,
                    resultBitmap.width,
                    resultBitmap.height
                )

                resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                val maskPaint = Paint()
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                resultCanvas.drawBitmap(rippleMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing ripple transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the ripple mask canvas
                rippleMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Calculate current ripple size with a curved progress for smoother growth
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()
                val currentRadius = progressCurved * maxRadius

                // Draw the ripple shape on the mask canvas - a simple circle that expands
                ripplePaint.color = Color.WHITE
                ripplePaint.style = Paint.Style.FILL

                // Optional: Add multiple ripple circles for a more complex effect
                /*
                val numRipples = 3
                val rippleSpacing = 40f
                for (i in 0 until numRipples) {
                    val rippleRadius = Math.max(0f, currentRadius - (i * rippleSpacing))
                    if (rippleRadius > 0) {
                        ripplePaint.alpha = (255 * (1 - (i.toFloat() / numRipples))).toInt()
                        rippleMaskCanvas.drawCircle(screenCenterX, screenCenterY, rippleRadius, ripplePaint)
                    }
                }
                */

                // Or use a simple expanding circle for a clean effect
                rippleMaskCanvas.drawCircle(screenCenterX, screenCenterY, currentRadius, ripplePaint)

                // Create a copy of the new image, masked by the ripple shape
                if (resource is BitmapDrawable) {
                    // Clear any previous bitmap and create new one
                    val resultBitmap = Bitmap.createBitmap(
                        views.container.width,
                        views.container.height,
                        Bitmap.Config.ARGB_8888
                    )

                    val resultCanvas = Canvas(resultBitmap)

                    // Calculate destination rectangle that maintains aspect ratio
                    val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                    val destRect = calculateProperDestRect(
                        resource.bitmap.width,
                        resource.bitmap.height,
                        resultBitmap.width,
                        resultBitmap.height
                    )

                    // Draw the bitmap with proper aspect ratio
                    resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                    // Apply the ripple mask to only show part of the new image
                    val maskPaint = Paint()
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    resultCanvas.drawBitmap(rippleMaskBitmap, 0f, 0f, maskPaint)

                    // Show the ripple-shaped cutout of the new image
                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    // For non-bitmap drawables, do a simpler approach
                    revealImageView.setImageDrawable(resource)

                    // Create a BitmapDrawable from the ripple mask
                    val maskDrawable = BitmapDrawable(context.resources, rippleMaskBitmap)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in ripple transition", e)
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    // Remove the temporary view
                    (views.container as ViewGroup).removeView(revealImageView)

                    // Make the new image fully visible
                    views.overlayView.visibility = View.VISIBLE
                    views.overlayView.alpha = 1f

                    // Clean up resources
                    rippleMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up ripple transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    rippleMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled ripple transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun performSlideExtendedTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.VISIBLE
        }

        // Randomly select slide direction
        val random = Random.Default
        val directions = arrayOf("left", "right", "up", "down")
        val direction = directions[random.nextInt(directions.size)]

        // Set initial position based on direction
        when (direction) {
            "left" -> {
                views.overlayView.translationX = views.container.width.toFloat()
                views.primaryView.translationX = 0f
            }

            "right" -> {
                views.overlayView.translationX = -views.container.width.toFloat()
                views.primaryView.translationX = 0f
            }

            "up" -> {
                views.overlayView.translationY = views.container.height.toFloat()
                views.primaryView.translationY = 0f
            }

            "down" -> {
                views.overlayView.translationY = -views.container.height.toFloat()
                views.primaryView.translationY = 0f
            }
        }

        // Create animations
        val animatorSet = AnimatorSet()

        // Animate the overlay (new image) in
        val overlayAnimator = when (direction) {
            "left" -> ObjectAnimator.ofFloat(
                views.overlayView,
                View.TRANSLATION_X,
                views.container.width.toFloat(),
                0f
            )

            "right" -> ObjectAnimator.ofFloat(
                views.overlayView,
                View.TRANSLATION_X,
                -views.container.width.toFloat(),
                0f
            )

            "up" -> ObjectAnimator.ofFloat(
                views.overlayView,
                View.TRANSLATION_Y,
                views.container.height.toFloat(),
                0f
            )

            "down" -> ObjectAnimator.ofFloat(
                views.overlayView,
                View.TRANSLATION_Y,
                -views.container.height.toFloat(),
                0f
            )

            else -> ObjectAnimator.ofFloat(
                views.overlayView,
                View.TRANSLATION_X,
                views.container.width.toFloat(),
                0f
            )
        }

        // Animate the primary (old image) out
        val primaryAnimator = when (direction) {
            "left" -> ObjectAnimator.ofFloat(
                views.primaryView,
                View.TRANSLATION_X,
                0f,
                -views.container.width.toFloat()
            )

            "right" -> ObjectAnimator.ofFloat(
                views.primaryView,
                View.TRANSLATION_X,
                0f,
                views.container.width.toFloat()
            )

            "up" -> ObjectAnimator.ofFloat(
                views.primaryView,
                View.TRANSLATION_Y,
                0f,
                -views.container.height.toFloat()
            )

            "down" -> ObjectAnimator.ofFloat(
                views.primaryView,
                View.TRANSLATION_Y,
                0f,
                views.container.height.toFloat()
            )

            else -> ObjectAnimator.ofFloat(
                views.primaryView,
                View.TRANSLATION_X,
                0f,
                -views.container.width.toFloat()
            )
        }

        // Optional: Add a slight scaling effect for more dynamic feel
        val overlayScaleX = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 0.95f, 1f)
        val overlayScaleY = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 0.95f, 1f)
        val primaryScaleX = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_X, 1f, 0.95f)
        val primaryScaleY = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_Y, 1f, 0.95f)

        // Play all animations together
        animatorSet.playTogether(
            overlayAnimator, primaryAnimator,
            overlayScaleX, overlayScaleY,
            primaryScaleX, primaryScaleY
        )

        // Configure the animation
        animatorSet.duration = transitionDuration
        animatorSet.interpolator = DecelerateInterpolator()

        // Add listener for completion
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Reset transformations
                views.primaryView.translationX = 0f
                views.primaryView.translationY = 0f
                views.overlayView.translationX = 0f
                views.overlayView.translationY = 0f
                views.primaryView.scaleX = 1f
                views.primaryView.scaleY = 1f
                views.overlayView.scaleX = 1f
                views.overlayView.scaleY = 1f

                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }

    private fun performWindTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.INVISIBLE // We'll use custom wind panels
        }

        val container = views.container as ViewGroup
        val containerWidth = container.width
        val containerHeight = container.height

        try {
            // Number of horizontal slices for the wind effect
            val numSlices = 10
            val sliceHeight = containerHeight / numSlices

            // Get the current bitmap
            val currentBitmap = if (views.primaryView.drawable is BitmapDrawable) {
                (views.primaryView.drawable as BitmapDrawable).bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    containerWidth,
                    containerHeight,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                views.primaryView.drawable.setBounds(0, 0, canvas.width, canvas.height)
                views.primaryView.drawable.draw(canvas)
                bitmap
            }

            // Get the new bitmap
            val newBitmap = if (resource is BitmapDrawable) {
                resource.bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    containerWidth,
                    containerHeight,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                resource.setBounds(0, 0, canvas.width, canvas.height)
                resource.draw(canvas)
                bitmap
            }

            // Create views for each slice of both images
            val sliceViews = mutableListOf<Pair<ImageView, ImageView>>()

            for (i in 0 until numSlices) {
                // Calculate the offset position for this slice
                val sliceTop = i * sliceHeight

                // Create slices from the current image
                val currentSliceBitmap = Bitmap.createBitmap(
                    currentBitmap,
                    0,
                    sliceTop,
                    containerWidth,
                    sliceHeight.coerceAtMost(currentBitmap.height - sliceTop)
                )

                val currentSliceView = ImageView(context)
                currentSliceView.layoutParams = FrameLayout.LayoutParams(
                    containerWidth,
                    sliceHeight
                ).apply {
                    topMargin = sliceTop
                }
                currentSliceView.setImageBitmap(currentSliceBitmap)

                // Create slices from the new image
                val newSliceBitmap = Bitmap.createBitmap(
                    newBitmap,
                    0,
                    sliceTop,
                    containerWidth,
                    sliceHeight.coerceAtMost(newBitmap.height - sliceTop)
                )

                val newSliceView = ImageView(context)
                newSliceView.layoutParams = FrameLayout.LayoutParams(
                    containerWidth,
                    sliceHeight
                ).apply {
                    topMargin = sliceTop
                }
                newSliceView.setImageBitmap(newSliceBitmap)

                // Set initial position for the new slice (off-screen to the left)
                newSliceView.translationX = -containerWidth.toFloat()

                // Add to container
                container.addView(currentSliceView)
                container.addView(newSliceView)

                sliceViews.add(Pair(currentSliceView, newSliceView))
            }

            // Hide the original views
            views.primaryView.visibility = View.INVISIBLE

            // Create animations for each slice with staggered delays
            val animatorSet = AnimatorSet()
            val animators = mutableListOf<Animator>()

            // Random wind effect with varying speeds
            val random = Random.Default

            for (i in 0 until numSlices) {
                val (currentSliceView, newSliceView) = sliceViews[i]

                // Randomize the delay for each slice to create wind effect
                val delay = random.nextInt(0, (transitionDuration / 3).toInt()).toLong()

                // Randomize the duration for varied speed
                val duration = transitionDuration - delay

                // Animate current slice out to the right
                val currentSliceAnimator = ObjectAnimator.ofFloat(
                    currentSliceView,
                    View.TRANSLATION_X,
                    0f,
                    containerWidth.toFloat() + random.nextInt(50, 200)
                )
                currentSliceAnimator.startDelay = delay
                currentSliceAnimator.duration = duration

                // Add slight rotation for more natural wind feel
                val rotation = random.nextFloat() * 5f * (if (i % 2 == 0) 1 else -1)
                val currentRotationAnimator = ObjectAnimator.ofFloat(
                    currentSliceView,
                    View.ROTATION,
                    0f,
                    rotation
                )
                currentRotationAnimator.startDelay = delay
                currentRotationAnimator.duration = duration

                // Animate new slice in from the left
                val newSliceAnimator = ObjectAnimator.ofFloat(
                    newSliceView,
                    View.TRANSLATION_X,
                    -containerWidth.toFloat() - random.nextInt(50, 200),
                    0f
                )
                newSliceAnimator.startDelay = delay
                newSliceAnimator.duration = duration

                // Add slight rotation for more natural wind feel
                val newRotationAnimator = ObjectAnimator.ofFloat(
                    newSliceView,
                    View.ROTATION,
                    rotation,
                    0f
                )
                newRotationAnimator.startDelay = delay
                newRotationAnimator.duration = duration

                // Add all animators
                animators.add(currentSliceAnimator)
                animators.add(currentRotationAnimator)
                animators.add(newSliceAnimator)
                animators.add(newRotationAnimator)
            }

            // Play all animations
            animatorSet.playTogether(animators)

            // Use a custom interpolator for wind-like movement
            val windInterpolator = object : TimeInterpolator {
                override fun getInterpolation(input: Float): Float {
                    // Wind-like curve: starts slow, then accelerates, then eases out
                    return (1 - Math.cos(input * Math.PI)).toFloat() / 2
                }
            }

            animators.forEach { it.interpolator = windInterpolator }

            // Add listener for completion
            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove all slice views
                    sliceViews.forEach { (currentSlice, newSlice) ->
                        container.removeView(currentSlice)
                        container.removeView(newSlice)
                    }

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })

            animatorSet.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in wind transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performWipeTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.INVISIBLE
        }

        val container = views.container as ViewGroup
        val containerWidth = container.width
        val containerHeight = container.height

        try {
            // Randomly choose wipe direction
            val random = Random.Default
            val directions = arrayOf("left", "right", "up", "down")
            val direction = directions[random.nextInt(directions.size)]

            // Create a mask for the wipe effect
            val maskView = View(context)
            maskView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Add the new image behind the mask
            views.overlayView.visibility = View.VISIBLE

            // Create a bitmap for clipping
            val maskBitmap =
                Bitmap.createBitmap(containerWidth, containerHeight, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(maskBitmap)
            val maskPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            }

            // Create a custom image view that will be revealed gradually
            val revealImageView = ImageView(context)
            revealImageView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Set the same image as the overlay
            if (resource is BitmapDrawable) {
                revealImageView.setImageBitmap(resource.bitmap)
            } else {
                revealImageView.setImageDrawable(resource.constantState?.newDrawable())
            }

            // Add the reveal view
            container.addView(revealImageView)

            // Create the animation
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = transitionDuration
            animator.interpolator = LinearInterpolator()

            animator.addUpdateListener { valueAnimator ->
                val progress = valueAnimator.animatedValue as Float

                // Clear the mask
                maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Create the wipe effect based on direction
                val rect = when (direction) {
                    "left" -> RectF(0f, 0f, containerWidth * progress, containerHeight.toFloat())
                    "right" -> RectF(
                        containerWidth * (1 - progress),
                        0f,
                        containerWidth.toFloat(),
                        containerHeight.toFloat()
                    )
                    "up" -> RectF(0f, 0f, containerWidth.toFloat(), containerHeight * progress)
                    "down" -> RectF(
                        0f,
                        containerHeight * (1 - progress),
                        containerWidth.toFloat(),
                        containerHeight.toFloat()
                    )
                    else -> RectF(0f, 0f, containerWidth * progress, containerHeight.toFloat())
                }

                // Draw the mask
                maskCanvas.drawRect(rect, maskPaint)

                // Use bitmap-based approach for API 24+
                val resultBitmap = Bitmap.createBitmap(
                    containerWidth,
                    containerHeight,
                    Bitmap.Config.ARGB_8888
                )
                val resultCanvas = Canvas(resultBitmap)

                // Draw the new image
                if (resource is BitmapDrawable) {
                    resultCanvas.drawBitmap(resource.bitmap, 0f, 0f, null)
                } else {
                    resource.setBounds(0, 0, resultCanvas.width, resultCanvas.height)
                    resource.draw(resultCanvas)
                }

                // Apply the mask
                val paint = Paint()
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                resultCanvas.drawBitmap(maskBitmap, 0f, 0f, paint)

                revealImageView.setImageBitmap(resultBitmap)
            }

            // Add listener for completion
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Remove the reveal view
                    container.removeView(revealImageView)

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            })

            animator.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error in wipe transition", e)
            // Fall back to fade transition
            performFadeTransition(views, resource, nextIndex, transitionDuration, callback)
        }
    }

    private fun performStarTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // IMPORTANT: Don't change visibility or properties of the primary view
        // until the animation is fully set up to prevent flicker

        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE // Start as INVISIBLE instead of setting alpha=0
            alpha = 1f  // Keep alpha at 1 to avoid any alpha transitions
        }

        // Calculate the center point of the screen
        val screenCenterX = views.container.width.toFloat() / 2
        val screenCenterY = views.container.height.toFloat() / 2

        // Calculate the maximum possible radius based on screen dimensions
        val maxRadius = Math.max(
            Math.max(screenCenterX, views.container.width - screenCenterX),
            Math.max(screenCenterY, views.container.height - screenCenterY)
        ) * 1.5f

        // Create a bitmap for the reveal effect that will cover the new image
        val starMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val starMaskCanvas = Canvas(starMaskBitmap)
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold just the star-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)

        // Slow down animation
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the star mask canvas
            starMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial star size - very small to start with
            val initialProgress = 0.01f // Tiny starting progress
            val progressCurved = Math.pow(initialProgress.toDouble(), 0.8).toFloat()
            val currentRadius = progressCurved * maxRadius

            // Draw the initial star shape
            starPaint.color = Color.WHITE
            starPaint.style = Paint.Style.FILL

            val path = Path()
            val outerRadius = currentRadius
            val innerRadius = currentRadius * 0.4f
            val numPoints = 10

            // Draw initial star
            path.moveTo(
                screenCenterX,
                screenCenterY - outerRadius
            )

            for (i in 1 until numPoints * 2) {
                val radius = if (i % 2 == 0) outerRadius else innerRadius
                val angle = Math.PI * i / numPoints - Math.PI / 2

                path.lineTo(
                    screenCenterX + radius * Math.cos(angle).toFloat(),
                    screenCenterY + radius * Math.sin(angle).toFloat()
                )
            }

            path.close()
            starMaskCanvas.drawPath(path, starPaint)

            // Prepare initial image
            if (resource is BitmapDrawable) {
                val resultBitmap = Bitmap.createBitmap(
                    views.container.width,
                    views.container.height,
                    Bitmap.Config.ARGB_8888
                )
                val resultCanvas = Canvas(resultBitmap)

                val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                val destRect = calculateProperDestRect(
                    resource.bitmap.width,
                    resource.bitmap.height,
                    resultBitmap.width,
                    resultBitmap.height
                )

                resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                val maskPaint = Paint()
                maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                resultCanvas.drawBitmap(starMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing star transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the star mask canvas
                starMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Calculate current star size with a curved progress for smoother growth
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()
                val currentRadius = progressCurved * maxRadius

                // Draw the star shape on the mask canvas
                starPaint.color = Color.WHITE
                starPaint.style = Paint.Style.FILL

                val path = Path()
                val outerRadius = currentRadius
                val innerRadius = currentRadius * 0.4f
                val numPoints = 10

                path.moveTo(
                    screenCenterX,
                    screenCenterY - outerRadius
                )

                for (i in 1 until numPoints * 2) {
                    val radius = if (i % 2 == 0) outerRadius else innerRadius
                    val angle = Math.PI * i / numPoints - Math.PI / 2

                    path.lineTo(
                        screenCenterX + radius * Math.cos(angle).toFloat(),
                        screenCenterY + radius * Math.sin(angle).toFloat()
                    )
                }

                path.close()
                starMaskCanvas.drawPath(path, starPaint)

                // Create a copy of the new image, masked by the star shape
                if (resource is BitmapDrawable) {
                    // Clear any previous bitmap and create new one
                    val resultBitmap = Bitmap.createBitmap(
                        views.container.width,
                        views.container.height,
                        Bitmap.Config.ARGB_8888
                    )

                    val resultCanvas = Canvas(resultBitmap)

                    // Calculate destination rectangle that maintains aspect ratio
                    val sourceRect = Rect(0, 0, resource.bitmap.width, resource.bitmap.height)
                    val destRect = calculateProperDestRect(
                        resource.bitmap.width,
                        resource.bitmap.height,
                        resultBitmap.width,
                        resultBitmap.height
                    )

                    // Draw the bitmap with proper aspect ratio
                    resultCanvas.drawBitmap(resource.bitmap, sourceRect, destRect, null)

                    // Apply the star mask to only show part of the new image
                    val maskPaint = Paint()
                    maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    resultCanvas.drawBitmap(starMaskBitmap, 0f, 0f, maskPaint)

                    // Show the star-shaped cutout of the new image
                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    // For non-bitmap drawables, do a simpler approach
                    revealImageView.setImageDrawable(resource)

                    // Create a BitmapDrawable from the star mask
                    val maskDrawable = BitmapDrawable(context.resources, starMaskBitmap)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in star transition", e)
            }
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try {
                    // Remove the temporary view
                    (views.container as ViewGroup).removeView(revealImageView)

                    // Make the new image fully visible
                    views.overlayView.visibility = View.VISIBLE
                    views.overlayView.alpha = 1f

                    // Clean up resources
                    starMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up star transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    starMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled star transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun calculateProperDestRect(
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): Rect {
        val srcRatio = srcWidth.toFloat() / srcHeight.toFloat()
        val dstRatio = dstWidth.toFloat() / dstHeight.toFloat()

        val resultRect = Rect()

        if (srcRatio > dstRatio) {
            // Source is wider than destination, scale to fit width
            resultRect.left = 0
            resultRect.right = dstWidth
            val scaledHeight = (dstWidth / srcRatio).toInt()
            val yOffset = (dstHeight - scaledHeight) / 2
            resultRect.top = yOffset
            resultRect.bottom = yOffset + scaledHeight
        } else {
            // Source is taller than destination, scale to fit height
            resultRect.top = 0
            resultRect.bottom = dstHeight
            val scaledWidth = (dstHeight * srcRatio).toInt()
            val xOffset = (dstWidth - scaledWidth) / 2
            resultRect.left = xOffset
            resultRect.right = xOffset + scaledWidth
        }

        return resultRect
    }

    private fun performSwapTransition(
        views: TransitionViews,
        resource: Drawable,
        nextIndex: Int,
        transitionDuration: Long,
        callback: TransitionCompletionCallback
    ) {
        // Set up the views
        views.primaryView.visibility = View.VISIBLE
        views.overlayView.apply {
            setImageDrawable(resource)
            alpha = 1f
            visibility = View.VISIBLE
            scaleX = 0.8f
            scaleY = 0.8f
            rotationY = 180f // Flipped initially
        }

        // Set the camera distance to avoid clipping
        val distance = 8000f
        views.primaryView.cameraDistance = distance
        views.overlayView.cameraDistance = distance

        // Create the animations
        val animatorSet = AnimatorSet()

        // First half of animation - primary view flips out
        val primaryRotateOut = ObjectAnimator.ofFloat(views.primaryView, View.ROTATION_Y, 0f, 90f)
        primaryRotateOut.duration = transitionDuration / 2
        primaryRotateOut.interpolator = AccelerateInterpolator()

        // Second half of animation - overlay view flips in
        val overlayRotateIn = ObjectAnimator.ofFloat(views.overlayView, View.ROTATION_Y, 180f, 360f)
        overlayRotateIn.duration = transitionDuration / 2
        overlayRotateIn.startDelay = transitionDuration / 2
        overlayRotateIn.interpolator = DecelerateInterpolator()

        // Scale animations for the overlay (for a "pop" effect)
        val overlayScaleX = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 0.8f, 1f)
        val overlayScaleY = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 0.8f, 1f)
        overlayScaleX.duration = transitionDuration / 4
        overlayScaleY.duration = transitionDuration / 4
        overlayScaleX.startDelay = transitionDuration * 3 / 4
        overlayScaleY.startDelay = transitionDuration * 3 / 4

        // Combine the animations
        animatorSet.playTogether(primaryRotateOut, overlayRotateIn, overlayScaleX, overlayScaleY)

        // Add listener to hide primary view when it's rotated to 90 degrees
        primaryRotateOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                views.primaryView.visibility = View.INVISIBLE
            }
        })

        // Add listener for completion
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Reset rotation values
                views.primaryView.rotationY = 0f
                views.overlayView.rotationY = 0f

                callback.onTransitionCompleted(resource, nextIndex)
            }
        })

        animatorSet.start()
    }
}
