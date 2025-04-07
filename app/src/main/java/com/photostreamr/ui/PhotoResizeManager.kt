package com.photostreamr.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import java.util.Random
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
        const val LETTERBOX_MODE_AMBIENT = "ambient"

        // Default values
        const val DEFAULT_DISPLAY_MODE = DISPLAY_MODE_FILL
        const val DEFAULT_LETTERBOX_MODE = LETTERBOX_MODE_BLACK

        // Preference keys
        const val PREF_KEY_PHOTO_SCALE = "photo_scale"
        const val PREF_KEY_LETTERBOX_MODE = "letterbox_mode"
        const val PREF_KEY_BLUR_INTENSITY = "letterbox_blur_intensity"
        const val PREF_KEY_GRADIENT_OPACITY = "letterbox_gradient_opacity"
        const val PREF_KEY_AMBIENT_INTENSITY = "letterbox_ambient_intensity"


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

    // Cache for blurred edge bitmaps
    private var cachedBlurredTopBitmap: Bitmap? = null
    private var cachedBlurredBottomBitmap: Bitmap? = null

    // Add these to cache generated ambient effects for reuse
    private var cachedTopAmbientBitmap: Bitmap? = null
    private var cachedBottomAmbientBitmap: Bitmap? = null
    private var lastAmbientCacheKey: String? = null

    private var lastPaletteCacheKey: String? = null

    // Job for coroutine management
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    // Cached letterbox resources
    private var currentPhoto: Drawable? = null
    private var cachedBlurredBitmap: Bitmap? = null
    private var cachedMirrorTopBitmap: Bitmap? = null
    private var cachedMirrorBottomBitmap: Bitmap? = null
    private var cachedPaletteColors: Array<IntArray>? = null

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

        cachedBlurredTopBitmap?.recycle()
        cachedBlurredTopBitmap = null

        cachedBlurredBottomBitmap?.recycle()
        cachedBlurredBottomBitmap = null

        cachedMirrorTopBitmap?.recycle()
        cachedMirrorTopBitmap = null

        cachedMirrorBottomBitmap?.recycle()
        cachedMirrorBottomBitmap = null

        cachedTopAmbientBitmap?.recycle()
        cachedTopAmbientBitmap = null

        cachedBottomAmbientBitmap?.recycle()
        cachedBottomAmbientBitmap = null

        lastAmbientCacheKey = null

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
     * Apply a completely random, amorphous cloud-like ambient effect
     * No refreshing - generate once and keep it
     */
    private fun applyAmbientLetterbox(drawable: Drawable, letterboxHeight: Int) {
        // Check if we already have ambient bitmaps for this drawable
        val cacheKey = System.identityHashCode(drawable).toString()

        if (cachedTopAmbientBitmap != null && cachedBottomAmbientBitmap != null && lastAmbientCacheKey == cacheKey) {
            // Use existing cached ambient effect
            Log.d(TAG, "Using cached ambient effect")
            topLetterboxView?.setImageBitmap(cachedTopAmbientBitmap)
            bottomLetterboxView?.setImageBitmap(cachedBottomAmbientBitmap)
            return
        }

        // Start with dark gray placeholder
        topLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))
        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))

        // Generate new ambient effect in background
        managerScope.launch(Dispatchers.Default) {
            try {
                // Convert drawable to bitmap for processing
                val bitmap = drawableToBitmap(drawable)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert drawable to bitmap for ambient lighting effect")
                    return@launch
                }

                // Sample dominant colors from the image using Palette API
                val palette = Palette.from(bitmap).generate()

                // Extract up to 6 distinct colors from the palette
                val colors = mutableListOf<Int>()

                // Add vibrant colors with fallbacks
                palette.vibrantSwatch?.rgb?.let { colors.add(it) }
                palette.lightVibrantSwatch?.rgb?.let { colors.add(it) }
                palette.darkVibrantSwatch?.rgb?.let { colors.add(it) }
                palette.mutedSwatch?.rgb?.let { colors.add(it) }
                palette.lightMutedSwatch?.rgb?.let { colors.add(it) }
                palette.darkMutedSwatch?.rgb?.let { colors.add(it) }

                // If we couldn't get enough colors, add some from the image directly
                if (colors.size < 3) {
                    val random = Random()
                    for (i in 0 until 5) {
                        val x = random.nextInt(bitmap.width)
                        val y = random.nextInt(bitmap.height)
                        colors.add(bitmap.getPixel(x, y))
                    }
                }

                // Create bitmaps for top and bottom letterbox areas
                val topAmbientBitmap = createPurelyRandomCloudEffect(bitmap.width, letterboxHeight, colors)
                val bottomAmbientBitmap = createPurelyRandomCloudEffect(bitmap.width, letterboxHeight, colors)

                // Cache for reuse
                cachedTopAmbientBitmap = topAmbientBitmap
                cachedBottomAmbientBitmap = bottomAmbientBitmap
                lastAmbientCacheKey = cacheKey

                // Apply on main thread - simple direct application
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        topLetterboxView?.setImageBitmap(topAmbientBitmap)
                        bottomLetterboxView?.setImageBitmap(bottomAmbientBitmap)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error applying ambient lighting effect", e)
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            // Fallback to dark color in case of error
                            topLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))
                            bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))
                        }
                    }
                } else {
                    Log.d(TAG, "Ambient lighting processing was cancelled")
                }
            }
        }
    }

    /**
     * Cross-fade to new drawable to prevent flickering
     */
    private fun crossfadeLetterboxDrawable(view: ImageView?, newBitmap: Bitmap) {
        if (view == null) return

        // If the view already has a bitmap, do a smooth crossfade
        if (view.drawable is BitmapDrawable) {
            // Create new ImageView with the new bitmap to fade in
            val tempImageView = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    view.height
                )
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(newBitmap)
                alpha = 0f
            }

            // Add temp view to the parent
            (view.parent as? ViewGroup)?.addView(tempImageView)

            // Position temp view exactly over the original
            tempImageView.y = view.y

            // Fade in the new view
            tempImageView.animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // When fade completes, update the original view and remove the temp
                        view.setImageBitmap(newBitmap)
                        (tempImageView.parent as? ViewGroup)?.removeView(tempImageView)
                    }
                })
                .start()
        } else {
            // For initial setup, just set the bitmap with a fade
            view.alpha = 0f
            view.setImageBitmap(newBitmap)
            view.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        }
    }

    /**
     * Create a completely random, amorphous cloud effect
     * No structure, no patterns, just pure randomness with larger, more numerous clouds
     */
    private fun createPurelyRandomCloudEffect(width: Int, height: Int, colors: List<Int>): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val random = Random(System.currentTimeMillis())

        // Create a noise texture first (using random pixel values)
        val noise = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Fill noise bitmap with random pixels
        for (x in 0 until width) {
            for (y in 0 until height) {
                // Generate random gray value for noise
                val alpha = 30 + random.nextInt(30) // Low alpha for subtle effect
                val value = random.nextInt(255)
                noise.setPixel(x, y, Color.argb(alpha, value, value, value))
            }
        }

        // Apply the noise texture to create a random base
        val noisePaint = Paint()
        noisePaint.alpha = 60 // Reduced to make clouds more visible
        canvas.drawBitmap(noise, 0f, 0f, noisePaint)

        // Increase the number of blobs (50-70 instead of 25-40)
        val numBlobs = 50 + random.nextInt(20)

        for (i in 0 until numBlobs) {
            // Completely random position
            val x = random.nextInt(width)
            val y = random.nextInt(height)

            // Larger size range (30-150 instead of 10-70)
            val size = 30 + random.nextInt(120)

            // Random color from our palette
            val color = colors[random.nextInt(colors.size)]

            // Adjust color alpha and brightness
            val adjustedColor = adjustColorForAmbient(color, random)

            // Create paint for this blob
            val paint = Paint()
            paint.isAntiAlias = true

            // Set up radial gradient for soft blob with larger radius
            val gradientRadius = size * (1.0f + random.nextFloat() * 0.5f)
            val radialColors = intArrayOf(
                adjustedColor,
                Color.argb(
                    (Color.alpha(adjustedColor) * 0.7f).toInt(),
                    Color.red(adjustedColor),
                    Color.green(adjustedColor),
                    Color.blue(adjustedColor)
                ),
                Color.argb(0, Color.red(adjustedColor), Color.green(adjustedColor), Color.blue(adjustedColor))
            )

            paint.shader = RadialGradient(
                x.toFloat(), y.toFloat(),
                gradientRadius,
                radialColors,
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )

            // Use screen blend mode for light-like effect
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

            // Draw the blob
            canvas.drawCircle(x.toFloat(), y.toFloat(), gradientRadius, paint)

            // Add additional smaller satellite blobs to create more complex cloud formations
            // This creates cloud clusters rather than just individual blobs
            val numSatellites = 2 + random.nextInt(4)
            for (j in 0 until numSatellites) {
                // Position satellite relative to the main blob
                val satelliteDistance = gradientRadius * (0.3f + random.nextFloat() * 0.7f)
                val satelliteAngle = random.nextFloat() * 360f
                val satelliteX = x + (satelliteDistance * Math.cos(Math.toRadians(satelliteAngle.toDouble()))).toFloat()
                val satelliteY = y + (satelliteDistance * Math.sin(Math.toRadians(satelliteAngle.toDouble()))).toFloat()

                // Smaller size for satellite
                val satelliteSize = size * (0.3f + random.nextFloat() * 0.4f)

                // Create paint for satellite blob
                val satellitePaint = Paint()
                satellitePaint.isAntiAlias = true

                // Similar color but with slight variation
                val hsvColor = FloatArray(3)
                Color.colorToHSV(adjustedColor, hsvColor)

                // Correct way to modify hue without direct array assignment
                var hue = (hsvColor[0] + random.nextFloat() * 10 - 5) % 360
                if (hue < 0) hue += 360
                hsvColor[0] = hue

                val satelliteColor = Color.HSVToColor(Color.alpha(adjustedColor), hsvColor)

                // Set up radial gradient
                satellitePaint.shader = RadialGradient(
                    satelliteX, satelliteY,
                    satelliteSize,
                    intArrayOf(
                        satelliteColor,
                        Color.argb(0, Color.red(satelliteColor), Color.green(satelliteColor), Color.blue(satelliteColor))
                    ),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )

                satellitePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

                // Draw the satellite blob
                canvas.drawCircle(satelliteX, satelliteY, satelliteSize, satellitePaint)
            }
        }

        // Apply final blur for smoother look - slightly reduced to preserve cloud definition
        try {
            val rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createTyped(rs, input.type)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            script.setRadius(12f) // Slightly reduced blur radius
            script.setInput(input)
            script.forEach(output)
            output.copyTo(bitmap)

            input.destroy()
            output.destroy()
            script.destroy()
            rs.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error applying final blur", e)
        }

        return bitmap
    }

    /**
     * Adjust color for ambient effect
     */
    private fun adjustColorForAmbient(color: Int, random: Random): Int {
        // Convert to HSV
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // Add slight randomness to hue
        var hue = (hsv[0] + random.nextFloat() * 20 - 10) % 360
        if (hue < 0) hue += 360
        hsv[0] = hue

        // Boost saturation slightly
        hsv[1] = (hsv[1] * (1.0f + random.nextFloat() * 0.3f)).coerceIn(0.4f, 0.9f)

        // Ensure good brightness
        hsv[2] = (hsv[2] * (1.0f + random.nextFloat() * 0.3f)).coerceIn(0.5f, 0.9f)

        // Increased alpha for more vibrant effect
        val alpha = (70 + random.nextInt(60)).coerceIn(70, 130)

        return Color.HSVToColor(alpha, hsv)
    }

    private fun applyLetterboxMode(drawable: Drawable, letterboxHeight: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val mode = prefs.getString(PREF_KEY_LETTERBOX_MODE, DEFAULT_LETTERBOX_MODE) ?: DEFAULT_LETTERBOX_MODE

        // Debug statement to verify the selected mode
        Log.d(TAG, "Letterbox mode: $mode (height: $letterboxHeight)")

        // Cancel any existing processing jobs first
        managerScope.coroutineContext.cancelChildren()

        // Immediately apply a basic effect to prevent black letterboxing
        when (mode) {
            LETTERBOX_MODE_BLACK -> {
                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            }
            LETTERBOX_MODE_GRADIENT -> {
                // Apply a default gradient immediately
                createDefaultGradient()
            }
            LETTERBOX_MODE_BLUR -> {
                // Start with dark gray for the blur placeholder
                val darkGray = ColorDrawable(Color.parseColor("#222222"))
                topLetterboxView?.setImageDrawable(darkGray)
                bottomLetterboxView?.setImageDrawable(darkGray)
            }
            LETTERBOX_MODE_MIRROR -> {
                // Start with black
                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            }
            LETTERBOX_MODE_AMBIENT -> {
                // Start with black for ambient effect
                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            }
            else -> {
                // Fallback for unknown modes
                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            }
        }

        // Now apply the actual effect
        when (mode) {
            LETTERBOX_MODE_BLACK -> {
                // Already applied
            }
            LETTERBOX_MODE_GRADIENT -> {
                // Apply photo-edge based gradient
                try {
                    applyGradientLetterbox(drawable)
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying immediate gradient effect", e)
                    createDefaultGradient() // Fallback
                }
            }
            LETTERBOX_MODE_BLUR -> {
                applyBlurLetterbox(drawable, letterboxHeight)
            }
            LETTERBOX_MODE_MIRROR -> {
                applyMirrorLetterbox(drawable, letterboxHeight)
            }
            LETTERBOX_MODE_AMBIENT -> {
                applyAmbientLetterbox(drawable, letterboxHeight)
            }
        }
    }

    /**
     * Apply a blurred version of the photo edges to the letterbox areas
     * Simply extracts the top and bottom parts of the original photo and blurs them
     */
    private fun applyBlurLetterbox(drawable: Drawable, letterboxHeight: Int) {
        // Use existing blurred edge bitmaps if available
        if (cachedBlurredTopBitmap != null && cachedBlurredBottomBitmap != null) {
            topLetterboxView?.setImageBitmap(cachedBlurredTopBitmap)
            bottomLetterboxView?.setImageBitmap(cachedBlurredBottomBitmap)
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
                val originalBitmap = drawableToBitmap(drawable)
                if (originalBitmap == null) {
                    Log.e(TAG, "Failed to convert drawable to bitmap for edge blur effect")
                    return@launch
                }

                // Extract the top and bottom portions exactly as they are
                val topEdgeBitmap = Bitmap.createBitmap(
                    originalBitmap,
                    0,
                    0,
                    originalBitmap.width,
                    letterboxHeight.coerceAtMost(originalBitmap.height / 3)
                )

                val bottomEdgeBitmap = Bitmap.createBitmap(
                    originalBitmap,
                    0,
                    originalBitmap.height - letterboxHeight.coerceAtMost(originalBitmap.height / 3),
                    originalBitmap.width,
                    letterboxHeight.coerceAtMost(originalBitmap.height / 3)
                )

                // Apply blur effect directly to these edges
                val blurRadius = blurIntensity.coerceAtMost(MAX_BLUR_RADIUS)

                // Scale down for better blurring performance
                val scaleFactor = 0.5f
                val scaledTopBitmap = Bitmap.createScaledBitmap(
                    topEdgeBitmap,
                    (topEdgeBitmap.width * scaleFactor).toInt(),
                    (topEdgeBitmap.height * scaleFactor).toInt(),
                    true
                )

                val scaledBottomBitmap = Bitmap.createScaledBitmap(
                    bottomEdgeBitmap,
                    (bottomEdgeBitmap.width * scaleFactor).toInt(),
                    (bottomEdgeBitmap.height * scaleFactor).toInt(),
                    true
                )

                // Recycle original edges
                topEdgeBitmap.recycle()
                bottomEdgeBitmap.recycle()

                // Apply blur
                val blurredTopBitmap = applyRenderScriptBlur(scaledTopBitmap, blurRadius)
                val blurredBottomBitmap = applyRenderScriptBlur(scaledBottomBitmap, blurRadius)

                // Recycle scaled bitmaps
                scaledTopBitmap.recycle()
                scaledBottomBitmap.recycle()

                if (blurredTopBitmap == null || blurredBottomBitmap == null) {
                    Log.e(TAG, "Failed to create blurred edge bitmaps")
                    return@launch
                }

                // Scale back to full width but maintain original aspect ratio
                val topFinalBitmap = Bitmap.createScaledBitmap(
                    blurredTopBitmap,
                    originalBitmap.width,
                    (blurredTopBitmap.height * (originalBitmap.width.toFloat() / blurredTopBitmap.width)).toInt(),
                    true
                )

                val bottomFinalBitmap = Bitmap.createScaledBitmap(
                    blurredBottomBitmap,
                    originalBitmap.width,
                    (blurredBottomBitmap.height * (originalBitmap.width.toFloat() / blurredBottomBitmap.width)).toInt(),
                    true
                )

                // Recycle blurred bitmaps
                blurredTopBitmap.recycle()
                blurredBottomBitmap.recycle()

                // Save for reuse
                cachedBlurredTopBitmap = topFinalBitmap
                cachedBlurredBottomBitmap = bottomFinalBitmap

                // Apply on main thread
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        topLetterboxView?.setImageBitmap(topFinalBitmap)
                        bottomLetterboxView?.setImageBitmap(bottomFinalBitmap)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error applying edge blur effect", e)
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            // Fallback to black in case of error
                            topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                            bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                        }
                    }
                } else {
                    Log.d(TAG, "Edge blur processing was cancelled")
                }
            }
        }
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
     * Create a default gradient with colors that blend naturally with photos
     */
    private fun createDefaultGradient() {
        try {
            // Create natural-looking default gradient colors
            val topColors = intArrayOf(
                Color.parseColor("#000000"),  // Black (screen edge)
                Color.parseColor("#1A1A1A"),  // Very dark gray
                Color.parseColor("#333333")   // Dark gray (photo edge)
            )

            val bottomColors = intArrayOf(
                Color.parseColor("#000000"),  // Black (screen edge)
                Color.parseColor("#1A1A1A"),  // Very dark gray
                Color.parseColor("#333333")   // Dark gray (photo edge)
            )

            Log.d(TAG, "Creating default photo-edge style gradient")

            // Apply using the same method as the photo-based gradient
            applyPhotoEdgeGradient(arrayOf(topColors, bottomColors), false, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating default gradient", e)
            // Absolute fallback
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            }
        }
    }

    /**
     * Apply a letterbox gradient derived from the edges of the photo
     * This creates the illusion that the letterbox is an extension of the photo
     */
    private fun applyGradientLetterbox(drawable: Drawable) {
        try {
            // Generate a cache key for the drawable
            val cacheKey = System.identityHashCode(drawable).toString()

            Log.d(TAG, "Starting photo-edge gradient for drawable: $cacheKey")

            // Use existing cached colors if available
            if (cachedPaletteColors != null && lastPaletteCacheKey == cacheKey) {
                Log.d(TAG, "Using cached photo-edge colors")
                applyPhotoEdgeGradient(cachedPaletteColors!!, true, false)
                return
            }

            // Clear cache if we have a new image
            if (cacheKey != lastPaletteCacheKey) {
                cachedPaletteColors = null
                lastPaletteCacheKey = cacheKey
            }

            // Create a default gradient immediately
            createDefaultGradient()

            // Convert drawable to bitmap for processing
            val bitmap = drawableToBitmap(drawable)
            if (bitmap == null) {
                Log.e(TAG, "Failed to convert drawable to bitmap for edge color extraction")
                return
            }

            // Extract colors directly from the bitmap edges
            val edgeColors = extractPhotoEdgeColors(bitmap)

            // Save for reuse
            cachedPaletteColors = edgeColors
            lastPaletteCacheKey = cacheKey

            // Apply the edge-based gradient
            applyPhotoEdgeGradient(edgeColors, true, true)

            Log.d(TAG, "Applied photo-edge based gradient")
        } catch (e: Exception) {
            Log.e(TAG, "Error in applyGradientLetterbox", e)
            createDefaultGradient()  // Fallback
        }
    }

    /**
     * Extract colors from the top and bottom edges of the photo
     * Returns an array with [topEdgeColors, bottomEdgeColors]
     */
    private fun extractPhotoEdgeColors(bitmap: Bitmap): Array<IntArray> {
        // Define how many pixels from the edge to sample
        val edgeSampleWidth = 20
        val numSamples = 5

        try {
            val width = bitmap.width
            val height = bitmap.height

            // Calculate optimal sample positions along the width
            val samplePositions = Array(numSamples) { index ->
                (width * (index + 1) / (numSamples + 1))
            }

            // Arrays to hold sampled colors
            val topColors = IntArray(numSamples)
            val bottomColors = IntArray(numSamples)

            // Sample colors from the top edge at various positions
            for (i in 0 until numSamples) {
                val x = samplePositions[i]

                // Sample from a small area rather than just a pixel
                val topSampleColors = mutableListOf<Int>()
                val bottomSampleColors = mutableListOf<Int>()

                // Sample a few rows at the edge
                for (row in 0 until edgeSampleWidth) {
                    // Sample the top edge
                    if (row < height) {
                        topSampleColors.add(bitmap.getPixel(x, row))
                    }

                    // Sample the bottom edge
                    val bottomRow = height - 1 - row
                    if (bottomRow >= 0) {
                        bottomSampleColors.add(bitmap.getPixel(x, bottomRow))
                    }
                }

                // Calculate the average color for this sample
                topColors[i] = averageColors(topSampleColors)
                bottomColors[i] = averageColors(bottomSampleColors)
            }

            // Enhance the colors to make the gradient more vibrant
            enhanceColorArray(topColors)
            enhanceColorArray(bottomColors)

            return arrayOf(topColors, bottomColors)
        } catch (e: Exception) {
            Log.e(TAG, "Error sampling edge colors", e)
            // Return a default array of colors if sampling fails
            return arrayOf(
                intArrayOf(Color.BLACK, Color.DKGRAY, Color.GRAY),
                intArrayOf(Color.BLACK, Color.DKGRAY, Color.GRAY)
            )
        }
    }

    /**
     * Calculate the average of a list of colors
     */
    private fun averageColors(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.BLACK

        var sumR = 0
        var sumG = 0
        var sumB = 0

        for (color in colors) {
            sumR += Color.red(color)
            sumG += Color.green(color)
            sumB += Color.blue(color)
        }

        val avgR = sumR / colors.size
        val avgG = sumG / colors.size
        val avgB = sumB / colors.size

        return Color.rgb(avgR, avgG, avgB)
    }

    /**
     * Enhance a color array to make gradients more visually appealing
     * This adjusts saturation and ensures a good transition
     */
    private fun enhanceColorArray(colors: IntArray) {
        for (i in colors.indices) {
            val color = colors[i]

            // Convert to HSV for easier manipulation
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)

            // Enhance saturation slightly (0.0-1.0 scale)
            hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1.0f)

            // Ensure value (brightness) is within a good range for gradients
            if (i == 0) {
                // First color (edge of screen) should be darker
                hsv[2] = (hsv[2] * 0.7f).coerceIn(0.1f, 0.5f)
            } else {
                // Inner colors (closer to photo) should maintain brightness
                hsv[2] = hsv[2].coerceIn(0.3f, 0.8f)
            }

            // Store enhanced color back in array
            colors[i] = Color.HSVToColor(hsv)
        }
    }

    /**
     * Apply the photo edge gradient to the letterbox views
     */
    private fun applyPhotoEdgeGradient(edgeColors: Array<IntArray>, smoothTransition: Boolean, isNewPhoto: Boolean) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val opacity = try {
                prefs.getFloat(PREF_KEY_GRADIENT_OPACITY, 0.85f)
            } catch (e: Exception) {
                0.85f  // Default opacity
            }

            Log.d(TAG, "Applying photo-edge gradient with opacity: $opacity")

            // Extract top and bottom edge colors
            val topEdgeColors = edgeColors[0]
            val bottomEdgeColors = edgeColors[1]

            // Apply opacity to the colors
            val topColorsWithOpacity = topEdgeColors.map { color ->
                Color.argb(
                    (opacity * 255).toInt().coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            }.toIntArray()

            val bottomColorsWithOpacity = bottomEdgeColors.map { color ->
                Color.argb(
                    (opacity * 255).toInt().coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            }.toIntArray()

            // Create top gradient (extending from the top edge of the photo)
            val topGradient = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,  // From photo to screen edge
                topColorsWithOpacity
            )

            // Create bottom gradient (extending from the bottom edge of the photo)
            val bottomGradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,  // From photo to screen edge
                bottomColorsWithOpacity
            )

            // Apply gradients to the views on the main thread
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                try {
                    if (smoothTransition && isNewPhoto) {
                        // For new photos, crossfade to the new gradient
                        crossfadeToNewGradient(topLetterboxView, topGradient)
                        crossfadeToNewGradient(bottomLetterboxView, bottomGradient)
                    } else {
                        // Direct application
                        topLetterboxView?.setImageDrawable(topGradient)
                        bottomLetterboxView?.setImageDrawable(bottomGradient)
                    }
                    Log.d(TAG, "Photo-edge gradient applied successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting photo-edge gradient drawable", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in applyPhotoEdgeGradient", e)
            // Fallback
            createDefaultGradient()
        }
    }

    /**
     * Crossfade to a new gradient drawable
     */
    private fun crossfadeToNewGradient(view: ImageView?, newGradient: Drawable) {
        // Skip if view isn't available
        if (view == null) return

        try {
            // Create a fade-out animation for the current drawable
            val fadeOut = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)
            fadeOut.duration = 150

            // Create a fade-in animation for the new drawable
            val fadeIn = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
            fadeIn.duration = 200

            // Set up the sequence
            fadeOut.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Change the drawable when fully faded out
                    view.setImageDrawable(newGradient)
                    // Start fade in
                    fadeIn.start()
                }
            })

            // Start the animation sequence
            fadeOut.start()
        } catch (e: Exception) {
            // If animation fails, just set the drawable directly
            Log.e(TAG, "Crossfade animation failed, applying gradient directly", e)
            view.setImageDrawable(newGradient)
        }
    }
}