package com.photostreamr.ui

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.preference.PreferenceManager
import kotlin.math.max
import kotlin.math.min

/**
 * Provides different scaling and display effects for photos, especially helpful
 * for displaying landscape photos on portrait screens (and vice versa).
 */
class PhotoScalingEffects(private val context: Context) {

    companion object {
        private const val TAG = "PhotoScalingEffects"

        // Display modes
        const val MODE_FIT = "fit"       // Fit entire photo within screen
        const val MODE_FILL = "fill"     // Fill screen (may crop sides/top/bottom)
        const val MODE_ORIGINAL = "original" // Original size
        const val MODE_SMART = "smart"   // Smart scaling based on content
        const val MODE_PAN = "pan"       // Pan effect

        // Enhancement effects
        const val EFFECT_NONE = "none"
        const val EFFECT_BOKEH = "bokeh"

        // Constants for effects
        private const val BOKEH_BLUR_RADIUS = 20f
        private const val DEFAULT_PAN_DURATION = 5000L // 20 seconds
    }

    /**
     * Class to hold all the views needed for scaling effects
     */
    data class ScalingViews(
        val imageView: ImageView,
        val container: ViewGroup,
        val backgroundView: ImageView? = null  // Optional view for background effects
    )

    /**
     * Interface for callback when scaling effect is complete
     */
    interface ScalingEffectCallback {
        fun onScalingEffectComplete(success: Boolean)
    }

    // Handler for animations
    private val mainHandler = Handler(Looper.getMainLooper())

    // Current running animations
    private var currentAnimatorSet: AnimatorSet? = null
    private var currentAnimationRunnable: Runnable? = null

    // RenderScript for blur effects
    private var renderScript: RenderScript? = null

    init {
        // Initialize RenderScript once for better performance
        try {
            renderScript = RenderScript.create(context)
            Log.d(TAG, "RenderScript initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing RenderScript", e)
        }
    }

