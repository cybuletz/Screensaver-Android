package com.photostreamr.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import androidx.preference.PreferenceManager
import kotlin.math.max
import kotlin.math.min

/**
 * Simple helper class for photo scaling and effects
 */
class SimplePhotoEffects(private val context: Context) {

    companion object {
        private const val TAG = "SimplePhotoEffects"
        private const val BOKEH_BLUR_RADIUS = 20f
    }

    private var renderScript: RenderScript? = null

    init {
        try {
            renderScript = RenderScript.create(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize RenderScript", e)
        }
    }

    /**
     * Apply scaling and effects to the images
     */
    fun applyEffects(
        mainImage: ImageView,
        backgroundImage: ImageView,
        container: ViewGroup,
        drawable: Drawable,
        scaleMode: String,
        enableBokeh: Boolean
    ) {
        Log.d(TAG, "Applying effects - scale: $scaleMode, bokeh: $enableBokeh")

        try {
            // First, apply the scaling to the main image
            when (scaleMode) {
                "fit" -> {
                    mainImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    Log.d(TAG, "Applied FIT scaling")
                }
                "fill" -> {
                    mainImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    Log.d(TAG, "Applied FILL scaling")
                }
                "original" -> {
                    mainImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    Log.d(TAG, "Applied ORIGINAL scaling")
                }
                "smart" -> {
                    // Check if the image is landscape or portrait
                    val isLandscapeImage = drawable.intrinsicWidth > drawable.intrinsicHeight
                    val isLandscapeScreen = container.width > container.height

                    if (isLandscapeImage == isLandscapeScreen) {
                        // Same orientation - use fill mode
                        mainImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        // Different orientation - use fit mode
                        mainImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    Log.d(TAG, "Applied SMART scaling: ${mainImage.scaleType}")
                }
                "pan" -> {
                    applyPanEffect(mainImage, drawable, container)
                    Log.d(TAG, "Applied PAN scaling")
                }
                else -> {
                    mainImage.scaleType = ImageView.ScaleType.FIT_CENTER
                    Log.d(TAG, "Applied default FIT scaling")
                }
            }

            // Make sure the main image has the drawable
            mainImage.setImageDrawable(drawable)

            // Next, apply bokeh effect if enabled
            if (enableBokeh) {
                applyBokehEffect(backgroundImage, drawable)
                // Ensure proper visibility and z-ordering
                backgroundImage.visibility = android.view.View.VISIBLE
                mainImage.elevation = 2f
                backgroundImage.elevation = 1f
                Log.d(TAG, "Applied bokeh effect")
            } else {
                backgroundImage.visibility = android.view.View.GONE
                Log.d(TAG, "Bokeh effect disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying effects", e)
            // Fallback to basic display
            mainImage.scaleType = ImageView.ScaleType.FIT_CENTER
            mainImage.setImageDrawable(drawable)
            backgroundImage.visibility = android.view.View.GONE
        }
    }

    /**
     * Apply pan effect to the image
     */
    private fun applyPanEffect(imageView: ImageView, drawable: Drawable, container: ViewGroup) {
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

        // Apply the scaling
        matrix.setScale(scale, scale)

        // Center the image
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale
        matrix.postTranslate(
            (screenWidth - scaledWidth) / 2,
            (screenHeight - scaledHeight) / 2
        )

        // Apply the matrix
        imageView.imageMatrix = matrix
    }

    /**
     * Apply bokeh effect to the background image
     */
    private fun applyBokehEffect(backgroundView: ImageView, drawable: Drawable) {
        try {
            // Create a blurred version of the image
            val bitmap = drawable.toBitmap()
            Log.d(TAG, "Created bitmap for bokeh: ${bitmap.width}x${bitmap.height}")

            val blurredBitmap = blurBitmap(bitmap, BOKEH_BLUR_RADIUS)

            // Set the blurred image as background
            backgroundView.scaleType = ImageView.ScaleType.CENTER_CROP
            backgroundView.setImageBitmap(blurredBitmap)
            Log.d(TAG, "Bokeh effect applied to background")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying bokeh effect", e)
            backgroundView.visibility = android.view.View.GONE
        }
    }

    /**
     * Blur a bitmap using RenderScript
     */
    private fun blurBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        if (radius <= 0) return bitmap

        var localRenderScript = renderScript
        var allocationIn: Allocation? = null
        var allocationOut: Allocation? = null
        var blurScript: ScriptIntrinsicBlur? = null

        try {
            // Create a RenderScript context if needed
            if (localRenderScript == null) {
                localRenderScript = RenderScript.create(context)
                renderScript = localRenderScript
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
                // Don't destroy renderScript as we'll reuse it
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up RenderScript resources", e)
            }
        }
    }

    /**
     * Convert drawable to bitmap
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

    fun cleanupAnimations(imageView: ImageView) {
        try {
            // Cancel any pending animations
            val animator = imageView.tag as? ValueAnimator
            animator?.cancel()
            imageView.tag = null
            Log.d(TAG, "Cleaned up animations for image view")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up animations", e)
        }
    }

    /**
     * Release resources
     */
    fun release() {
        try {
            renderScript?.destroy()
            renderScript = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying RenderScript", e)
        }
    }
}