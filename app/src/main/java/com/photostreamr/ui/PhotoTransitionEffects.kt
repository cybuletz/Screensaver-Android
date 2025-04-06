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
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val splitMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val splitMaskCanvas = Canvas(splitMaskBitmap)
        val splitPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the split-shaped cutout of the new image
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
            // Clear the split mask canvas
            splitMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial split position - just starting to appear
            val initialProgress = 0.01f

            // Draw initial split shape
            splitPaint.color = Color.WHITE
            splitPaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()
            val centerY = height / 2f

            // Top panel (tiny)
            val topHeight = centerY * initialProgress
            splitMaskCanvas.drawRect(0f, 0f, width, topHeight, splitPaint)

            // Bottom panel (tiny)
            val bottomStart = height - (centerY * initialProgress)
            splitMaskCanvas.drawRect(0f, bottomStart, width, height, splitPaint)

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
                resultCanvas.drawBitmap(splitMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing split transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the split mask canvas
                splitMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the split shape
                splitPaint.color = Color.WHITE
                splitPaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()
                val centerY = height / 2f

                // Calculate the height of each panel based on progress
                val panelHeight = centerY * progressCurved

                // Top panel (growing from top)
                splitMaskCanvas.drawRect(0f, 0f, width, panelHeight, splitPaint)

                // Bottom panel (growing from bottom)
                val bottomStart = height - panelHeight
                splitMaskCanvas.drawRect(0f, bottomStart, width, height, splitPaint)

                // Create a copy of the new image, masked by the split shape
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
                    resultCanvas.drawBitmap(splitMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in split transition", e)
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
                    splitMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up split transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    splitMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled split transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
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
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val checkerMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val checkerMaskCanvas = Canvas(checkerMaskBitmap)
        val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the checkerboard-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Checkerboard configuration
        val gridSize = 8 // 8x8 grid of squares

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the checker mask canvas
            checkerMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial checker position - just starting to appear
            val initialProgress = 0.01f

            // Draw initial checker shape (tiny squares)
            checkerPaint.color = Color.WHITE
            checkerPaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()
            val cellWidth = width / gridSize
            val cellHeight = height / gridSize

            // Draw initial tiny checkerboard pattern
            for (row in 0 until gridSize) {
                for (col in 0 until gridSize) {
                    // Create a staggered effect where some squares appear before others
                    val isEvenCell = (row + col) % 2 == 0

                    // Skip the cells that haven't started appearing yet
                    if (initialProgress < 0.05 && !isEvenCell) continue

                    // Small square in the center of each cell
                    val centerX = col * cellWidth + cellWidth / 2
                    val centerY = row * cellHeight + cellHeight / 2
                    val squareSize = cellWidth * initialProgress * 0.2f

                    checkerMaskCanvas.drawRect(
                        centerX - squareSize / 2,
                        centerY - squareSize / 2,
                        centerX + squareSize / 2,
                        centerY + squareSize / 2,
                        checkerPaint
                    )
                }
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
                resultCanvas.drawBitmap(checkerMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing checkerboard transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the checker mask canvas
                checkerMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the checkerboard pattern
                checkerPaint.color = Color.WHITE
                checkerPaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()
                val cellWidth = width / gridSize
                val cellHeight = height / gridSize

                for (row in 0 until gridSize) {
                    for (col in 0 until gridSize) {
                        // Create a staggered effect where some squares appear before others
                        val isEvenCell = (row + col) % 2 == 0

                        // Delay factor based on position in grid - cells further from center appear later
                        val centerRow = gridSize / 2f
                        val centerCol = gridSize / 2f
                        val distanceFromCenter = Math.sqrt(
                            Math.pow((row - centerRow).toDouble(), 2.0) +
                                    Math.pow((col - centerCol).toDouble(), 2.0)
                        ).toFloat() / (gridSize / 2f)

                        // Adjust progress based on position
                        val cellDelay = if (isEvenCell) 0f else 0.2f
                        val cellProgress = Math.max(0f, progressCurved - cellDelay - distanceFromCenter * 0.3f)

                        // Calculate square size based on progress
                        val maxSize = Math.min(cellWidth, cellHeight)
                        val squareSize = maxSize * Math.min(1f, cellProgress * 1.5f)

                        if (squareSize > 0) {
                            val left = col * cellWidth + (cellWidth - squareSize) / 2
                            val top = row * cellHeight + (cellHeight - squareSize) / 2

                            checkerMaskCanvas.drawRect(
                                left,
                                top,
                                left + squareSize,
                                top + squareSize,
                                checkerPaint
                            )
                        }
                    }
                }

                // Create a copy of the new image, masked by the checkerboard shape
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
                    resultCanvas.drawBitmap(checkerMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkerboard transition", e)
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
                    checkerMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up checkerboard transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    checkerMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled checkerboard transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun performKaleidoscopeTransition(
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
        val kaleidoscopeMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val kaleidoscopeMaskCanvas = Canvas(kaleidoscopeMaskBitmap)
        val kaleidoscopePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the kaleidoscope cutout of the new image
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

        // Kaleidoscope configuration
        val segmentCount = 8 // Number of segments in the kaleidoscope
        val rotationAngle = 360f / segmentCount

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the kaleidoscope mask canvas
            kaleidoscopeMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial kaleidoscope size - just starting to appear
            val initialProgress = 0.01f

            // Draw initial kaleidoscope shape (tiny segments)
            kaleidoscopePaint.color = Color.WHITE
            kaleidoscopePaint.style = Paint.Style.FILL

            // Maximum radius - diagonal from center to corner
            val maxRadius = Math.sqrt(
                Math.pow(centerX.toDouble(), 2.0) +
                        Math.pow(centerY.toDouble(), 2.0)
            ).toFloat()

            // Draw tiny initial segments
            val initialRadius = maxRadius * initialProgress

            for (i in 0 until segmentCount) {
                val startAngle = i * rotationAngle

                val path = Path()
                path.moveTo(centerX, centerY)

                val startRadians = Math.toRadians(startAngle.toDouble())
                val endRadians = Math.toRadians((startAngle + rotationAngle).toDouble())

                val startX = centerX + initialRadius * Math.cos(startRadians).toFloat()
                val startY = centerY + initialRadius * Math.sin(startRadians).toFloat()

                val endX = centerX + initialRadius * Math.cos(endRadians).toFloat()
                val endY = centerY + initialRadius * Math.sin(endRadians).toFloat()

                path.lineTo(startX, startY)

                // Add a small arc between the two points
                val oval = RectF(
                    centerX - initialRadius,
                    centerY - initialRadius,
                    centerX + initialRadius,
                    centerY + initialRadius
                )
                path.arcTo(oval, startAngle, rotationAngle)

                path.close()

                kaleidoscopeMaskCanvas.drawPath(path, kaleidoscopePaint)
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
                resultCanvas.drawBitmap(kaleidoscopeMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing kaleidoscope transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the kaleidoscope mask canvas
                kaleidoscopeMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the kaleidoscope pattern
                kaleidoscopePaint.color = Color.WHITE
                kaleidoscopePaint.style = Paint.Style.FILL

                // Maximum radius - diagonal from center to corner
                val maxRadius = Math.sqrt(
                    Math.pow(centerX.toDouble(), 2.0) +
                            Math.pow(centerY.toDouble(), 2.0)
                ).toFloat()

                // Calculate current radius based on progress
                val currentRadius = maxRadius * progressCurved

                // Add rotation effect to the kaleidoscope
                val rotationOffset = progressCurved * 30f // Rotate up to 30 degrees as it grows

                for (i in 0 until segmentCount) {
                    val startAngle = i * rotationAngle + rotationOffset

                    val path = Path()
                    path.moveTo(centerX, centerY)

                    val startRadians = Math.toRadians(startAngle.toDouble())
                    val endRadians = Math.toRadians((startAngle + rotationAngle).toDouble())

                    val startX = centerX + currentRadius * Math.cos(startRadians).toFloat()
                    val startY = centerY + currentRadius * Math.sin(startRadians).toFloat()

                    val endX = centerX + currentRadius * Math.cos(endRadians).toFloat()
                    val endY = centerY + currentRadius * Math.sin(endRadians).toFloat()

                    path.lineTo(startX, startY)

                    // Add an arc between the two points
                    val oval = RectF(
                        centerX - currentRadius,
                        centerY - currentRadius,
                        centerX + currentRadius,
                        centerY + currentRadius
                    )
                    path.arcTo(oval, startAngle, rotationAngle)

                    path.close()

                    kaleidoscopeMaskCanvas.drawPath(path, kaleidoscopePaint)
                }

                // Create a copy of the new image, masked by the kaleidoscope shape
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
                    resultCanvas.drawBitmap(kaleidoscopeMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in kaleidoscope transition", e)
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
                    kaleidoscopeMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up kaleidoscope transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    kaleidoscopeMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled kaleidoscope transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
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
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val origamiMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val origamiMaskCanvas = Canvas(origamiMaskBitmap)
        val origamiPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the origami-shaped cutout of the new image
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
            // Clear the origami mask canvas
            origamiMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial origami position - just starting to appear
            val initialProgress = 0.01f

            // Draw initial origami shape (small triangle from corner)
            origamiPaint.color = Color.WHITE
            origamiPaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()

            // Draw a small triangle in the top-left corner
            val initialSize = width * initialProgress

            val path = Path()
            path.moveTo(0f, 0f)
            path.lineTo(initialSize, 0f)
            path.lineTo(0f, initialSize)
            path.close()

            origamiMaskCanvas.drawPath(path, origamiPaint)

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
                resultCanvas.drawBitmap(origamiMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing origami transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the origami mask canvas
                origamiMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the origami shape
                origamiPaint.color = Color.WHITE
                origamiPaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()

                // The origami unfold happens in phases
                if (progressCurved < 0.33f) {
                    // Phase 1: Triangle from top-left growing to center
                    val normalizedProgress = progressCurved * 3f // Scale to 0-1 range for this phase

                    val size = width * normalizedProgress

                    val path = Path()
                    path.moveTo(0f, 0f)
                    path.lineTo(size, 0f)
                    path.lineTo(0f, size)
                    path.close()

                    origamiMaskCanvas.drawPath(path, origamiPaint)

                } else if (progressCurved < 0.66f) {
                    // Phase 2: First triangle fully unfolded, second triangle unfolding
                    val normalizedProgress = (progressCurved - 0.33f) * 3f // Scale to 0-1 range for this phase

                    // First triangle (full size)
                    val path1 = Path()
                    path1.moveTo(0f, 0f)
                    path1.lineTo(width, 0f)
                    path1.lineTo(0f, height)
                    path1.close()

                    // Second triangle (growing)
                    val path2 = Path()
                    path2.moveTo(width, 0f)
                    path2.lineTo(width, height * normalizedProgress)
                    path2.lineTo(width - (width * normalizedProgress), height)
                    path2.lineTo(0f, height)
                    path2.close()

                    origamiMaskCanvas.drawPath(path1, origamiPaint)
                    origamiMaskCanvas.drawPath(path2, origamiPaint)

                } else {
                    // Phase 3: Both triangles fully unfolded, filling the rectangle
                    val normalizedProgress = (progressCurved - 0.66f) * 3f // Scale to 0-1 range for this phase

                    // At this point, we've covered most of the screen with two triangles
                    // Now we just fill in the remaining bottom-right corner
                    val path = Path()
                    path.moveTo(0f, 0f)
                    path.lineTo(width, 0f)
                    path.lineTo(width, height)
                    path.lineTo(0f, height)
                    path.close()

                    origamiMaskCanvas.drawPath(path, origamiPaint)
                }

                // Create a copy of the new image, masked by the origami shape
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
                    resultCanvas.drawBitmap(origamiMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in origami transition", e)
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
                    origamiMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up origami transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    origamiMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled origami transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
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

    private fun performCrystallizeTransition(
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
        val crystalMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val crystalMaskCanvas = Canvas(crystalMaskBitmap)
        val crystalPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the crystallized cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Create a set of random triangles to form the crystal pattern
        val random = Random(System.currentTimeMillis())
        val triangleCount = 50
        val triangles = ArrayList<Triple<PointF, PointF, PointF>>(triangleCount)

        val width = views.container.width.toFloat()
        val height = views.container.height.toFloat()

        // Generate random triangles across the screen
        for (i in 0 until triangleCount) {
            val point1 = PointF(random.nextFloat() * width, random.nextFloat() * height)
            val point2 = PointF(random.nextFloat() * width, random.nextFloat() * height)
            val point3 = PointF(random.nextFloat() * width, random.nextFloat() * height)
            triangles.add(Triple(point1, point2, point3))
        }

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the crystal mask canvas
            crystalMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial crystal position - just starting to appear
            val initialProgress = 0.01f

            // Draw initial crystal shape (tiny triangles)
            crystalPaint.color = Color.WHITE
            crystalPaint.style = Paint.Style.FILL

            // Draw just a few tiny triangles
            val initialTriangles = Math.max(1, (triangleCount * initialProgress).toInt())
            for (i in 0 until initialTriangles) {
                val (p1, p2, p3) = triangles[i]

                // Scale triangles to be tiny initially
                val centerX = (p1.x + p2.x + p3.x) / 3f
                val centerY = (p1.y + p2.y + p3.y) / 3f

                val scaledP1 = PointF(
                    centerX + (p1.x - centerX) * initialProgress,
                    centerY + (p1.y - centerY) * initialProgress
                )
                val scaledP2 = PointF(
                    centerX + (p2.x - centerX) * initialProgress,
                    centerY + (p2.y - centerY) * initialProgress
                )
                val scaledP3 = PointF(
                    centerX + (p3.x - centerX) * initialProgress,
                    centerY + (p3.y - centerY) * initialProgress
                )

                val path = Path()
                path.moveTo(scaledP1.x, scaledP1.y)
                path.lineTo(scaledP2.x, scaledP2.y)
                path.lineTo(scaledP3.x, scaledP3.y)
                path.close()

                crystalMaskCanvas.drawPath(path, crystalPaint)
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
                resultCanvas.drawBitmap(crystalMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing crystallize transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the crystal mask canvas
                crystalMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the crystal shape
                crystalPaint.color = Color.WHITE
                crystalPaint.style = Paint.Style.FILL

                // Calculate how many triangles to show based on progress
                val visibleTriangles = Math.max(1, (triangleCount * progressCurved * 1.2f).toInt())

                for (i in 0 until visibleTriangles) {
                    if (i >= triangles.size) break

                    val (p1, p2, p3) = triangles[i]

                    // Calculate triangle specific progress - triangles appear at different times
                    val triangleDelay = i.toFloat() / triangleCount * 0.5f // Stagger the appearance
                    val triangleProgress = Math.max(0f, progressCurved - triangleDelay)

                    if (triangleProgress > 0) {
                        // Scale triangles based on their individual progress
                        val centerX = (p1.x + p2.x + p3.x) / 3f
                        val centerY = (p1.y + p2.y + p3.y) / 3f

                        val growFactor = Math.min(1f, triangleProgress * 2f)

                        val scaledP1 = PointF(
                            centerX + (p1.x - centerX) * growFactor,
                            centerY + (p1.y - centerY) * growFactor
                        )
                        val scaledP2 = PointF(
                            centerX + (p2.x - centerX) * growFactor,
                            centerY + (p2.y - centerY) * growFactor
                        )
                        val scaledP3 = PointF(
                            centerX + (p3.x - centerX) * growFactor,
                            centerY + (p3.y - centerY) * growFactor
                        )

                        val path = Path()
                        path.moveTo(scaledP1.x, scaledP1.y)
                        path.lineTo(scaledP2.x, scaledP2.y)
                        path.lineTo(scaledP3.x, scaledP3.y)
                        path.close()

                        crystalMaskCanvas.drawPath(path, crystalPaint)
                    }
                }

                // Create a copy of the new image, masked by the crystal shape
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
                    resultCanvas.drawBitmap(crystalMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in crystallize transition", e)
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
                    crystalMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up crystallize transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    crystalMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled crystallize transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
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
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val diagonalMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val diagonalMaskCanvas = Canvas(diagonalMaskBitmap)
        val diagonalPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the diagonal-shaped cutout of the new image
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
            // Clear the diagonal mask canvas
            diagonalMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial diagonal position - just starting to appear
            val initialProgress = 0.01f

            // Draw initial diagonal shape (small triangle from corner)
            diagonalPaint.color = Color.WHITE
            diagonalPaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()

            // The diagonal wipe moves from bottom-left to top-right
            // At progress 0, nothing is showing
            // At progress 1, everything is showing

            // Calculate how far the diagonal line should be from the origin (bottom-left)
            val maxDiagonalLength = width + height // The maximum distance the diagonal line will travel
            val initialOffset = -height + (initialProgress * maxDiagonalLength)

            // Create a path for the revealed portion
            val path = Path()
            path.moveTo(0f, height) // Start at bottom-left
            path.lineTo(0f, Math.max(0f, height - initialOffset)) // Up the left edge

            if (initialOffset > height) {
                // If we've moved past the top-left corner
                path.lineTo(initialOffset - height, 0f) // Across the top edge
            }

            path.lineTo(0f, height) // Back to bottom-left
            path.close()

            diagonalMaskCanvas.drawPath(path, diagonalPaint)

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
                resultCanvas.drawBitmap(diagonalMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing diagonal transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the diagonal mask canvas
                diagonalMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the diagonal shape
                diagonalPaint.color = Color.WHITE
                diagonalPaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()

                // Calculate how far the diagonal line should be from the origin (bottom-left)
                val maxDiagonalLength = width + height // The maximum distance the diagonal line will travel
                val offset = -height + (progressCurved * maxDiagonalLength)

                // Create a path for the revealed portion
                val path = Path()
                path.moveTo(0f, height) // Start at bottom-left

                if (offset <= 0) {
                    // Diagonal line hasn't reached the left edge yet
                    path.lineTo(0f, height + offset) // Up the left edge (partially)
                    path.close()
                } else if (offset <= height) {
                    // Diagonal line is on the left edge
                    path.lineTo(0f, height - offset) // Up the left edge
                    path.lineTo(offset, height) // To the right along the bottom
                    path.close()
                } else if (offset <= width) {
                    // Diagonal line is on the bottom edge
                    path.lineTo(0f, 0f) // Up to top-left
                    path.lineTo(offset - height, 0f) // Along the top
                    path.lineTo(offset, height) // To the diagonal
                    path.close()
                } else if (offset <= width + height) {
                    // Diagonal line is on the right edge
                    path.lineTo(0f, 0f) // Up to top-left
                    path.lineTo(width, 0f) // Along the top to top-right
                    path.lineTo(width, offset - width) // Down the right edge
                    path.lineTo(width - (offset - width - height), height) // To the diagonal
                    path.close()
                } else {
                    // We've completed the wipe
                    path.lineTo(0f, 0f) // Up to top-left
                    path.lineTo(width, 0f) // Along the top to top-right
                    path.lineTo(width, height) // Down to bottom-right
                    path.close()
                }

                diagonalMaskCanvas.drawPath(path, diagonalPaint)

                // Create a copy of the new image, masked by the diagonal shape
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
                    resultCanvas.drawBitmap(diagonalMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in diagonal transition", e)
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
                    diagonalMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up diagonal transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    diagonalMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled diagonal transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
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
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val circleMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val circleMaskCanvas = Canvas(circleMaskBitmap)
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the circle-shaped cutout of the new image
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

        // Maximum radius - diagonal from center to corner
        val maxRadius = Math.sqrt(
            Math.pow(centerX.toDouble(), 2.0) +
                    Math.pow(centerY.toDouble(), 2.0)
        ).toFloat()

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Prepare the first frame before making anything visible
        try {
            // Clear the circle mask canvas
            circleMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial circle size - just starting to appear
            val initialProgress = 0.01f

            // Draw initial circle shape (tiny circle)
            circlePaint.color = Color.WHITE
            circlePaint.style = Paint.Style.FILL

            val initialRadius = maxRadius * initialProgress

            circleMaskCanvas.drawCircle(centerX, centerY, initialRadius, circlePaint)

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
                resultCanvas.drawBitmap(circleMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing circle transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the circle mask canvas
                circleMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the circle shape
                circlePaint.color = Color.WHITE
                circlePaint.style = Paint.Style.FILL

                val currentRadius = maxRadius * progressCurved

                circleMaskCanvas.drawCircle(centerX, centerY, currentRadius, circlePaint)

                // Create a copy of the new image, masked by the circle shape
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
                    resultCanvas.drawBitmap(circleMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in circle transition", e)
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
                    circleMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up circle transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    circleMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled circle transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
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
        // Set up overlay view with the new image but keep it invisible initially
        views.overlayView.apply {
            setImageDrawable(resource)
            visibility = View.INVISIBLE
            alpha = 1f
        }

        // Create a bitmap for the reveal effect that will cover the new image
        val windMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val windMaskCanvas = Canvas(windMaskBitmap)
        val windPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the wind-shaped cutout of the new image
        val revealImageView = ImageView(context)
        revealImageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        revealImageView.scaleType = views.overlayView.scaleType // Match the scale type of the main image
        revealImageView.visibility = View.INVISIBLE // Start as INVISIBLE

        // Add the reveal view to the container, on top of both primary and overlay views
        (views.container as ViewGroup).addView(revealImageView)

        // Wind configuration
        val lineCount = 20 // Number of wind lines
        val random = Random(System.currentTimeMillis())

        // Create animator for the transition
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = transitionDuration * 2
        animator.interpolator = AccelerateInterpolator(0.8f)

        // Generate the wind lines with random heights and positions
        val windLines = ArrayList<Triple<Float, Float, Float>>(lineCount)
        val height = views.container.height.toFloat()

        for (i in 0 until lineCount) {
            val lineHeight = height * (0.02f + random.nextFloat() * 0.05f) // 2-7% of screen height
            val yPosition = random.nextFloat() * (height - lineHeight)
            val speedFactor = 0.7f + random.nextFloat() * 0.6f // Random speed variations

            windLines.add(Triple(lineHeight, yPosition, speedFactor))
        }

        // Prepare the first frame before making anything visible
        try {
            // Clear the wind mask canvas
            windMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial wind position - just starting to appear
            val initialProgress = 0.01f

            // Draw initial wind shape (tiny lines)
            windPaint.color = Color.WHITE
            windPaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()

            // Draw tiny initial wind lines
            for (i in 0 until 2) { // Just show a couple of lines at the start
                if (i < windLines.size) {
                    val (lineHeight, yPosition, speedFactor) = windLines[i]

                    // Start with a small section of the line
                    val lineWidth = width * initialProgress * speedFactor

                    windMaskCanvas.drawRect(0f, yPosition, lineWidth, yPosition + lineHeight, windPaint)
                }
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
                resultCanvas.drawBitmap(windMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing wind transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the wind mask canvas
                windMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the wind shape
                windPaint.color = Color.WHITE
                windPaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()

                // Determine how many lines to show based on progress
                val visibleLines = (windLines.size * progressCurved * 1.5f).toInt().coerceAtMost(windLines.size)

                for (i in 0 until visibleLines) {
                    val (lineHeight, yPosition, speedFactor) = windLines[i]

                    // Calculate line specific progress - lines appear at different times
                    val lineDelay = i.toFloat() / windLines.size * 0.3f // Stagger the appearance
                    val lineProgress = Math.max(0f, progressCurved - lineDelay)

                    // Calculate line width based on progress
                    val lineWidth = width * lineProgress * speedFactor * 1.5f // Multiply by 1.5 to ensure lines reach the end

                    // Only draw if there's something to show
                    if (lineWidth > 0) {
                        windMaskCanvas.drawRect(0f, yPosition, Math.min(lineWidth, width), yPosition + lineHeight, windPaint)
                    }
                }

                // Create a copy of the new image, masked by the wind shape
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
                    resultCanvas.drawBitmap(windMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in wind transition", e)
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
                    windMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up wind transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    windMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled wind transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
    }

    private fun performWipeTransition(
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
        val wipeMaskBitmap = Bitmap.createBitmap(
            views.container.width,
            views.container.height,
            Bitmap.Config.ARGB_8888
        )
        val wipeMaskCanvas = Canvas(wipeMaskBitmap)
        val wipePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Create a temporary ImageView to hold the wipe-shaped cutout of the new image
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
            // Clear the wipe mask canvas
            wipeMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate initial wipe position - just starting to appear
            val initialProgress = 0.01f

            // Draw initial wipe shape (small section from left)
            wipePaint.color = Color.WHITE
            wipePaint.style = Paint.Style.FILL

            val width = views.container.width.toFloat()
            val height = views.container.height.toFloat()
            val initialWidth = width * initialProgress

            // Draw a rectangle from the left edge
            wipeMaskCanvas.drawRect(0f, 0f, initialWidth, height, wipePaint)

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
                resultCanvas.drawBitmap(wipeMaskBitmap, 0f, 0f, maskPaint)

                revealImageView.setImageBitmap(resultBitmap)
            } else {
                revealImageView.setImageDrawable(resource)
                revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
            }

            // Now make revealImageView visible before animation starts
            revealImageView.visibility = View.VISIBLE

        } catch (e: Exception) {
            Log.e(TAG, "Error preparing wipe transition", e)
        }

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float

            try {
                // Clear the wipe mask canvas
                wipeMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // Use a curved progress for smoother animation
                val progressCurved = Math.pow(progress.toDouble(), 0.8).toFloat()

                // Draw the wipe shape
                wipePaint.color = Color.WHITE
                wipePaint.style = Paint.Style.FILL

                val width = views.container.width.toFloat()
                val height = views.container.height.toFloat()
                val currentWidth = width * progressCurved

                // Add a smooth edge to the wipe with a gradient
                val edgeWidth = width * 0.05f // 5% of the screen width

                // Main rectangle
                wipeMaskCanvas.drawRect(0f, 0f, currentWidth, height, wipePaint)

                // Gradient edge
                val gradientPaint = Paint()
                val colors = intArrayOf(Color.WHITE, Color.TRANSPARENT)
                val positions = floatArrayOf(0f, 1f)

                gradientPaint.shader = LinearGradient(
                    currentWidth, 0f,
                    currentWidth + edgeWidth, 0f,
                    colors, positions,
                    Shader.TileMode.CLAMP
                )

                wipeMaskCanvas.drawRect(currentWidth, 0f, currentWidth + edgeWidth, height, gradientPaint)

                // Create a copy of the new image, masked by the wipe shape
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
                    resultCanvas.drawBitmap(wipeMaskBitmap, 0f, 0f, maskPaint)

                    revealImageView.setImageBitmap(resultBitmap)
                } else {
                    revealImageView.setImageDrawable(resource)
                    revealImageView.setColorFilter(Color.BLACK, PorterDuff.Mode.DST_IN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in wipe transition", e)
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
                    wipeMaskBitmap.recycle()

                    // Complete the transition
                    callback.onTransitionCompleted(resource, nextIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up wipe transition", e)
                    callback.onTransitionCompleted(resource, nextIndex)
                }
            }

            override fun onAnimationCancel(animation: Animator) {
                try {
                    // Clean up if animation is cancelled
                    (views.container as ViewGroup).removeView(revealImageView)
                    wipeMaskBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up cancelled wipe transition", e)
                }
            }
        })

        // Start the animation
        animator.start()
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