    fun applyScalingMode(
        views: ScalingViews,
        drawable: Drawable,
        mode: String = getDefaultMode(),
        enhancementEffect: String = EFFECT_NONE,
        callback: ScalingEffectCallback? = null
    ) {
        Log.d(TAG, "Applying scaling mode: $mode with effect: $enhancementEffect")

        // Cancel any existing animations
        cleanupAnimations()

        try {
            // First apply the scaling based on the mode
            when (mode) {
                MODE_FIT -> {
                    resetImageView(views.imageView)
                    views.imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    views.imageView.setImageDrawable(drawable)
                    Log.d(TAG, "FIT mode applied")
                }
                MODE_FILL -> {
                    resetImageView(views.imageView)
                    views.imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    views.imageView.setImageDrawable(drawable)
                    Log.d(TAG, "FILL mode applied")
                }
                MODE_ORIGINAL -> {
                    resetImageView(views.imageView)
                    views.imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    views.imageView.setImageDrawable(drawable)
                    Log.d(TAG, "ORIGINAL mode applied")
                }
                MODE_SMART -> {
                    resetImageView(views.imageView)
                    // Check if the image is landscape or portrait
                    val isLandscapeImage = drawable.intrinsicWidth > drawable.intrinsicHeight
                    val isLandscapeScreen = views.container.width > views.container.height

                    if (isLandscapeImage == isLandscapeScreen) {
                        // Same orientation - use fill mode
                        views.imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        // Different orientation - use fit mode
                        views.imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    views.imageView.setImageDrawable(drawable)
                    Log.d(TAG, "SMART mode applied with scale type: ${views.imageView.scaleType}")
                }
                MODE_PAN -> {
                    applyPanMode(views, drawable, enhancementEffect, callback)
                    Log.d(TAG, "PAN mode applied")
                    return // Pan mode handles the callback
                }
                else -> {
                    resetImageView(views.imageView)
                    views.imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                    views.imageView.setImageDrawable(drawable)
                    Log.d(TAG, "Default mode applied as fallback")
                }
            }

            // Then apply enhancement effect separately
            if (enhancementEffect != EFFECT_NONE) {
                applyEnhancementEffect(views, drawable, enhancementEffect)
            }

            // Ensure the view is visible
            views.imageView.visibility = View.VISIBLE

            // Set proper elevation to ensure bokeh effect works
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                views.imageView.elevation = 2f
                views.backgroundView?.elevation = 1f
            }

            callback?.onScalingEffectComplete(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying scaling mode", e)
            // Fallback
            try {
                resetImageView(views.imageView)
                views.imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                views.imageView.setImageDrawable(drawable)
                callback?.onScalingEffectComplete(false)
            } catch (e2: Exception) {
                Log.e(TAG, "Error applying fallback scaling", e2)
                callback?.onScalingEffectComplete(false)
            }
        }
    }

    /**
     * Scale image to fit entirely within the container while preserving aspect ratio
     */
    private fun applyFitMode(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String,
        callback: ScalingEffectCallback?
    ) {
        val imageView = views.imageView

        // Reset any previous transformations
        resetImageView(imageView)

        // Set the scaling type to fit center
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.setImageDrawable(drawable)

        // Apply enhancement effect if specified
        applyEnhancementEffect(views, drawable, enhancementEffect)

        // Make sure the image view is visible and properly elevated
        imageView.visibility = View.VISIBLE
        imageView.elevation = 2f
        views.backgroundView?.elevation = 1f

        callback?.onScalingEffectComplete(true)
    }

    /**
     * Scale image to fill the container while preserving aspect ratio
     * This may result in parts of the image being cropped
     */
    private fun applyFillMode(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String,
        callback: ScalingEffectCallback?
    ) {
        val imageView = views.imageView

        // Reset any previous transformations
        resetImageView(imageView)

        // Set the scaling type to center crop
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setImageDrawable(drawable)

        // Apply enhancement effect if specified
        applyEnhancementEffect(views, drawable, enhancementEffect)

        // Make sure the image view is visible and properly elevated
        imageView.visibility = View.VISIBLE
        imageView.elevation = 2f
        views.backgroundView?.elevation = 1f

        callback?.onScalingEffectComplete(true)
    }

    /**
     * Display image at its original size (may require scrolling)
     */
    private fun applyOriginalMode(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String,
        callback: ScalingEffectCallback?
    ) {
        val imageView = views.imageView

        // Reset any previous transformations
        resetImageView(imageView)

        // Set the scaling type to center inside
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.setImageDrawable(drawable)

        // Apply enhancement effect if specified
        applyEnhancementEffect(views, drawable, enhancementEffect)

        // Make sure the image view is visible and properly elevated
        imageView.visibility = View.VISIBLE
        imageView.elevation = 2f
        views.backgroundView?.elevation = 1f

        callback?.onScalingEffectComplete(true)
    }

    /**
     * Smart mode tries to determine the best way to display the image
     * based on its content and orientation
     */
    private fun applySmartMode(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String,
        callback: ScalingEffectCallback?
    ) {
        val imageView = views.imageView

        // Check if the image is landscape or portrait
        val isLandscapeImage = drawable.intrinsicWidth > drawable.intrinsicHeight
        val isLandscapeScreen = views.container.width > views.container.height

        // Reset any previous transformations
        resetImageView(imageView)

        if (isLandscapeImage == isLandscapeScreen) {
            // Same orientation - use fill mode
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            // Different orientation - use fit mode
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        imageView.setImageDrawable(drawable)

        // Apply enhancement effect if specified
        applyEnhancementEffect(views, drawable, enhancementEffect)

        // Make sure the image view is visible and properly elevated
        imageView.visibility = View.VISIBLE
        imageView.elevation = 2f
        views.backgroundView?.elevation = 1f

        callback?.onScalingEffectComplete(true)
    }

    /**
     * Apply pan effect to the image
     */
    private fun applyPanEffect(imageView: ImageView, drawable: Drawable, container: ViewGroup) {
        Log.d(TAG, "Setting up pan effect with animation")

        // Pan effect uses matrix scaling
        imageView.scaleType = ImageView.ScaleType.MATRIX
        val matrix = Matrix()

        // Get image and screen dimensions
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        val screenWidth = container.width.toFloat()
        val screenHeight = container.height.toFloat()

        // Calculate scale to fill
        val scale = max(screenWidth / drawableWidth, screenHeight / drawableHeight)

        // Calculate the total width/height after scaling
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale

        // Set initial position - centered
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (screenWidth - scaledWidth) / 2,
            (screenHeight - scaledHeight) / 2
        )

        // Apply the initial matrix
        imageView.imageMatrix = matrix

        // Only animate if panning is needed (image is wider than screen after scaling)
        if (scaledWidth > screenWidth) {
            val panDuration = 5000L // 5 seconds for panning

            // Animate panning from left to right and back
            val animator = ValueAnimator.ofFloat(0f, 1f, 0f)
            animator.repeatMode = ValueAnimator.RESTART
            animator.repeatCount = ValueAnimator.INFINITE
            animator.duration = panDuration * 2
            animator.interpolator = LinearInterpolator()

            // Maximum pan distance
            val maxPanX = (scaledWidth - screenWidth)

            animator.addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                val panX = if (progress <= 0.5f) {
                    // First half: 0 -> maxPanX
                    progress * 2 * maxPanX
                } else {
                    // Second half: maxPanX -> 0
                    (1f - (progress - 0.5f) * 2) * maxPanX
                }

                // Create new matrix for this animation frame
                val panMatrix = Matrix(matrix)
                panMatrix.postTranslate(-panX, 0f)
                imageView.imageMatrix = panMatrix
            }

            // Start the animation
            animator.start()

            // Store animator reference to cancel it later if needed
            imageView.tag = animator

            Log.d(TAG, "Started panning animation for width $scaledWidth > $screenWidth")
        } else {
            Log.d(TAG, "No panning needed, image fits screen width: $scaledWidth <= $screenWidth")
        }
    }


