package com.photostreamr.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages resizing and letterboxing for photos, particularly handling landscape photos in portrait mode.
 */
@Singleton
class PhotoResizeManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "PhotoResizeManager"

        // Photo display modes
        const val DISPLAY_MODE_FILL = "fill"  // Use centerCrop - fills screen but may crop
        const val DISPLAY_MODE_FIT = "fit"    // Use fitCenter - no crop but may have letterbox

        // Letterbox fill modes
        const val LETTERBOX_MODE_BLACK = "black"
        const val LETTERBOX_MODE_BLUR = "blur"
        const val LETTERBOX_MODE_GRADIENT = "gradient"
        const val LETTERBOX_MODE_MIRROR = "mirror"

        // Default values
        const val DEFAULT_DISPLAY_MODE = DISPLAY_MODE_FILL
        const val DEFAULT_LETTERBOX_MODE = LETTERBOX_MODE_BLACK

        // Preference keys
        const val PREF_KEY_PHOTO_SCALE = "photo_scale"
        const val PREF_KEY_LETTERBOX_MODE = "letterbox_mode"
        const val PREF_KEY_BLUR_INTENSITY = "letterbox_blur_intensity"
        const val PREF_KEY_GRADIENT_OPACITY = "letterbox_gradient_opacity"

        // Constants for effects
        private const val DEFAULT_BLUR_RADIUS = 20f
        private const val DEFAULT_GRADIENT_OPACITY = 0.7f
        private const val MAX_BLUR_RADIUS = 25f
    }

    // Main views
    private var primaryPhotoView: ImageView? = null
    private var overlayPhotoView: ImageView? = null
    private var topLetterboxView: ImageView? = null
    private var bottomLetterboxView: ImageView? = null
    private var containerView: View? = null

    // Job for coroutine management
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    // Cached letterbox resources
    private var currentPhoto: Drawable? = null
    private var cachedBlurredBitmap: Bitmap? = null
    private var cachedMirrorTopBitmap: Bitmap? = null
    private var cachedMirrorBottomBitmap: Bitmap? = null
    private var cachedPaletteColors: IntArray? = null

    // Track if letterboxing is currently active
    private var isLetterboxActive = false

    /**
     * Initializes the resize manager with the necessary views
     */
    fun initialize(
        primaryView: ImageView,
        overlayView: ImageView,
        topView: ImageView,
        bottomView: ImageView,
        container: View
    ) {
        primaryPhotoView = primaryView
        overlayPhotoView = overlayView
        topLetterboxView = topView
        bottomLetterboxView = bottomView
        containerView = container

        // Set initial visibility to GONE until needed
        topView.visibility = View.GONE
        bottomView.visibility = View.GONE

        // Make sure the main image views have a black background
        primaryView.setBackgroundColor(Color.BLACK)
        overlayView.setBackgroundColor(Color.BLACK)
    }

    /**
     * Clean up resources when no longer needed
     */
    fun cleanup() {
        managerJob.cancel()
        recycleBitmaps()

        primaryPhotoView = null
        overlayPhotoView = null
        topLetterboxView = null
        bottomLetterboxView = null
        containerView = null
        currentPhoto = null
    }

    /**
     * Recycle all cached bitmaps to free memory
     */
    private fun recycleBitmaps() {
        cachedBlurredBitmap?.recycle()
        cachedBlurredBitmap = null

        cachedMirrorTopBitmap?.recycle()
        cachedMirrorTopBitmap = null

        cachedMirrorBottomBitmap?.recycle()
        cachedMirrorBottomBitmap = null

        cachedPaletteColors = null
    }

    /**
     * Apply the current display mode (fit or fill) to both photo views
     */
    fun applyDisplayMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val displayMode = prefs.getString(PREF_KEY_PHOTO_SCALE, DEFAULT_DISPLAY_MODE) ?: DEFAULT_DISPLAY_MODE

        // Apply the appropriate scale type based on display mode
        val scaleType = when (displayMode) {
            DISPLAY_MODE_FIT -> ImageView.ScaleType.FIT_CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }

        primaryPhotoView?.scaleType = scaleType
        overlayPhotoView?.scaleType = scaleType

        // Log the change
        Log.d(TAG, "Applied display mode: $displayMode, scale type: $scaleType")
    }

    /**
     * Process a photo for letterbox display if needed
     * @param drawable The photo drawable to display
     * @param imageView The ImageView that will display the photo (primary or overlay)
     * @return true if letterboxing was applied, false otherwise
     */
    fun processPhoto(drawable: Drawable, imageView: ImageView): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val displayMode = prefs.getString(PREF_KEY_PHOTO_SCALE, DEFAULT_DISPLAY_MODE) ?: DEFAULT_DISPLAY_MODE

        // Only consider letterboxing if in FIT mode
        if (displayMode != DISPLAY_MODE_FIT) {
            hideLetterboxing()
            return false
        }

        // If the views aren't initialized, we can't do anything
        if (topLetterboxView == null || bottomLetterboxView == null) {
            Log.e(TAG, "Letterbox views not initialized")
            return false
        }

        // Get intrinsic dimensions of the drawable
        val photoWidth = drawable.intrinsicWidth
        val photoHeight = drawable.intrinsicHeight

        // If either dimension is invalid, we can't calculate letterboxing
        if (photoWidth <= 0 || photoHeight <= 0) {
            Log.d(TAG, "Invalid photo dimensions: ${photoWidth}x${photoHeight}")
            hideLetterboxing()
            return false
        }

        // Check if this is a landscape photo (wider than tall)
        val isLandscape = photoWidth > photoHeight

        // Also get the dimensions of the container
        val container = containerView
        if (container == null) {
            hideLetterboxing()
            return false
        }

        val containerWidth = container.width
        val containerHeight = container.height

        // Check if container has valid dimensions
        if (containerWidth <= 0 || containerHeight <= 0) {
            Log.d(TAG, "Container dimensions not yet available")
            hideLetterboxing()
            return false
        }

        // Calculate aspect ratios
        val photoRatio = photoWidth.toFloat() / photoHeight.toFloat()
        val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()

        // Only letterbox if we have a landscape image in a portrait container
        // and the aspect ratios indicate letterboxing is needed
        if (isLandscape && containerRatio < photoRatio) {
            Log.d(TAG, "Landscape photo in portrait mode detected. Photo: ${photoWidth}x${photoHeight}, Container: ${containerWidth}x${containerHeight}")

            // Calculate the height the photo should have to maintain aspect ratio
            val scaledPhotoHeight = (containerWidth / photoRatio).toInt()

            // Calculate letterbox heights (how much space on top and bottom)
            val letterboxHeight = (containerHeight - scaledPhotoHeight) / 2

            if (letterboxHeight <= 0) {
                // If letterbox is too small, don't bother
                hideLetterboxing()
                return false
            }

            Log.d(TAG, "Applying letterboxing with height: $letterboxHeight pixels per band")

            // Configure and show letterbox views
            configureBothLetterboxViews(letterboxHeight)

            // Update current photo reference (for reuse in effect changes)
            if (drawable != currentPhoto) {
                // Reset cached resources since we have a new photo
                recycleBitmaps()
                currentPhoto = drawable
            }

            // Apply the current letterbox mode
            applyLetterboxMode(drawable, letterboxHeight)

            // Mark letterboxing as active
            isLetterboxActive = true

            return true
        } else {
            // No letterboxing needed
            hideLetterboxing()
            return false
        }
    }

    /**
     * Configure both letterbox views with the appropriate height and properties
     */
    private fun configureBothLetterboxViews(letterboxHeight: Int) {
        topLetterboxView?.let { view ->
            val params = view.layoutParams
            params.height = letterboxHeight
            view.layoutParams = params
            view.visibility = View.VISIBLE
        }

        bottomLetterboxView?.let { view ->
            val params = view.layoutParams
            params.height = letterboxHeight
            view.layoutParams = params
            view.visibility = View.VISIBLE
        }
    }

    /**
     * Hide the letterboxing views when not needed
     */
    private fun hideLetterboxing() {
        topLetterboxView?.visibility = View.GONE
        bottomLetterboxView?.visibility = View.GONE
        isLetterboxActive = false
    }

    /**
     * Apply the currently selected letterbox mode to fill the letterbox areas
     */
    private fun applyLetterboxMode(drawable: Drawable, letterboxHeight: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val mode = prefs.getString(PREF_KEY_LETTERBOX_MODE, DEFAULT_LETTERBOX_MODE) ?: DEFAULT_LETTERBOX_MODE

        // Cancel any existing processing jobs first
        managerScope.coroutineContext.cancelChildren()

        when (mode) {
            LETTERBOX_MODE_BLACK -> {
                // Just use black background (default)
                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            }

            LETTERBOX_MODE_BLUR -> {
                // Apply blurred version of photo
                applyBlurLetterbox(drawable, letterboxHeight)
            }

            LETTERBOX_MODE_GRADIENT -> {
                // Apply gradient based on photo colors
                applyGradientLetterbox(drawable)
            }

            LETTERBOX_MODE_MIRROR -> {
                // Apply mirrored/reflected edges of the photo
                applyMirrorLetterbox(drawable, letterboxHeight)
            }

            else -> {
                // Fallback to black for unknown modes
                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            }
        }
    }

    /**
     * Apply a blurred version of the photo to the letterbox areas
     */
    private fun applyBlurLetterbox(drawable: Drawable, letterboxHeight: Int) {
        // Use existing blurred bitmap if available
        if (cachedBlurredBitmap != null) {
            topLetterboxView?.setImageBitmap(cachedBlurredBitmap)
            bottomLetterboxView?.setImageBitmap(cachedBlurredBitmap)
            return
        }

        // Start with placeholder
        topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))

        // Get blur intensity from preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val blurIntensity = try {
            prefs.getFloat(PREF_KEY_BLUR_INTENSITY, DEFAULT_BLUR_RADIUS)
        } catch (e: ClassCastException) {
            // If the value is stored as an Integer, convert it to Float
            prefs.getInt(PREF_KEY_BLUR_INTENSITY, DEFAULT_BLUR_RADIUS.toInt()).toFloat()
        }

        // Process blur in background
        managerScope.launch(Dispatchers.Default) {
            try {
                // Convert drawable to bitmap for processing
                val bitmap = drawableToBitmap(drawable)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert drawable to bitmap for blur effect")
                    return@launch
                }

                // Scale down for better performance
                val scaleFactor = 0.2f
                val width = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
                val height = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)

                // Apply blur effect
                val blurRadius = blurIntensity.coerceAtMost(MAX_BLUR_RADIUS)
                val blurredBitmap = applyRenderScriptBlur(scaledBitmap, blurRadius)

                if (blurredBitmap == null) {
                    Log.e(TAG, "Failed to create blurred bitmap")
                    return@launch
                }

                // Save the blurred bitmap for reuse
                cachedBlurredBitmap = blurredBitmap

                // Apply on main thread
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        topLetterboxView?.setImageBitmap(blurredBitmap)
                        bottomLetterboxView?.setImageBitmap(blurredBitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying blur effect", e)
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        // Fallback to black in case of error
                        topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                    }
                }
            }
        }
    }

    /**
     * Apply a color gradient based on the photo's dominant colors
     */
    private fun applyGradientLetterbox(drawable: Drawable) {
        // Use existing palette if available
        if (cachedPaletteColors != null) {
            applyGradientWithColors(cachedPaletteColors!!)
            return
        }

        // Start with placeholder
        topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))

        // Process palette in background
        managerScope.launch(Dispatchers.Default) {
            try {
                // Convert drawable to bitmap for processing
                val bitmap = drawableToBitmap(drawable)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert drawable to bitmap for palette extraction")
                    return@launch
                }

                // Generate palette from bitmap
                val palette = Palette.from(bitmap).generate()

                // Extract colors from palette (dominant, vibrant, muted)
                val dominantColor = palette.getDominantColor(Color.BLACK)
                val vibrantColor = palette.getVibrantColor(dominantColor)
                val darkColor = palette.getDarkMutedColor(Color.BLACK)

                // Create array of colors for top and bottom gradients
                val colorArray = intArrayOf(darkColor, dominantColor, vibrantColor)

                // Save for reuse
                cachedPaletteColors = colorArray

                // Apply on main thread
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        applyGradientWithColors(colorArray)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying gradient effect", e)
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        // Fallback to black in case of error
                        topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                    }
                }
            }
        }
    }

    /**
     * Apply a gradient with the given colors to the letterbox areas
     */
    private fun applyGradientWithColors(colorArray: IntArray) {
        // Get opacity from preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val opacity = try {
            prefs.getFloat(PREF_KEY_GRADIENT_OPACITY, DEFAULT_GRADIENT_OPACITY)
        } catch (e: ClassCastException) {
            // If the value is stored as an Integer, convert it to Float
            prefs.getInt(PREF_KEY_GRADIENT_OPACITY, (DEFAULT_GRADIENT_OPACITY * 100).toInt()).toFloat() / 100f
        }

        // Apply opacity to colors
        val opaqueColors = colorArray.map { color ->
            Color.argb(
                (opacity * 255).toInt(),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
        }.toIntArray()

        // Create top gradient (darker at the edge)
        val topGradient = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            opaqueColors
        )

        // Create bottom gradient (darker at the edge)
        val bottomGradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            opaqueColors
        )

        // Apply gradients
        topLetterboxView?.setImageDrawable(topGradient)
        bottomLetterboxView?.setImageDrawable(bottomGradient)
    }

    /**
     * Apply a mirrored version of the photo edges to the letterbox areas
     */
    private fun applyMirrorLetterbox(drawable: Drawable, letterboxHeight: Int) {
        // Use existing mirrored bitmaps if available
        if (cachedMirrorTopBitmap != null && cachedMirrorBottomBitmap != null) {
            topLetterboxView?.setImageBitmap(cachedMirrorTopBitmap)
            bottomLetterboxView?.setImageBitmap(cachedMirrorBottomBitmap)
            return
        }

        // Start with placeholder
        topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))

        // Process mirror effect in background
        managerScope.launch(Dispatchers.Default) {
            try {
                // Convert drawable to bitmap for processing
                val bitmap = drawableToBitmap(drawable)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert drawable to bitmap for mirror effect")
                    return@launch
                }

                // Calculate source rectangles for top and bottom edges
                val sourceWidth = bitmap.width
                val sourceHeight = bitmap.height

                // Get appropriate height for edge regions to mirror
                val edgeHeight = (letterboxHeight * 1.5).toInt().coerceAtMost(sourceHeight / 3)

                // Create top bitmap (from top of photo, flipped)
                val topBitmap = Bitmap.createBitmap(sourceWidth, edgeHeight, Bitmap.Config.ARGB_8888)
                val topCanvas = Canvas(topBitmap)

                // Draw the top edge of the source bitmap
                val topSourceRect = Rect(0, 0, sourceWidth, edgeHeight)
                val topDestRect = Rect(0, 0, sourceWidth, edgeHeight)
                topCanvas.drawBitmap(bitmap, topSourceRect, topDestRect, Paint().apply {
                    isFilterBitmap = true
                })

                // Apply a transform to flip it
                val topMatrix = Matrix()
                topMatrix.setScale(1f, -1f)
                topMatrix.postTranslate(0f, edgeHeight.toFloat())

                val flippedTopBitmap = Bitmap.createBitmap(sourceWidth, edgeHeight, Bitmap.Config.ARGB_8888)
                val flippedTopCanvas = Canvas(flippedTopBitmap)
                flippedTopCanvas.drawBitmap(topBitmap, topMatrix, Paint().apply {
                    isFilterBitmap = true
                })

                // Create bottom bitmap (from bottom of photo, flipped)
                val bottomBitmap = Bitmap.createBitmap(sourceWidth, edgeHeight, Bitmap.Config.ARGB_8888)
                val bottomCanvas = Canvas(bottomBitmap)

                // Draw the bottom edge of the source bitmap
                val bottomSourceRect = Rect(0, sourceHeight - edgeHeight, sourceWidth, sourceHeight)
                val bottomDestRect = Rect(0, 0, sourceWidth, edgeHeight)
                bottomCanvas.drawBitmap(bitmap, bottomSourceRect, bottomDestRect, Paint().apply {
                    isFilterBitmap = true
                })

                // Apply a transform to flip it
                val bottomMatrix = Matrix()
                bottomMatrix.setScale(1f, -1f)
                bottomMatrix.postTranslate(0f, edgeHeight.toFloat())

                val flippedBottomBitmap = Bitmap.createBitmap(sourceWidth, edgeHeight, Bitmap.Config.ARGB_8888)
                val flippedBottomCanvas = Canvas(flippedBottomBitmap)
                flippedBottomCanvas.drawBitmap(bottomBitmap, bottomMatrix, Paint().apply {
                    isFilterBitmap = true
                })

                // Apply a gradient overlay to fade out the edges
                val topFadeEdgePaint = Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, edgeHeight.toFloat(),
                        intArrayOf(Color.argb(255, 0, 0, 0), Color.argb(0, 0, 0, 0)),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }

                flippedTopCanvas.drawRect(0f, 0f, sourceWidth.toFloat(), edgeHeight.toFloat(), topFadeEdgePaint)

                val bottomFadePaint = Paint().apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, edgeHeight.toFloat(),
                        intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(255, 0, 0, 0)),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                flippedBottomCanvas.drawRect(0f, 0f, sourceWidth.toFloat(), edgeHeight.toFloat(), bottomFadePaint)

                // Save for reuse
                cachedMirrorTopBitmap = flippedTopBitmap
                cachedMirrorBottomBitmap = flippedBottomBitmap

                // Apply on main thread
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        topLetterboxView?.setImageBitmap(flippedTopBitmap)
                        bottomLetterboxView?.setImageBitmap(flippedBottomBitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying mirror effect", e)
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        // Fallback to black in case of error
                        topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                    }
                }
            }
        }
    }

    /**
     * Apply a blur effect using RenderScript
     */
    private fun applyRenderScriptBlur(bitmap: Bitmap, blurRadius: Float): Bitmap? {
        var rs: RenderScript? = null
        var input: Allocation? = null
        var output: Allocation? = null
        var script: ScriptIntrinsicBlur? = null

        try {
            // Create a mutable copy to avoid "bitmap is immutable" errors
            val outputBitmap = bitmap.copy(bitmap.config, true)

            rs = RenderScript.create(context)
            input = Allocation.createFromBitmap(rs, bitmap)
            output = Allocation.createFromBitmap(rs, outputBitmap)

            script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            script.setRadius(blurRadius.coerceAtMost(MAX_BLUR_RADIUS))
            script.setInput(input)
            script.forEach(output)

            output.copyTo(outputBitmap)

            return outputBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error applying RenderScript blur", e)
            return null
        } finally {
            // Properly clean up all RenderScript resources
            try {
                script?.destroy()
                input?.destroy()
                output?.destroy()
                rs?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up RenderScript resources", e)
            }
        }
    }

    /**
     * Utility method to convert a drawable to a bitmap
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }

            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to bitmap", e)
            null
        }
    }

    /**
     * Check if letterboxing is currently active
     */
    fun isLetterboxActive(): Boolean = isLetterboxActive
}