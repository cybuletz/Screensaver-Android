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
        // Removed the gradient option
        const val LETTERBOX_MODE_MIRROR = "mirror"
        const val LETTERBOX_MODE_AMBIENT = "ambient"
        const val LETTERBOX_MODE_AMBIENT_CLOUDS = "ambient_clouds"

        // Default values
        const val DEFAULT_DISPLAY_MODE = DISPLAY_MODE_FILL
        const val DEFAULT_LETTERBOX_MODE = LETTERBOX_MODE_BLACK

        // Preference keys
        const val PREF_KEY_PHOTO_SCALE = "photo_scale"
        const val PREF_KEY_LETTERBOX_MODE = "letterbox_mode"
        const val PREF_KEY_BLUR_INTENSITY = "letterbox_blur_intensity"
        // Removed the gradient opacity preference
        const val PREF_KEY_AMBIENT_INTENSITY = "letterbox_ambient_intensity"

        // Constants for effects
        private const val DEFAULT_BLUR_RADIUS = 20f
        private const val MAX_BLUR_RADIUS = 25f

        // Minimum width in pixels for a color segment to be included
        private const val MIN_COLOR_SEGMENT_WIDTH = 20
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

    // New cache variables for ambient columns effect
    private var cachedTopAmbientColumnsBitmap: Bitmap? = null
    private var cachedBottomAmbientColumnsBitmap: Bitmap? = null
    private var lastAmbientColumnsCacheKey: String? = null

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

        cachedTopAmbientColumnsBitmap?.recycle()
        cachedTopAmbientColumnsBitmap = null

        cachedBottomAmbientColumnsBitmap?.recycle()
        cachedBottomAmbientColumnsBitmap = null

        lastAmbientCacheKey = null
        lastAmbientColumnsCacheKey = null

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
    private fun applyAmbientCloudsLetterbox(drawable: Drawable, letterboxHeight: Int) {
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
     * Adjust color for ambient effect - make them even lighter
     */
    private fun adjustColorForAmbient(color: Int, random: Random): Int {
        // Convert to HSV
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // Add slight randomness to hue
        var hue = (hsv[0] + random.nextFloat() * 20 - 10) % 360
        if (hue < 0) hue += 360
        hsv[0] = hue

        // Boost saturation slightly (reduced to make colors softer)
        hsv[1] = (hsv[1] * (1.0f + random.nextFloat() * 0.2f)).coerceIn(0.35f, 0.8f)

        // Make colors even lighter by boosting brightness more
        hsv[2] = (hsv[2] * (1.0f + random.nextFloat() * 0.4f) + 0.1f).coerceIn(0.6f, 0.95f)

        // Increased alpha for more vibrant effect but still somewhat transparent
        val alpha = (70 + random.nextInt(60)).coerceIn(70, 130)

        return Color.HSVToColor(alpha, hsv)
    }

    // Completely redesigned ambient columns letterbox effect to eliminate all stripes
    private fun applyAmbientColumnsLetterbox(drawable: Drawable, letterboxHeight: Int) {
        // Check if we already have ambient column bitmaps for this drawable
        val cacheKey = System.identityHashCode(drawable).toString()

        if (cachedTopAmbientColumnsBitmap != null && cachedBottomAmbientColumnsBitmap != null &&
            lastAmbientColumnsCacheKey == cacheKey) {
            // Use existing cached ambient effect
            Log.d(TAG, "Using cached ambient columns effect")
            topLetterboxView?.setImageBitmap(cachedTopAmbientColumnsBitmap)
            bottomLetterboxView?.setImageBitmap(cachedBottomAmbientColumnsBitmap)
            return
        }

        // Start with dark gray placeholder
        topLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
        bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val ambientIntensity = try {
            prefs.getInt(PREF_KEY_AMBIENT_INTENSITY, 70) / 100f
        } catch (e: ClassCastException) {
            0.7f // Default intensity
        }

        // Process ambient lighting in background
        managerScope.launch(Dispatchers.Default) {
            try {
                // Convert drawable to bitmap for processing
                val bitmap = drawableToBitmap(drawable)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert drawable to bitmap for ambient lighting effect")
                    return@launch
                }

                // Create bitmaps for top and bottom letterbox areas at double width for smoother blur
                val scaleFactor = 2.0f
                val blurWidth = (bitmap.width * scaleFactor).toInt()
                val blurHeight = (letterboxHeight * 1.5).toInt()

                val topAmbientBitmap = Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888)
                val bottomAmbientBitmap = Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888)

                // Create canvases for drawing
                val topCanvas = Canvas(topAmbientBitmap)
                val bottomCanvas = Canvas(bottomAmbientBitmap)

                // Fill with black initially
                topCanvas.drawColor(Color.BLACK)
                bottomCanvas.drawColor(Color.BLACK)

                // Sample colors from the edge of the image
                val topColors = sampleEdgeColors(bitmap, true, 50)
                val bottomColors = sampleEdgeColors(bitmap, false, 50)

                // Filter color points to remove very narrow segments
                val filteredTopColors = filterNarrowColorSegments(topColors, blurWidth)
                val filteredBottomColors = filterNarrowColorSegments(bottomColors, blurWidth)

                // Create a much smoother gradient by sampling more points and using path-based gradients

                // For top edge
                val topGradientBitmap = createSmoothColorGradient(
                    blurWidth,
                    blurHeight,
                    filteredTopColors,
                    true
                )
                topCanvas.drawBitmap(topGradientBitmap, 0f, 0f, null)
                topGradientBitmap.recycle()

                // For bottom edge
                val bottomGradientBitmap = createSmoothColorGradient(
                    blurWidth,
                    blurHeight,
                    filteredBottomColors,
                    false
                )
                bottomCanvas.drawBitmap(bottomGradientBitmap, 0f, 0f, null)
                bottomGradientBitmap.recycle()

                // Apply extreme gaussian blur to eliminate any remaining hard edges
                val topBlurred = applyMultipassBlur(topAmbientBitmap, 25f, 3)
                val bottomBlurred = applyMultipassBlur(bottomAmbientBitmap, 25f, 3)

                // Scale back down to original size
                val finalTopBitmap = Bitmap.createScaledBitmap(
                    topBlurred ?: topAmbientBitmap,
                    bitmap.width,
                    letterboxHeight,
                    true
                )

                val finalBottomBitmap = Bitmap.createScaledBitmap(
                    bottomBlurred ?: bottomAmbientBitmap,
                    bitmap.width,
                    letterboxHeight,
                    true
                )

                // Cache for reuse
                cachedTopAmbientColumnsBitmap = finalTopBitmap
                cachedBottomAmbientColumnsBitmap = finalBottomBitmap
                lastAmbientColumnsCacheKey = cacheKey

                // Recycle intermediate bitmaps
                topAmbientBitmap.recycle()
                bottomAmbientBitmap.recycle()
                if (topBlurred != null && topBlurred != topAmbientBitmap) topBlurred.recycle()
                if (bottomBlurred != null && bottomBlurred != bottomAmbientBitmap) bottomBlurred.recycle()

                // Apply on main thread
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        topLetterboxView?.setImageBitmap(finalTopBitmap)
                        bottomLetterboxView?.setImageBitmap(finalBottomBitmap)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error applying ambient lighting effect", e)
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            // Fallback to dark gray in case of error
                            topLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
                            bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
                        }
                    }
                } else {
                    Log.d(TAG, "Ambient lighting processing was cancelled")
                }
            }
        }
    }

    /**
     * Filter out color segments that are too narrow
     */
    private fun filterNarrowColorSegments(colorPoints: List<Pair<Float, Int>>, totalWidth: Int): List<Pair<Float, Int>> {
        if (colorPoints.size <= 2) {
            return colorPoints // Don't filter if we only have two points (full width)
        }

        val result = mutableListOf<Pair<Float, Int>>()

        // Always include first and last points to ensure full coverage
        result.add(colorPoints.first())

        // Check segments between points
        for (i in 1 until colorPoints.size - 1) {
            val prevX = colorPoints[i-1].first
            val currentX = colorPoints[i].first
            val nextX = colorPoints[i+1].first

            // Calculate segment widths in pixels
            val prevSegmentWidth = (currentX - prevX) * totalWidth
            val nextSegmentWidth = (nextX - currentX) * totalWidth

            // Only include this point if either adjacent segment is wide enough
            if (prevSegmentWidth >= MIN_COLOR_SEGMENT_WIDTH || nextSegmentWidth >= MIN_COLOR_SEGMENT_WIDTH) {
                result.add(colorPoints[i])
            }
        }

        // Always include last point
        result.add(colorPoints.last())

        return result
    }

    // Sample colors evenly across the edge of an image
    private fun sampleEdgeColors(bitmap: Bitmap, isTopEdge: Boolean, sampleCount: Int): List<Pair<Float, Int>> {
        val result = mutableListOf<Pair<Float, Int>>()
        val width = bitmap.width

        // Determine sampling area height
        val sampleAreaHeight = (bitmap.height * 0.08).toInt().coerceAtLeast(2)
        val startY = if (isTopEdge) 0 else bitmap.height - sampleAreaHeight
        val endY = if (isTopEdge) sampleAreaHeight else bitmap.height

        // Sample at regular intervals
        for (i in 0 until sampleCount) {
            val normalizedX = i / (sampleCount - 1f)
            val sampleX = (normalizedX * (width - 1)).toInt()

            // Average color from a small patch around this point
            val patchSize = 3
            val sampleColors = mutableListOf<Int>()

            for (y in startY until endY) {
                for (x in (sampleX - patchSize).coerceAtLeast(0)..(sampleX + patchSize).coerceAtMost(width - 1)) {
                    sampleColors.add(bitmap.getPixel(x, y))
                }
            }

            val avgColor = averageColors(sampleColors)
            val enhancedColor = enhanceColor(avgColor)
            result.add(Pair(normalizedX, enhancedColor))
        }

        return result
    }

    // Create an ultra-smooth color gradient from sampled points
    private fun createSmoothColorGradient(width: Int, height: Int, colorPoints: List<Pair<Float, Int>>, isTopEdge: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill with black initially
        canvas.drawColor(Color.BLACK)

        // First pass: Create smooth color bands with wide overlap
        for (i in 0 until colorPoints.size - 1) {
            val (startX, startColor) = colorPoints[i]
            val (endX, endColor) = colorPoints[i + 1]

            // Convert normalized coordinates to pixel positions
            val x1 = startX * width
            val x2 = endX * width

            // Create a wide linear gradient between points
            val gradient = LinearGradient(
                x1, 0f,
                x2, 0f,
                startColor,
                endColor,
                Shader.TileMode.CLAMP
            )

            val paint = Paint().apply {
                shader = gradient
                isAntiAlias = true
            }

            // Draw a rectangle covering this segment
            canvas.drawRect(x1, 0f, x2, height.toFloat(), paint)
        }

        // Second pass: Apply vertical gradient for depth
        val verticalGradient = LinearGradient(
            0f, if (isTopEdge) height.toFloat() else 0f,
            0f, if (isTopEdge) 0f else height.toFloat(),
            intArrayOf(
                Color.argb(255, 0, 0, 0),  // Full black at one end
                Color.argb(0, 0, 0, 0)     // Transparent at the other end
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        val verticalPaint = Paint().apply {
            shader = verticalGradient
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), verticalPaint)

        return bitmap
    }

    // Multipass blur for extremely smooth gradients
    private fun applyMultipassBlur(bitmap: Bitmap, radius: Float, passes: Int): Bitmap? {
        try {
            var currentBitmap = bitmap
            var resultBitmap: Bitmap? = null

            // Apply multiple blur passes with diminishing radius
            for (i in 0 until passes) {
                val passRadius = radius * (1f - (i * 0.2f)) // Gradually reduce radius in later passes

                val blurredBitmap = Bitmap.createBitmap(
                    currentBitmap.width,
                    currentBitmap.height,
                    Bitmap.Config.ARGB_8888
                )

                val canvas = Canvas(blurredBitmap)
                val paint = Paint()
                paint.isAntiAlias = true
                paint.maskFilter = BlurMaskFilter(passRadius, BlurMaskFilter.Blur.NORMAL)
                canvas.drawBitmap(currentBitmap, 0f, 0f, paint)

                // If we created an intermediate bitmap, recycle it
                if (resultBitmap != null && resultBitmap != bitmap) {
                    resultBitmap.recycle()
                }

                resultBitmap = blurredBitmap
                currentBitmap = blurredBitmap
            }

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error applying multipass blur", e)
            return null
        }
    }

    // Enhance color to make it more visible and lighter
    private fun enhanceColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // Slightly reduce saturation for a lighter, less intense look
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(0.9f)

        // Boost brightness by 50% instead of 30% to make colors lighter
        hsv[2] = (hsv[2] * 1.5f + 0.1f).coerceAtMost(1.0f)

        // Ensure alpha is high enough for visibility but not too opaque
        return Color.HSVToColor(180, hsv)
    }

    // Simple and reliable blur function that works consistently
    private fun simpleBlur(source: Bitmap, radius: Float): Bitmap? {
        try {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint()
            paint.isAntiAlias = true

            // Apply two-pass blur for more even results
            // First horizontal blur
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawBitmap(source, 0f, 0f, paint)

            // Second blur pass with different radius
            val secondBlurPaint = Paint()
            secondBlurPaint.isAntiAlias = true
            secondBlurPaint.maskFilter = BlurMaskFilter(radius * 0.7f, BlurMaskFilter.Blur.NORMAL)

            // Create a temporary bitmap for the second pass
            val tempBitmap = Bitmap.createBitmap(output)
            val tempCanvas = Canvas(output)
            tempCanvas.drawBitmap(tempBitmap, 0f, 0f, secondBlurPaint)
            tempBitmap.recycle()

            return output
        } catch (e: Exception) {
            Log.e(TAG, "Error applying simple blur", e)
            return null
        }
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
            LETTERBOX_MODE_AMBIENT_CLOUDS -> {
                // Start with black for ambient clouds effect
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
            LETTERBOX_MODE_BLUR -> {
                applyBlurLetterbox(drawable, letterboxHeight)
            }
            LETTERBOX_MODE_MIRROR -> {
                applyMirrorLetterbox(drawable, letterboxHeight)
            }
            LETTERBOX_MODE_AMBIENT -> {
                applyAmbientColumnsLetterbox(drawable, letterboxHeight)
            }
            LETTERBOX_MODE_AMBIENT_CLOUDS -> {
                applyAmbientCloudsLetterbox(drawable, letterboxHeight)
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

                // Create RenderScript
                try {
                    val rs = RenderScript.create(context)
                    val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

                    // Allocate memory for Renderscript to work with
                    val topAllocationIn = Allocation.createFromBitmap(rs, scaledTopBitmap)
                    val topAllocationOut = Allocation.createTyped(rs, topAllocationIn.type)

                    val bottomAllocationIn = Allocation.createFromBitmap(rs, scaledBottomBitmap)
                    val bottomAllocationOut = Allocation.createTyped(rs, bottomAllocationIn.type)

                    // Set the blur radius
                    blurScript.setRadius(blurRadius)

                    // Perform the blur
                    blurScript.setInput(topAllocationIn)
                    blurScript.forEach(topAllocationOut)
                    topAllocationOut.copyTo(scaledTopBitmap)

                    blurScript.setInput(bottomAllocationIn)
                    blurScript.forEach(bottomAllocationOut)
                    bottomAllocationOut.copyTo(scaledBottomBitmap)

                    // Scale back up to full size (with blur effect applied)
                    val blurredTopBitmap = Bitmap.createScaledBitmap(
                        scaledTopBitmap,
                        originalBitmap.width,
                        letterboxHeight,
                        true
                    )

                    val blurredBottomBitmap = Bitmap.createScaledBitmap(
                        scaledBottomBitmap,
                        originalBitmap.width,
                        letterboxHeight,
                        true
                    )

                    // Clean up resources
                    topAllocationIn.destroy()
                    topAllocationOut.destroy()
                    bottomAllocationIn.destroy()
                    bottomAllocationOut.destroy()
                    blurScript.destroy()
                    rs.destroy()

                    // Cache for reuse
                    cachedBlurredTopBitmap = blurredTopBitmap
                    cachedBlurredBottomBitmap = blurredBottomBitmap

                    // Apply on main thread
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            topLetterboxView?.setImageBitmap(blurredTopBitmap)
                            bottomLetterboxView?.setImageBitmap(blurredBottomBitmap)
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to simple blur if RenderScript fails
                    Log.e(TAG, "Error applying RenderScript blur, falling back to simple blur", e)

                    val blurredTopBitmap = simpleBlur(topEdgeBitmap, blurRadius)
                    val blurredBottomBitmap = simpleBlur(bottomEdgeBitmap, blurRadius)

                    // Cache for reuse
                    cachedBlurredTopBitmap = blurredTopBitmap
                    cachedBlurredBottomBitmap = blurredBottomBitmap

                    // Apply on main thread
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            if (blurredTopBitmap != null) {
                                topLetterboxView?.setImageBitmap(blurredTopBitmap)
                            }
                            if (blurredBottomBitmap != null) {
                                bottomLetterboxView?.setImageBitmap(blurredBottomBitmap)
                            }
                        }
                    }
                }

                // Clean up temporary bitmaps
                topEdgeBitmap.recycle()
                bottomEdgeBitmap.recycle()
                scaledTopBitmap.recycle()
                scaledBottomBitmap.recycle()

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error applying edge blur effect", e)
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            // Fallback to black
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
     * Apply a mirror effect in the letterbox areas
     * Extracts the top and bottom parts of the original photo, flips them, and applies blur
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

        // Process mirror in background
        managerScope.launch(Dispatchers.Default) {
            try {
                // Convert drawable to bitmap for processing
                val originalBitmap = drawableToBitmap(drawable)
                if (originalBitmap == null) {
                    Log.e(TAG, "Failed to convert drawable to bitmap for mirror effect")
                    return@launch
                }

                // Extract the top and bottom edges for mirroring
                val topEdgeHeight = (originalBitmap.height * 0.15f).toInt().coerceAtMost(letterboxHeight)
                val bottomEdgeHeight = (originalBitmap.height * 0.15f).toInt().coerceAtMost(letterboxHeight)

                val topEdgeBitmap = Bitmap.createBitmap(
                    originalBitmap,
                    0,
                    0,
                    originalBitmap.width,
                    topEdgeHeight
                )

                val bottomEdgeBitmap = Bitmap.createBitmap(
                    originalBitmap,
                    0,
                    originalBitmap.height - bottomEdgeHeight,
                    originalBitmap.width,
                    bottomEdgeHeight
                )

                // Create mirrored versions
                val topMirroredBitmap = Bitmap.createBitmap(
                    originalBitmap.width,
                    letterboxHeight,
                    Bitmap.Config.ARGB_8888
                )

                val bottomMirroredBitmap = Bitmap.createBitmap(
                    originalBitmap.width,
                    letterboxHeight,
                    Bitmap.Config.ARGB_8888
                )

                // Create Canvas objects for drawing
                val topCanvas = Canvas(topMirroredBitmap)
                val bottomCanvas = Canvas(bottomMirroredBitmap)

                // Set up mirror matrices
                val topMatrix = Matrix()
                topMatrix.preScale(1f, -1f) // Flip vertically for top letterbox

                val bottomMatrix = Matrix()
                bottomMatrix.preScale(1f, -1f) // Flip vertically for bottom letterbox

                // Draw the mirrored images
                // For top letterbox, draw at the bottom of the canvas
                topCanvas.drawBitmap(
                    topEdgeBitmap,
                    topMatrix,
                    Paint().apply { isFilterBitmap = true }
                )

                // For bottom letterbox, draw at the top of the canvas
                bottomCanvas.drawBitmap(
                    bottomEdgeBitmap,
                    bottomMatrix,
                    Paint().apply { isFilterBitmap = true }
                )

                // Apply a soft blur to make the mirrored effect less harsh
                try {
                    val rs = RenderScript.create(context)
                    val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

                    // Set up for top letterbox
                    val topAllocationIn = Allocation.createFromBitmap(rs, topMirroredBitmap)
                    val topAllocationOut = Allocation.createTyped(rs, topAllocationIn.type)

                    // Set up for bottom letterbox
                    val bottomAllocationIn = Allocation.createFromBitmap(rs, bottomMirroredBitmap)
                    val bottomAllocationOut = Allocation.createTyped(rs, bottomAllocationIn.type)

                    // Set blur radius (light blur)
                    blurScript.setRadius(5f)

                    // Process top letterbox
                    blurScript.setInput(topAllocationIn)
                    blurScript.forEach(topAllocationOut)
                    topAllocationOut.copyTo(topMirroredBitmap)

                    // Process bottom letterbox
                    blurScript.setInput(bottomAllocationIn)
                    blurScript.forEach(bottomAllocationOut)
                    bottomAllocationOut.copyTo(bottomMirroredBitmap)

                    // Clean up RenderScript resources
                    topAllocationIn.destroy()
                    topAllocationOut.destroy()
                    bottomAllocationIn.destroy()
                    bottomAllocationOut.destroy()
                    blurScript.destroy()
                    rs.destroy()
                } catch (e: Exception) {
                    Log.e(TAG, "Error applying blur to mirrored images", e)
                    // Continue without blur if RenderScript fails
                }

                // Apply a gradient to fade the mirror effect towards the edges
                applyFadeGradientToMirror(topMirroredBitmap, true)
                applyFadeGradientToMirror(bottomMirroredBitmap, false)

                // Cache for reuse
                cachedMirrorTopBitmap = topMirroredBitmap
                cachedMirrorBottomBitmap = bottomMirroredBitmap

                // Apply on main thread
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        topLetterboxView?.setImageBitmap(topMirroredBitmap)
                        bottomLetterboxView?.setImageBitmap(bottomMirroredBitmap)
                    }
                }

                // Clean up temporary bitmaps
                topEdgeBitmap.recycle()
                bottomEdgeBitmap.recycle()

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error applying mirror effect", e)
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            // Fallback to black
                            topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                            bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                        }
                    }
                } else {
                    Log.d(TAG, "Mirror effect processing was cancelled")
                }
            }
        }
    }

    /**
     * Apply a gradient to fade the mirrored image
     */
    private fun applyFadeGradientToMirror(bitmap: Bitmap, isTopLetterbox: Boolean) {
        val canvas = Canvas(bitmap)
        val paint = Paint()

        // Create linear gradient for fading effect
        val gradient = LinearGradient(
            0f,
            if (isTopLetterbox) bitmap.height.toFloat() else 0f,
            0f,
            if (isTopLetterbox) 0f else bitmap.height.toFloat(),
            intArrayOf(
                Color.argb(180, 0, 0, 0),  // Semi-transparent black
                Color.argb(255, 0, 0, 0)   // Opaque black
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        paint.shader = gradient
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
    }

    /**
     * Calculate the average of a list of colors
     */
    private fun averageColors(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.BLACK

        var totalR = 0
        var totalG = 0
        var totalB = 0
        var totalA = 0

        for (color in colors) {
            totalR += Color.red(color)
            totalG += Color.green(color)
            totalB += Color.blue(color)
            totalA += Color.alpha(color)
        }

        val count = colors.size
        return Color.argb(
            totalA / count,
            totalR / count,
            totalG / count,
            totalB / count
        )
    }

    /**
     * Convert a drawable to a bitmap
     */
    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val bitmapDrawable = drawable as BitmapDrawable
            if (bitmapDrawable.bitmap != null) {
                return bitmapDrawable.bitmap
            }
        }

        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight

        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            return null
        }

        try {
            val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory when converting drawable to bitmap", e)
            return null
        }
    }
}