    /**
     * Pan mode applies a slow panning effect to show the entire image
     * This is especially useful for landscape images on portrait screens
     */
    private fun applyPanMode(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String,
        callback: ScalingEffectCallback?
    ) {
        val imageView = views.imageView

        // Reset any previous transformations
        resetImageView(imageView)

        // Initially scale to fill
        imageView.scaleType = ImageView.ScaleType.MATRIX
        val matrix = Matrix()

        // Get image and screen dimensions
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        val screenWidth = views.container.width.toFloat()
        val screenHeight = views.container.height.toFloat()

        // Calculate scale to fill the height
        val scale = max(screenWidth / drawableWidth, screenHeight / drawableHeight)

        // Calculate the total width/height after scaling
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale

        // Set initial position - centered
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (screenWidth - scaledWidth) / 2,
            (screenHeight - scaledHeight) / 2
        )
        imageView.imageMatrix = matrix
        imageView.setImageDrawable(drawable)

        // Apply enhancement effect if specified
        applyEnhancementEffect(views, drawable, enhancementEffect)

        // Make sure the image view is visible and properly elevated
        imageView.visibility = View.VISIBLE
        imageView.elevation = 2f
        views.backgroundView?.elevation = 1f

        // Only animate if panning is needed (image is wider than screen after scaling)
        if (scaledWidth > screenWidth) {
            // Get the maximum pan amount
            val maxPanX = (scaledWidth - screenWidth)

            // Start from left to right
            val leftToRight = ValueAnimator.ofFloat(0f, maxPanX)
            leftToRight.duration = DEFAULT_PAN_DURATION
            leftToRight.interpolator = LinearInterpolator()
            leftToRight.addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                val panMatrix = Matrix(matrix)
                panMatrix.postTranslate(-value, 0f)
                imageView.imageMatrix = panMatrix
            }

