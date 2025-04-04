package com.photostreamr.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient  // Fixed import
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.preference.PreferenceManager
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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
        const val MODE_KEN_BURNS = "kenburns" // Ken Burns effect

        // Enhancement effects
        const val EFFECT_NONE = "none"
        const val EFFECT_BOKEH = "bokeh"
        const val EFFECT_VIGNETTE = "vignette"
        const val EFFECT_AMBIENT = "ambient"

        // Constants for effects
        private const val BOKEH_BLUR_RADIUS = 20f
        private const val VIGNETTE_ALPHA = 90 // 0-255
        private const val DEFAULT_PAN_DURATION = 5000L // 20 seconds
        private const val KEN_BURNS_MIN_SCALE = 1.0f
        private const val KEN_BURNS_MAX_SCALE = 1.2f
        private const val KEN_BURNS_DURATION = 5000L // 30 seconds
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

    /**
     * Apply the selected display mode to the image
     *
     * @param views The views to apply the scaling to
     * @param drawable The drawable to display
     * @param mode The display mode to apply
     * @param enhancementEffect Optional visual enhancement to apply
     * @param callback Optional callback for when scaling is complete
     */
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
            // Apply the main scaling mode
            when (mode) {
                MODE_FIT -> applyFitMode(views, drawable, enhancementEffect, callback)
                MODE_FILL -> applyFillMode(views, drawable, enhancementEffect, callback)
                MODE_ORIGINAL -> applyOriginalMode(views, drawable, enhancementEffect, callback)
                MODE_SMART -> applySmartMode(views, drawable, enhancementEffect, callback)
                MODE_PAN -> applyPanMode(views, drawable, enhancementEffect, callback)
                MODE_KEN_BURNS -> applyKenBurnsEffect(views, drawable, enhancementEffect, callback)
                else -> applyFitMode(views, drawable, enhancementEffect, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying scaling mode", e)
            // Fallback to fit mode in case of error
            applyFitMode(views, drawable, EFFECT_NONE, callback)
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

        callback?.onScalingEffectComplete(true)
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
     * Apply the Ken Burns effect (slow pan and zoom)
     */
    private fun applyKenBurnsEffect(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String,
        callback: ScalingEffectCallback?
    ) {
        val imageView = views.imageView

        // Reset any previous transformations
        resetImageView(imageView)

        // Start with fill mode
        imageView.scaleType = ImageView.ScaleType.MATRIX
        val matrix = Matrix()

        // Get image and screen dimensions
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        val screenWidth = views.container.width.toFloat()
        val screenHeight = views.container.height.toFloat()

        // Calculate scale to fill the screen
        val scale = max(screenWidth / drawableWidth, screenHeight / drawableHeight)

        // Apply the scale
        matrix.setScale(scale, scale)

        // Center the image
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale
        matrix.postTranslate(
            (screenWidth - scaledWidth) / 2,
            (screenHeight - scaledHeight) / 2
        )

        // Set initial matrix
        imageView.imageMatrix = matrix
        imageView.setImageDrawable(drawable)

        // Apply enhancement effect if specified
        applyEnhancementEffect(views, drawable, enhancementEffect)

        // Create random start and end points for the effect
        val random = Random.Default
        val startScale = KEN_BURNS_MIN_SCALE
        val endScale = KEN_BURNS_MAX_SCALE

        // Create Ken Burns animation
        val scaleUp = ValueAnimator.ofFloat(startScale, endScale)
        scaleUp.duration = KEN_BURNS_DURATION
        scaleUp.interpolator = LinearInterpolator()

        // Random pan values
        val startPanX = if (scaledWidth > screenWidth) -random.nextFloat() * (scaledWidth - screenWidth) else 0f
        val startPanY = if (scaledHeight > screenHeight) -random.nextFloat() * (scaledHeight - screenHeight) else 0f
        val endPanX = if (scaledWidth > screenWidth) -random.nextFloat() * (scaledWidth - screenWidth) else 0f
        val endPanY = if (scaledHeight > screenHeight) -random.nextFloat() * (scaledHeight - screenHeight) else 0f

        // Animate scale and pan
        scaleUp.addUpdateListener { animator ->
            val progress = animator.animatedValue as Float
            val currScale = startScale + (progress - startScale) * (endScale - startScale)
            val currPanX = startPanX + (progress - startScale) / (endScale - startScale) * (endPanX - startPanX)
            val currPanY = startPanY + (progress - startScale) / (endScale - startScale) * (endPanY - startPanY)

            val newMatrix = Matrix()
            newMatrix.setScale(scale * currScale, scale * currScale, screenWidth / 2, screenHeight / 2)
            newMatrix.postTranslate(
                (screenWidth - scaledWidth * currScale) / 2 + currPanX,
                (screenHeight - scaledHeight * currScale) / 2 + currPanY
            )
            imageView.imageMatrix = newMatrix
        }

        // Create the reverse animation
        val scaleDown = ValueAnimator.ofFloat(endScale, startScale)
        scaleDown.duration = KEN_BURNS_DURATION
        scaleDown.interpolator = LinearInterpolator()
        scaleDown.addUpdateListener { animator ->
            val progress = (animator.animatedValue as Float - startScale) / (endScale - startScale)
            val currScale = endScale - progress * (endScale - startScale)
            val currPanX = endPanX - progress * (endPanX - startPanX)
            val currPanY = endPanY - progress * (endPanY - startPanY)

            val newMatrix = Matrix()
            newMatrix.setScale(scale * currScale, scale * currScale, screenWidth / 2, screenHeight / 2)
            newMatrix.postTranslate(
                (screenWidth - scaledWidth * currScale) / 2 + currPanX,
                (screenHeight - scaledHeight * currScale) / 2 + currPanY
            )
            imageView.imageMatrix = newMatrix
        }

        // Create animation set
        val animatorSet = AnimatorSet()
        animatorSet.playSequentially(scaleUp, scaleDown)

        // Make it repeat
        val kenBurnsRunnable = object : Runnable {
            override fun run() {
                animatorSet.start()
                currentAnimatorSet = animatorSet
                mainHandler.postDelayed(this, KEN_BURNS_DURATION * 2)
            }
        }

        // Start animation
        mainHandler.post(kenBurnsRunnable)
        currentAnimationRunnable = kenBurnsRunnable

        callback?.onScalingEffectComplete(true)
    }

    /**
     * Apply visual enhancement effects to the image
     */
    private fun applyEnhancementEffect(
        views: ScalingViews,
        drawable: Drawable,
        enhancementEffect: String
    ) {
        when (enhancementEffect) {
            EFFECT_BOKEH -> applyBokehEffect(views, drawable)
            EFFECT_VIGNETTE -> applyVignetteEffect(views, drawable)
            EFFECT_AMBIENT -> applyAmbientEffect(views, drawable)
            else -> {
                // Clear any background effects
                views.backgroundView?.setImageDrawable(null)
                views.backgroundView?.visibility = View.GONE
            }
        }
    }

    /**
     * Apply a bokeh (blur) effect to the background of the image
     * This keeps the center sharp and blurs the edges
     */
    private fun applyBokehEffect(views: ScalingViews, drawable: Drawable) {
        val backgroundView = views.backgroundView ?: return

        try {
            // Create a blurred version of the image
            val bitmap = drawable.toBitmap()
            val blurredBitmap = blurBitmap(bitmap, BOKEH_BLUR_RADIUS)

            // Set the blurred image as background
            backgroundView.scaleType = ImageView.ScaleType.CENTER_CROP
            backgroundView.setImageBitmap(blurredBitmap)
            backgroundView.visibility = View.VISIBLE

            // Make sure the blurred background is behind the main image
            backgroundView.z = views.imageView.z - 1
        } catch (e: Exception) {
            Log.e(TAG, "Error applying bokeh effect", e)
            backgroundView.visibility = View.GONE
        }
    }

    /**
     * Apply a vignette effect (darkened edges) to the image
     */
    private fun applyVignetteEffect(views: ScalingViews, drawable: Drawable) {
        val backgroundView = views.backgroundView ?: return

        try {
            // Create a drawable with radial gradient for vignette
            val colors = intArrayOf(Color.TRANSPARENT, Color.BLACK)
            val gradientDrawable = RadialGradient(  // Fixed reference
                0.5f, 0.5f, 0.7f,
                colors,
                floatArrayOf(0.7f, 1.0f),
                Shader.TileMode.CLAMP
            )

            // Create a bitmap with the vignette
            val bitmap = Bitmap.createBitmap(
                views.container.width,
                views.container.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.shader = gradientDrawable
            paint.alpha = VIGNETTE_ALPHA

            // Draw the vignette
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)

            // Set as overlay
            backgroundView.scaleType = ImageView.ScaleType.FIT_XY
            backgroundView.setImageBitmap(bitmap)
            backgroundView.visibility = View.VISIBLE

            // Make sure the vignette is on top of the main image
            backgroundView.z = views.imageView.z + 1
        } catch (e: Exception) {
            Log.e(TAG, "Error applying vignette effect", e)
            backgroundView.visibility = View.GONE
        }
    }

    /**
     * Apply an ambient effect by extending the edge colors beyond the photo
     */
    private fun applyAmbientEffect(views: ScalingViews, drawable: Drawable) {
        val backgroundView = views.backgroundView ?: return

        try {
            // Get the bitmap from drawable
            val bitmap = drawable.toBitmap()

            // Create a blurred, scaled version for the background
            val blurredBitmap = blurBitmap(bitmap, 25f)
            val scaledBitmap = Bitmap.createScaledBitmap(
                blurredBitmap,
                (views.container.width * 1.5).toInt(),
                (views.container.height * 1.5).toInt(),
                true
            )

            // Set as background
            backgroundView.scaleType = ImageView.ScaleType.CENTER_CROP
            backgroundView.setImageBitmap(scaledBitmap)
            backgroundView.visibility = View.VISIBLE

            // Ensure background is behind the main image
            backgroundView.z = views.imageView.z - 1

            // Add a slight zoom animation to the background
            val scaleX = ObjectAnimator.ofFloat(backgroundView, "scaleX", 1.0f, 1.1f)
            val scaleY = ObjectAnimator.ofFloat(backgroundView, "scaleY", 1.0f, 1.1f)

            // Set repeat mode on the individual animators
            scaleX.repeatMode = ValueAnimator.REVERSE  // Fixed reference
            scaleX.repeatCount = ValueAnimator.INFINITE  // Fixed reference
            scaleY.repeatMode = ValueAnimator.REVERSE  // Fixed reference
            scaleY.repeatCount = ValueAnimator.INFINITE  // Fixed reference

            val animatorSet = AnimatorSet()
            animatorSet.playTogether(scaleX, scaleY)
            animatorSet.duration = 30000
            animatorSet.interpolator = LinearInterpolator()

            animatorSet.start()
            currentAnimatorSet = animatorSet
        } catch (e: Exception) {
            Log.e(TAG, "Error applying ambient effect", e)
            backgroundView.visibility = View.GONE
        }
    }

    /**
     * Helper function to blur a bitmap
     */
    private fun blurBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        if (radius <= 0) return bitmap

        var renderScript: RenderScript? = null
        var allocationIn: Allocation? = null
        var allocationOut: Allocation? = null
        var blurScript: ScriptIntrinsicBlur? = null

        try {
            // Initialize RenderScript
            renderScript = RenderScript.create(context)

            // Create output bitmap
            val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)

            // Create allocations
            allocationIn = Allocation.createFromBitmap(renderScript, bitmap)
            allocationOut = Allocation.createFromBitmap(renderScript, outputBitmap)

            // Create blur script and set parameters
            blurScript = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
            blurScript.setRadius(min(radius, 25f)) // Maximum radius is 25
            blurScript.setInput(allocationIn)
            blurScript.forEach(allocationOut)

            // Copy the result back to the output bitmap
            allocationOut.copyTo(outputBitmap)

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
                renderScript?.destroy()
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