            // Right to left
            val rightToLeft = ValueAnimator.ofFloat(maxPanX, 0f)
            rightToLeft.duration = DEFAULT_PAN_DURATION
            rightToLeft.interpolator = LinearInterpolator()
            rightToLeft.addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                val panMatrix = Matrix(matrix)
                panMatrix.postTranslate(-value, 0f)
                imageView.imageMatrix = panMatrix
            }

            // Create animator set to run one after the other
            val animatorSet = AnimatorSet()
            animatorSet.playSequentially(leftToRight, rightToLeft)

            // Make it repeat
            val panRunnable = object : Runnable {
                override fun run() {
                    animatorSet.start()
                    currentAnimatorSet = animatorSet
                    mainHandler.postDelayed(this, DEFAULT_PAN_DURATION * 2)
                }
            }

            // Start animation
            mainHandler.post(panRunnable)
            currentAnimationRunnable = panRunnable
        }

        callback?.onScalingEffectComplete(true)
    }

    /**
     * Apply visual enhancement effects to the image
     */
    fun applyEnhancementEffect(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String
    ) {
        Log.d(TAG, "Applying enhancement effect: $enhancementEffect")
        Log.d(TAG, "Background view available: ${views.backgroundView != null}")

        when (enhancementEffect) {
            EFFECT_BOKEH -> {
                applyBokehEffect(views, drawable)
                Log.d(TAG, "Bokeh effect applied")
            }
            else -> {
                // Clear any background effects
                views.backgroundView?.setImageDrawable(null)
                views.backgroundView?.visibility = View.GONE
                Log.d(TAG, "No enhancement effect applied, cleared background")
            }
        }
    }

    /**
     * Apply a bokeh (blur) effect to the background of the image
     * This keeps the center sharp and blurs the edges
     */
    /**
     * Apply a bokeh (blur) effect to the background of the image
     * This keeps the center sharp and blurs the edges
     */
    private fun applyBokehEffect(views: ScalingViews, drawable: Drawable) {
        val backgroundView = views.backgroundView ?: return

        try {
            // Create a blurred version of the image
            val bitmap = drawable.toBitmap()
            Log.d(TAG, "Original bitmap size: ${bitmap.width}x${bitmap.height}")

            val blurredBitmap = blurBitmap(bitmap, BOKEH_BLUR_RADIUS)
            Log.d(TAG, "Blurred bitmap created successfully: ${blurredBitmap != null}")

            // IMPORTANT: Use the SAME scale type as the main image for the background
            // Instead of hardcoding CENTER_CROP
            backgroundView.scaleType = views.imageView.scaleType

            // For MATRIX scale type, need to also copy the matrix
            if (views.imageView.scaleType == ImageView.ScaleType.MATRIX &&
                views.imageView.imageMatrix != null) {
                backgroundView.imageMatrix = Matrix(views.imageView.imageMatrix)
            }

            backgroundView.setImageBitmap(blurredBitmap)
            backgroundView.visibility = View.VISIBLE

            // Make sure z-ordering is correct
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                backgroundView.elevation = 1f
                views.imageView.elevation = 2f
                Log.d(TAG, "Set elevations: main image: ${views.imageView.elevation}, background: ${backgroundView.elevation}")
            } else {
                // For older Android versions, use bringToFront
                views.imageView.bringToFront()
                Log.d(TAG, "Using bringToFront() for z-ordering on older Android")
            }

            Log.d(TAG, "Bokeh effect applied with scale type: ${views.imageView.scaleType}")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying bokeh effect", e)
            backgroundView.visibility = View.GONE
        }
    }

    /**
     * Helper function to blur a bitmap
     */
    private fun blurBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        if (radius <= 0) return bitmap

        var localRenderScript = renderScript
        var allocationIn: Allocation? = null
        var allocationOut: Allocation? = null
        var blurScript: ScriptIntrinsicBlur? = null

        try {
            // Initialize RenderScript if needed
            if (localRenderScript == null) {
                localRenderScript = RenderScript.create(context)
                renderScript = localRenderScript
                Log.d(TAG, "Created new RenderScript instance for blurring")
            }

            // Create output bitmap
            val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

            // Create allocations
            allocationIn = Allocation.createFromBitmap(localRenderScript, bitmap)
            allocationOut = Allocation.createFromBitmap(localRenderScript, outputBitmap)

            // Create blur script and set parameters
            blurScript = ScriptIntrinsicBlur.create(localRenderScript, Element.U8_4(localRenderScript))
            blurScript.setRadius(min(radius, 25f)) // Maximum radius is 25
            blurScript.setInput(allocationIn)
            blurScript.forEach(allocationOut)

            // Copy the result back to the output bitmap
            allocationOut.copyTo(outputBitmap)

            Log.d(TAG, "Blur applied successfully with radius: $radius")
            return outputBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error blurring bitmap", e)
            return bitmap
        } finally {
            // Clean up resources to prevent leaks
            try {
                blurScript?.destroy()
                allocationIn?.destroy()
                allocationOut?.destroy()
                // Don't destroy renderScript here as we'll reuse it
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up RenderScript resources", e)
            }
        }
    }

    /**
     * Reset image view to default state
     */
    private fun resetImageView(imageView: ImageView) {
        imageView.clearAnimation()
        imageView.animate().cancel()
        imageView.alpha = 1.0f
        imageView.scaleX = 1.0f
        imageView.scaleY = 1.0f
        imageView.translationX = 0f
        imageView.translationY = 0f
        imageView.rotation = 0f
        imageView.rotationX = 0f
        imageView.rotationY = 0f
    }

    /**
     * Clean up any ongoing animations
     */
    fun cleanupAnimations() {
        currentAnimatorSet?.cancel()
        currentAnimatorSet = null

        currentAnimationRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        currentAnimationRunnable = null
    }

    /**
     * Release resources when done
     */
    fun release() {
        cleanupAnimations()

        // Clean up RenderScript - no need to check if it's null as destroy() handles that
        try {
            renderScript?.destroy()
            renderScript = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying RenderScript", e)
        }
    }

    /**
     * Get the default scaling mode from preferences
     */
    private fun getDefaultMode(): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString("photo_scale", MODE_FILL) ?: MODE_FILL
    }

    /**
     * Helper extension to convert Drawable to Bitmap
     */
    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable) {
            return this.bitmap
        }

        val width = if (this.intrinsicWidth > 0) this.intrinsicWidth else 1
        val height = if (this.intrinsicHeight > 0) this.intrinsicHeight else 1

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        this.setBounds(0, 0, canvas.width, canvas.height)
        this.draw(canvas)

        return bitmap
    }
}