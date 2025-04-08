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
 * Manages resizing and letterboxing for photos, handling multiple orientations:
 * 1. Landscape photos in portrait mode (top/bottom letterboxing)
 * 2. Portrait photos in landscape mode (left/right letterboxing)
 * 3. Square photos in any orientation
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
        const val DISPLAY_MODE_MULTI_TEMPLATE = "multi_template" // NEW: Use multiple photos in a template

        // Template layout types
        const val TEMPLATE_TYPE_KEY = "template_layout_type"
        const val TEMPLATE_TYPE_DEFAULT = 0

        // Letterbox fill modes
        const val LETTERBOX_MODE_BLACK = "black"
        const val LETTERBOX_MODE_BLUR = "blur"
        const val LETTERBOX_MODE_AMBIENT = "ambient"
        const val LETTERBOX_MODE_AMBIENT_CLOUDS = "ambient_clouds"

        // Default values
        const val DEFAULT_DISPLAY_MODE = DISPLAY_MODE_FIT
        const val DEFAULT_LETTERBOX_MODE = LETTERBOX_MODE_AMBIENT_CLOUDS

        // Preference keys
        const val PREF_KEY_PHOTO_SCALE = "photo_scale"
        const val PREF_KEY_LETTERBOX_MODE = "letterbox_mode"
        const val PREF_KEY_BLUR_INTENSITY = "letterbox_blur_intensity"
        const val PREF_RANDOM_TEMPLATE_TYPES = "random_template_types"


        // Constants for effects
        private const val DEFAULT_BLUR_RADIUS = 20f
        private const val MAX_BLUR_RADIUS = 25f

        // Minimum width in pixels for a color segment to be included
        private const val MIN_COLOR_SEGMENT_WIDTH = 20

        // Letterbox orientation constants
        private const val LETTERBOX_ORIENTATION_HORIZONTAL = 1  // For top/bottom letterboxing
        private const val LETTERBOX_ORIENTATION_VERTICAL = 2    // For left/right letterboxing
    }

    // Main views
    private var primaryPhotoView: ImageView? = null
    private var overlayPhotoView: ImageView? = null

    // Horizontal letterbox views (top/bottom)
    private var topLetterboxView: ImageView? = null
    private var bottomLetterboxView: ImageView? = null

    // Vertical letterbox views (left/right)
    private var leftLetterboxView: ImageView? = null
    private var rightLetterboxView: ImageView? = null

    private var containerView: View? = null

    // Cache for blurred edge bitmaps - horizontal (top/bottom)
    private var cachedBlurredTopBitmap: Bitmap? = null
    private var cachedBlurredBottomBitmap: Bitmap? = null

    // Cache for blurred edge bitmaps - vertical (left/right)
    private var cachedBlurredLeftBitmap: Bitmap? = null
    private var cachedBlurredRightBitmap: Bitmap? = null

    // Horizontal ambient effect caches (top/bottom)
    private var cachedTopAmbientBitmap: Bitmap? = null
    private var cachedBottomAmbientBitmap: Bitmap? = null
    private var lastAmbientCacheKey: String? = null

    // Vertical ambient effect caches (left/right)
    private var cachedLeftAmbientBitmap: Bitmap? = null
    private var cachedRightAmbientBitmap: Bitmap? = null
    private var lastVerticalAmbientCacheKey: String? = null

    // Horizontal ambient columns effect caches (top/bottom)
    private var cachedTopAmbientColumnsBitmap: Bitmap? = null
    private var cachedBottomAmbientColumnsBitmap: Bitmap? = null
    private var lastAmbientColumnsCacheKey: String? = null

    // Vertical ambient columns effect caches (left/right)
    private var cachedLeftAmbientColumnsBitmap: Bitmap? = null
    private var cachedRightAmbientColumnsBitmap: Bitmap? = null
    private var lastVerticalAmbientColumnsCacheKey: String? = null

    private var lastPaletteCacheKey: String? = null

    // Job for coroutine management
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    // Cached letterbox resources
    private var currentPhoto: Drawable? = null
    private var cachedBlurredBitmap: Bitmap? = null
    private var cachedPaletteColors: Array<IntArray>? = null

    // Track if letterboxing is currently active and its orientation
    private var isLetterboxActive = false
    private var activeLetterboxOrientation = 0

    fun initialize(
        primaryView: ImageView,
        overlayView: ImageView,
        topView: ImageView,
        bottomView: ImageView,
        leftView: ImageView,
        rightView: ImageView,
        container: View
    ) {
        primaryPhotoView = primaryView
        overlayPhotoView = overlayView
        topLetterboxView = topView
        bottomLetterboxView = bottomView
        leftLetterboxView = leftView
        rightLetterboxView = rightView
        containerView = container

        // Set initial visibility to GONE until needed
        topView.visibility = View.GONE
        bottomView.visibility = View.GONE
        leftView.visibility = View.GONE
        rightView.visibility = View.GONE

        // Make sure the main image views have a black background
        primaryView.setBackgroundColor(Color.BLACK)
        overlayView.setBackgroundColor(Color.BLACK)
    }

    fun cleanup() {
        managerJob.cancel()
        recycleBitmaps()

        primaryPhotoView = null
        overlayPhotoView = null
        topLetterboxView = null
        bottomLetterboxView = null
        leftLetterboxView = null
        rightLetterboxView = null
        containerView = null
        currentPhoto = null
    }

    private fun recycleBitmaps() {
        // Horizontal letterbox caches (top/bottom)
        cachedBlurredBitmap?.recycle()
        cachedBlurredBitmap = null

        cachedBlurredTopBitmap?.recycle()
        cachedBlurredTopBitmap = null

        cachedBlurredBottomBitmap?.recycle()
        cachedBlurredBottomBitmap = null

        cachedTopAmbientBitmap?.recycle()
        cachedTopAmbientBitmap = null

        cachedBottomAmbientBitmap?.recycle()
        cachedBottomAmbientBitmap = null

        cachedTopAmbientColumnsBitmap?.recycle()
        cachedTopAmbientColumnsBitmap = null

        cachedBottomAmbientColumnsBitmap?.recycle()
        cachedBottomAmbientColumnsBitmap = null

        // Vertical letterbox caches (left/right)
        cachedBlurredLeftBitmap?.recycle()
        cachedBlurredLeftBitmap = null

        cachedBlurredRightBitmap?.recycle()
        cachedBlurredRightBitmap = null

        cachedLeftAmbientBitmap?.recycle()
        cachedLeftAmbientBitmap = null

        cachedRightAmbientBitmap?.recycle()
        cachedRightAmbientBitmap = null

        cachedLeftAmbientColumnsBitmap?.recycle()
        cachedLeftAmbientColumnsBitmap = null

        cachedRightAmbientColumnsBitmap?.recycle()
        cachedRightAmbientColumnsBitmap = null

        // Reset cache keys
        lastAmbientCacheKey = null
        lastAmbientColumnsCacheKey = null
        lastVerticalAmbientCacheKey = null
        lastVerticalAmbientColumnsCacheKey = null

        cachedPaletteColors = null
    }

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

    fun processPhoto(drawable: Drawable, imageView: ImageView): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val displayMode = prefs.getString(PREF_KEY_PHOTO_SCALE, DEFAULT_DISPLAY_MODE) ?: DEFAULT_DISPLAY_MODE

        // Only consider letterboxing if in FIT mode
        if (displayMode != DISPLAY_MODE_FIT) {
            Log.d(TAG, "Not in FIT mode, no letterboxing applied")
            hideAllLetterboxing()
            return false
        }

        // If the views aren't initialized, we can't do anything
        if (topLetterboxView == null || bottomLetterboxView == null ||
            leftLetterboxView == null || rightLetterboxView == null) {
            Log.e(TAG, "Letterbox views not initialized")
            return false
        }

        // Get intrinsic dimensions of the drawable
        val photoWidth = drawable.intrinsicWidth
        val photoHeight = drawable.intrinsicHeight

        // If either dimension is invalid, we can't calculate letterboxing
        if (photoWidth <= 0 || photoHeight <= 0) {
            Log.d(TAG, "Invalid photo dimensions: ${photoWidth}x${photoHeight}")
            hideAllLetterboxing()
            return false
        }

        // Calculate photo aspect ratio
        val photoRatio = photoWidth.toFloat() / photoHeight.toFloat()
        val isSquarePhoto = (photoRatio >= 0.95f && photoRatio <= 1.05f)
        val isLandscape = photoWidth > photoHeight

        // Also get the dimensions of the container
        val container = containerView
        if (container == null) {
            Log.d(TAG, "Container view is null")
            hideAllLetterboxing()
            return false
        }

        val containerWidth = container.width
        val containerHeight = container.height

        // Check if container has valid dimensions
        if (containerWidth <= 0 || containerHeight <= 0) {
            Log.d(TAG, "Container dimensions not yet available")
            hideAllLetterboxing()
            return false
        }

        // Calculate container aspect ratio
        val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()
        val isLandscapeContainer = containerRatio > 1.0f

        // Enhanced logging for troubleshooting
        Log.d(TAG, """
        Photo dimensions: ${photoWidth}x${photoHeight}
        Photo ratio: $photoRatio 
        Is square photo: $isSquarePhoto
        Is landscape photo: $isLandscape
        Container dimensions: ${containerWidth}x${containerHeight}
        Container ratio: $containerRatio
        Is landscape container: $isLandscapeContainer
    """.trimIndent())

        // Update current photo reference (for reuse in effect changes)
        if (drawable != currentPhoto) {
            // Reset cached resources since we have a new photo
            recycleBitmaps()
            currentPhoto = drawable
        }

        // CASE 1: Square photos - special handling
        if (isSquarePhoto) {
            Log.d(TAG, "Processing square photo...")

            if (isLandscapeContainer) {
                // Square photo in landscape container - apply vertical letterboxing
                val scaledPhotoWidth = containerHeight // For square photos, width = height when fitting to height
                val letterboxWidth = (containerWidth - scaledPhotoWidth) / 2

                Log.d(TAG, "Square photo in landscape container: letterboxWidth = $letterboxWidth")

                // Force letterboxing for square photos even with small margins (minimum 1 pixel)
                if (letterboxWidth >= 1) {
                    configureVerticalLetterboxViews(letterboxWidth)
                    applyLetterboxMode(drawable, letterboxWidth, LETTERBOX_ORIENTATION_VERTICAL)
                    isLetterboxActive = true
                    activeLetterboxOrientation = LETTERBOX_ORIENTATION_VERTICAL
                    return true
                }
            } else {
                // Square photo in portrait container - apply horizontal letterboxing
                val scaledPhotoHeight = containerWidth // For square photos, height = width when fitting to width
                val letterboxHeight = (containerHeight - scaledPhotoHeight) / 2

                Log.d(TAG, "Square photo in portrait container: letterboxHeight = $letterboxHeight")

                // Force letterboxing for square photos even with small margins (minimum 1 pixel)
                if (letterboxHeight >= 1) {
                    configureHorizontalLetterboxViews(letterboxHeight)
                    applyLetterboxMode(drawable, letterboxHeight, LETTERBOX_ORIENTATION_HORIZONTAL)
                    isLetterboxActive = true
                    activeLetterboxOrientation = LETTERBOX_ORIENTATION_HORIZONTAL
                    return true
                }
            }
        }
        // CASE 2: Landscape photo in portrait container
        else if (isLandscape && !isLandscapeContainer) {
            // Calculate the height the photo should have to maintain aspect ratio
            val scaledPhotoHeight = (containerWidth / photoRatio).toInt()

            // Calculate letterbox heights (how much space on top and bottom)
            val letterboxHeight = (containerHeight - scaledPhotoHeight) / 2

            Log.d(TAG, "Landscape photo in portrait container: letterboxHeight = $letterboxHeight")

            // Lower minimum threshold to 1 pixel
            if (letterboxHeight >= 1) {
                configureHorizontalLetterboxViews(letterboxHeight)
                applyLetterboxMode(drawable, letterboxHeight, LETTERBOX_ORIENTATION_HORIZONTAL)
                isLetterboxActive = true
                activeLetterboxOrientation = LETTERBOX_ORIENTATION_HORIZONTAL
                return true
            }
        }
        // CASE 3: Portrait photo in landscape container
        else if (!isLandscape && isLandscapeContainer) {
            // Calculate the width the photo should have to maintain aspect ratio
            val scaledPhotoWidth = (containerHeight * photoRatio).toInt()

            // Calculate letterbox widths (how much space on left and right)
            val letterboxWidth = (containerWidth - scaledPhotoWidth) / 2

            Log.d(TAG, "Portrait photo in landscape container: letterboxWidth = $letterboxWidth")

            // Lower minimum threshold to 1 pixel
            if (letterboxWidth >= 1) {
                configureVerticalLetterboxViews(letterboxWidth)
                applyLetterboxMode(drawable, letterboxWidth, LETTERBOX_ORIENTATION_VERTICAL)
                isLetterboxActive = true
                activeLetterboxOrientation = LETTERBOX_ORIENTATION_VERTICAL
                return true
            }
        }
        // CASE 4: Landscape photo in landscape container (NEW CASE)
        else if (isLandscape && isLandscapeContainer) {
            // If the ratios are different enough, we should still apply letterboxing
            // First try horizontal letterboxing (top/bottom) if the photo is less wide than the container
            if (photoRatio < containerRatio) {
                // Photo is less wide than container (relative to height) - apply vertical letterboxing
                val scaledPhotoWidth = (containerHeight * photoRatio).toInt()
                val letterboxWidth = (containerWidth - scaledPhotoWidth) / 2

                Log.d(TAG, "Landscape photo in landscape container (photo less wide): letterboxWidth = $letterboxWidth")

                if (letterboxWidth >= 1) {
                    configureVerticalLetterboxViews(letterboxWidth)
                    applyLetterboxMode(drawable, letterboxWidth, LETTERBOX_ORIENTATION_VERTICAL)
                    isLetterboxActive = true
                    activeLetterboxOrientation = LETTERBOX_ORIENTATION_VERTICAL
                    return true
                }
            }
            // Otherwise try vertical letterboxing (left/right) if the photo is wider than the container
            else if (photoRatio > containerRatio) {
                // Photo is wider than container (relative to height) - apply horizontal letterboxing
                val scaledPhotoHeight = (containerWidth / photoRatio).toInt()
                val letterboxHeight = (containerHeight - scaledPhotoHeight) / 2

                Log.d(TAG, "Landscape photo in landscape container (photo more wide): letterboxHeight = $letterboxHeight")

                if (letterboxHeight >= 1) {
                    configureHorizontalLetterboxViews(letterboxHeight)
                    applyLetterboxMode(drawable, letterboxHeight, LETTERBOX_ORIENTATION_HORIZONTAL)
                    isLetterboxActive = true
                    activeLetterboxOrientation = LETTERBOX_ORIENTATION_HORIZONTAL
                    return true
                }
            }

            // If we get here, the photo and container have the same ratio or letterboxing is too small
            Log.d(TAG, "Landscape photo in landscape container: no letterboxing needed or too small")
        }
        // CASE 5: Portrait photo in portrait container
        else if (!isLandscape && !isLandscapeContainer) {
            // First try horizontal letterboxing (top/bottom) when the container is narrower relative to photo
            if (photoRatio > containerRatio) {
                // Photo is wider relative to its height than container - apply horizontal letterboxing
                val scaledPhotoHeight = (containerWidth / photoRatio).toInt()
                val letterboxHeight = (containerHeight - scaledPhotoHeight) / 2

                Log.d(TAG, "Portrait photo in portrait container (photo relatively wider): letterboxHeight = $letterboxHeight")

                if (letterboxHeight >= 1) {
                    configureHorizontalLetterboxViews(letterboxHeight)
                    applyLetterboxMode(drawable, letterboxHeight, LETTERBOX_ORIENTATION_HORIZONTAL)
                    isLetterboxActive = true
                    activeLetterboxOrientation = LETTERBOX_ORIENTATION_HORIZONTAL
                    return true
                }
            }
            // Otherwise try vertical letterboxing (left/right) if container is wider relative to photo
            else if (photoRatio < containerRatio) {
                // Photo is narrower relative to its height than container - apply vertical letterboxing
                val scaledPhotoWidth = (containerHeight * photoRatio).toInt()
                val letterboxWidth = (containerWidth - scaledPhotoWidth) / 2

                Log.d(TAG, "Portrait photo in portrait container (photo relatively narrower): letterboxWidth = $letterboxWidth")

                if (letterboxWidth >= 1) {
                    configureVerticalLetterboxViews(letterboxWidth)
                    applyLetterboxMode(drawable, letterboxWidth, LETTERBOX_ORIENTATION_VERTICAL)
                    isLetterboxActive = true
                    activeLetterboxOrientation = LETTERBOX_ORIENTATION_VERTICAL
                    return true
                }
            }

            // If we get here, the photo and container have the same ratio or letterboxing is too small
            Log.d(TAG, "Portrait photo in portrait container: no letterboxing needed or too small")
        }
        // If we get here, no letterboxing was applied
        Log.d(TAG, "No letterboxing condition met")
        hideAllLetterboxing()
        return false
    }

    private fun hideAllLetterboxing() {
        // Hide horizontal letterbox views
        topLetterboxView?.visibility = View.GONE
        bottomLetterboxView?.visibility = View.GONE

        // Hide vertical letterbox views
        leftLetterboxView?.visibility = View.GONE
        rightLetterboxView?.visibility = View.GONE

        isLetterboxActive = false
        activeLetterboxOrientation = 0
    }

    private fun applyAmbientCloudsLetterbox(drawable: Drawable, size: Int, orientation: Int) {
        if (orientation == LETTERBOX_ORIENTATION_HORIZONTAL) {
            // Handle horizontal letterboxing (top/bottom)
            // Check if we already have ambient bitmaps for this drawable
            val cacheKey = System.identityHashCode(drawable).toString()

            if (cachedTopAmbientBitmap != null && cachedBottomAmbientBitmap != null && lastAmbientCacheKey == cacheKey) {
                // Use existing cached ambient effect
                Log.d(TAG, "Using cached horizontal ambient effect")
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
                    val colors = extractColorsFromPalette(palette, bitmap)

                    // Create bitmaps for top and bottom letterbox areas
                    val topAmbientBitmap = createPurelyRandomCloudEffect(bitmap.width, size, colors)
                    val bottomAmbientBitmap = createPurelyRandomCloudEffect(bitmap.width, size, colors)

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
                        Log.e(TAG, "Error applying horizontal ambient lighting effect", e)
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
        } else {
            // Handle vertical letterboxing (left/right)
            // Check if we already have ambient bitmaps for this drawable
            val cacheKey = System.identityHashCode(drawable).toString()

            if (cachedLeftAmbientBitmap != null && cachedRightAmbientBitmap != null && lastVerticalAmbientCacheKey == cacheKey) {
                // Use existing cached ambient effect
                Log.d(TAG, "Using cached vertical ambient effect")
                leftLetterboxView?.setImageBitmap(cachedLeftAmbientBitmap)
                rightLetterboxView?.setImageBitmap(cachedRightAmbientBitmap)
                return
            }

            // Start with dark gray placeholder
            leftLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))
            rightLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))

            // Generate new ambient effect in background
            managerScope.launch(Dispatchers.Default) {
                try {
                    // Convert drawable to bitmap for processing
                    val bitmap = drawableToBitmap(drawable)
                    if (bitmap == null) {
                        Log.e(TAG, "Failed to convert drawable to bitmap for vertical ambient lighting effect")
                        return@launch
                    }

                    // Sample dominant colors from the image using Palette API
                    val palette = Palette.from(bitmap).generate()

                    // Extract up to 6 distinct colors from the palette
                    val colors = extractColorsFromPalette(palette, bitmap)

                    // Create bitmaps for left and right letterbox areas
                    val leftAmbientBitmap = createPurelyRandomCloudEffect(size, bitmap.height, colors)
                    val rightAmbientBitmap = createPurelyRandomCloudEffect(size, bitmap.height, colors)

                    // Cache for reuse
                    cachedLeftAmbientBitmap = leftAmbientBitmap
                    cachedRightAmbientBitmap = rightAmbientBitmap
                    lastVerticalAmbientCacheKey = cacheKey

                    // Apply on main thread - simple direct application
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            leftLetterboxView?.setImageBitmap(leftAmbientBitmap)
                            rightLetterboxView?.setImageBitmap(rightAmbientBitmap)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error applying vertical ambient lighting effect", e)
                        withContext(Dispatchers.Main) {
                            if (isActive) {
                                // Fallback to dark color in case of error
                                leftLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))
                                rightLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 10, 10, 10)))
                            }
                        }
                    } else {
                        Log.d(TAG, "Vertical ambient lighting processing was cancelled")
                    }
                }
            }
        }
    }

    private fun extractColorsFromPalette(palette: Palette, bitmap: Bitmap): List<Int> {
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

        return colors
    }

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

    private fun applyAmbientColumnsLetterbox(drawable: Drawable, size: Int, orientation: Int) {
        if (orientation == LETTERBOX_ORIENTATION_HORIZONTAL) {
            // Handle horizontal letterboxing (top/bottom)
            val cacheKey = System.identityHashCode(drawable).toString()

            if (cachedTopAmbientColumnsBitmap != null && cachedBottomAmbientColumnsBitmap != null &&
                lastAmbientColumnsCacheKey == cacheKey) {
                // Use existing cached ambient effect
                Log.d(TAG, "Using cached horizontal ambient columns effect")
                applyAmbientBitmapsToHorizontalViews(cachedTopAmbientColumnsBitmap, cachedBottomAmbientColumnsBitmap)
                return
            }

            // Start with dark gray placeholder
            topLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
            bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))

            // Process ambient lighting in background
            managerScope.launch(Dispatchers.Default) {
                try {
                    // Convert drawable to bitmap for processing
                    val bitmap = drawableToBitmap(drawable)
                    if (bitmap == null) {
                        Log.e(TAG, "Failed to convert drawable to bitmap for ambient lighting effect")
                        return@launch
                    }

                    // This is a critical change: grab the proper width from the container view
                    val containerWidth = withContext(Dispatchers.Main) {
                        containerView?.width ?: bitmap.width
                    }

                    // Sample colors from the edge of the image
                    val topColors = sampleEdgeColors(bitmap, true, 50)
                    val bottomColors = sampleEdgeColors(bitmap, false, 50)

                    // Filter color points to remove very narrow segments
                    val filteredTopColors = filterNarrowColorSegments(topColors, bitmap.width)
                    val filteredBottomColors = filterNarrowColorSegments(bottomColors, bitmap.width)

                    // Create the gradient bitmaps using the EXACT container width
                    val topBitmap = createFullWidthGradientBitmap(
                        containerWidth,
                        size,
                        filteredTopColors,
                        true
                    )

                    val bottomBitmap = createFullWidthGradientBitmap(
                        containerWidth,
                        size,
                        filteredBottomColors,
                        false
                    )

                    // Cache for reuse
                    cachedTopAmbientColumnsBitmap = topBitmap
                    cachedBottomAmbientColumnsBitmap = bottomBitmap
                    lastAmbientColumnsCacheKey = cacheKey

                    // Apply on main thread
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            applyAmbientBitmapsToHorizontalViews(topBitmap, bottomBitmap)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error applying horizontal ambient columns effect", e)
                        withContext(Dispatchers.Main) {
                            if (isActive) {
                                // Fallback to dark gray in case of error
                                topLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
                                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
                            }
                        }
                    } else {
                        Log.d(TAG, "Horizontal ambient columns processing was cancelled")
                    }
                }
            }
        } else {
            // Handle vertical letterboxing (left/right)
            val cacheKey = System.identityHashCode(drawable).toString()

            if (cachedLeftAmbientColumnsBitmap != null && cachedRightAmbientColumnsBitmap != null &&
                lastVerticalAmbientColumnsCacheKey == cacheKey) {
                // Use existing cached ambient effect
                Log.d(TAG, "Using cached vertical ambient columns effect")
                applyAmbientBitmapsToVerticalViews(cachedLeftAmbientColumnsBitmap, cachedRightAmbientColumnsBitmap)
                return
            }

            // Start with dark gray placeholder
            leftLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
            rightLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))

            // Process ambient lighting in background
            managerScope.launch(Dispatchers.Default) {
                try {
                    // Convert drawable to bitmap for processing
                    val bitmap = drawableToBitmap(drawable)
                    if (bitmap == null) {
                        Log.e(TAG, "Failed to convert drawable to bitmap for ambient lighting effect")
                        return@launch
                    }

                    // This is a critical change: grab the proper height from the container view
                    val containerHeight = withContext(Dispatchers.Main) {
                        containerView?.height ?: bitmap.height
                    }

                    // Sample colors from the left and right edges of the image
                    val leftColors = sampleVerticalEdgeColors(bitmap, true, 50)
                    val rightColors = sampleVerticalEdgeColors(bitmap, false, 50)

                    // Filter color points to remove very narrow segments
                    val filteredLeftColors = filterNarrowColorSegments(leftColors, bitmap.height)
                    val filteredRightColors = filterNarrowColorSegments(rightColors, bitmap.height)

                    // Create the gradient bitmaps using the EXACT container height
                    val leftBitmap = createFullHeightGradientBitmap(
                        size,
                        containerHeight,
                        filteredLeftColors,
                        true
                    )

                    val rightBitmap = createFullHeightGradientBitmap(
                        size,
                        containerHeight,
                        filteredRightColors,
                        false
                    )

                    // Cache for reuse
                    cachedLeftAmbientColumnsBitmap = leftBitmap
                    cachedRightAmbientColumnsBitmap = rightBitmap
                    lastVerticalAmbientColumnsCacheKey = cacheKey

                    // Apply on main thread
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            applyAmbientBitmapsToVerticalViews(leftBitmap, rightBitmap)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error applying vertical ambient columns effect", e)
                        withContext(Dispatchers.Main) {
                            if (isActive) {
                                // Fallback to dark gray in case of error
                                leftLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
                                rightLetterboxView?.setImageDrawable(ColorDrawable(Color.argb(255, 20, 20, 20)))
                            }
                        }
                    } else {
                        Log.d(TAG, "Vertical ambient columns processing was cancelled")
                    }
                }
            }
        }
    }

    private fun applyAmbientBitmapsToHorizontalViews(topBitmap: Bitmap?, bottomBitmap: Bitmap?) {
        // Set the letterbox view container backgrounds to black first
        val parent1 = topLetterboxView?.parent as? ViewGroup
        val parent2 = bottomLetterboxView?.parent as? ViewGroup

        parent1?.setBackgroundColor(Color.BLACK)
        parent2?.setBackgroundColor(Color.BLACK)

        // Apply critical settings to the letterbox views
        topLetterboxView?.apply {
            layoutParams = layoutParams.apply {
                // Ensure width matches parent exactly
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = false
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.BLACK)
            setImageBitmap(topBitmap)
        }

        bottomLetterboxView?.apply {
            layoutParams = layoutParams.apply {
                // Ensure width matches parent exactly
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = false
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.BLACK)
            setImageBitmap(bottomBitmap)
        }
    }

    private fun applyAmbientBitmapsToVerticalViews(leftBitmap: Bitmap?, rightBitmap: Bitmap?) {
        // Set the letterbox view container backgrounds to black first
        val parent1 = leftLetterboxView?.parent as? ViewGroup
        val parent2 = rightLetterboxView?.parent as? ViewGroup

        parent1?.setBackgroundColor(Color.BLACK)
        parent2?.setBackgroundColor(Color.BLACK)

        // Apply critical settings to the letterbox views
        leftLetterboxView?.apply {
            layoutParams = layoutParams.apply {
                // Ensure height matches parent exactly
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = false
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.BLACK)
            setImageBitmap(leftBitmap)
        }

        rightLetterboxView?.apply {
            layoutParams = layoutParams.apply {
                // Ensure height matches parent exactly
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            scaleType = ImageView.ScaleType.FIT_XY
            adjustViewBounds = false
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.BLACK)
            setImageBitmap(rightBitmap)
        }
    }

    private fun createFullWidthGradientBitmap(width: Int, height: Int, colorPoints: List<Pair<Float, Int>>, isTopEdge: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill completely with black first
        canvas.drawColor(Color.BLACK)

        // If no color points, just return a black bitmap
        if (colorPoints.isEmpty()) {
            return bitmap
        }

        // Directly draw color rectangles across the full width
        for (i in 0 until colorPoints.size - 1) {
            val (startX, startColor) = colorPoints[i]
            val (endX, endColor) = colorPoints[i + 1]

            // Convert normalized coordinates to pixel positions
            val x1 = (startX * width).toInt()
            val x2 = (endX * width).toInt()

            // Skip very narrow segments
            if (x2 - x1 < MIN_COLOR_SEGMENT_WIDTH) continue

            // Create a gradient paint
            val paint = Paint().apply {
                shader = LinearGradient(
                    x1.toFloat(), 0f,
                    x2.toFloat(), 0f,
                    startColor,
                    endColor,
                    Shader.TileMode.CLAMP
                )
                isAntiAlias = true
            }

            // Draw the gradient rectangle
            canvas.drawRect(x1.toFloat(), 0f, x2.toFloat(), height.toFloat(), paint)
        }

        // Apply vertical gradient for depth
        val verticalPaint = Paint().apply {
            shader = LinearGradient(
                0f, if (isTopEdge) height.toFloat() else 0f,
                0f, if (isTopEdge) 0f else height.toFloat(),
                intArrayOf(
                    Color.argb(255, 0, 0, 0),  // Full black
                    Color.argb(0, 0, 0, 0)     // Transparent
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), verticalPaint)

        // Apply a subtle blur for smoother gradients
        return applySimpleBlur(bitmap, 8f) ?: bitmap
    }

    private fun createFullHeightGradientBitmap(width: Int, height: Int, colorPoints: List<Pair<Float, Int>>, isLeftEdge: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill completely with black first
        canvas.drawColor(Color.BLACK)

        // If no color points, just return a black bitmap
        if (colorPoints.isEmpty()) {
            return bitmap
        }

        // Directly draw color rectangles across the full height
        for (i in 0 until colorPoints.size - 1) {
            val (startY, startColor) = colorPoints[i]
            val (endY, endColor) = colorPoints[i + 1]

            // Convert normalized coordinates to pixel positions
            val y1 = (startY * height).toInt()
            val y2 = (endY * height).toInt()

            // Skip very narrow segments
            if (y2 - y1 < MIN_COLOR_SEGMENT_WIDTH) continue

            // Create a gradient paint
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, y1.toFloat(),
                    0f, y2.toFloat(),
                    startColor,
                    endColor,
                    Shader.TileMode.CLAMP
                )
                isAntiAlias = true
            }

            // Draw the gradient rectangle
            canvas.drawRect(0f, y1.toFloat(), width.toFloat(), y2.toFloat(), paint)
        }

        // Apply horizontal gradient for depth
        val horizontalPaint = Paint().apply {
            shader = LinearGradient(
                if (isLeftEdge) width.toFloat() else 0f, 0f,
                if (isLeftEdge) 0f else width.toFloat(), 0f,
                intArrayOf(
                    Color.argb(255, 0, 0, 0),  // Full black
                    Color.argb(0, 0, 0, 0)     // Transparent
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), horizontalPaint)

        // Apply a subtle blur for smoother gradients
        return applySimpleBlur(bitmap, 8f) ?: bitmap
    }

    private fun enhanceColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // Reduce saturation for a lighter, less intense look
        hsv[1] = (hsv[1] * 0.85f).coerceAtMost(0.8f)

        // Boost brightness significantly to make colors even lighter
        hsv[2] = (hsv[2] * 1.75f + 0.2f).coerceAtMost(1.0f)

        // Use a moderate alpha
        return Color.HSVToColor(160, hsv)
    }

    private fun applySimpleBlur(source: Bitmap, radius: Float): Bitmap? {
        try {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint()
            paint.isAntiAlias = true
            paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawBitmap(source, 0f, 0f, paint)
            return output
        } catch (e: Exception) {
            Log.e(TAG, "Error applying simple blur", e)
            return null
        }
    }

    private fun configureHorizontalLetterboxViews(letterboxHeight: Int) {
        // Hide vertical letterboxing first
        leftLetterboxView?.visibility = View.GONE
        rightLetterboxView?.visibility = View.GONE

        // Configure horizontal letterboxing
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

    private fun configureVerticalLetterboxViews(letterboxWidth: Int) {
        // Hide horizontal letterboxing first
        topLetterboxView?.visibility = View.GONE
        bottomLetterboxView?.visibility = View.GONE

        // Configure vertical letterboxing
        leftLetterboxView?.let { view ->
            val params = view.layoutParams
            params.width = letterboxWidth
            view.layoutParams = params
            view.visibility = View.VISIBLE
        }

        rightLetterboxView?.let { view ->
            val params = view.layoutParams
            params.width = letterboxWidth
            view.layoutParams = params
            view.visibility = View.VISIBLE
        }
    }

    private fun filterNarrowColorSegments(colorPoints: List<Pair<Float, Int>>, totalSize: Int): List<Pair<Float, Int>> {
        if (colorPoints.size <= 2) {
            return colorPoints // Don't filter if we only have two points (full width/height)
        }

        val result = mutableListOf<Pair<Float, Int>>()

        // Always include first and last points to ensure full coverage
        result.add(colorPoints.first())

        // Check segments between points
        for (i in 1 until colorPoints.size - 1) {
            val prevPos = colorPoints[i-1].first
            val currentPos = colorPoints[i].first
            val nextPos = colorPoints[i+1].first

            // Calculate segment widths in pixels
            val prevSegmentSize = (currentPos - prevPos) * totalSize
            val nextSegmentSize = (nextPos - currentPos) * totalSize

            // Only include this point if either adjacent segment is wide enough
            if (prevSegmentSize >= MIN_COLOR_SEGMENT_WIDTH || nextSegmentSize >= MIN_COLOR_SEGMENT_WIDTH) {
                result.add(colorPoints[i])
            }
        }

        // Always include last point
        result.add(colorPoints.last())

        return result
    }

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

    private fun sampleVerticalEdgeColors(bitmap: Bitmap, isLeftEdge: Boolean, sampleCount: Int): List<Pair<Float, Int>> {
        val result = mutableListOf<Pair<Float, Int>>()
        val height = bitmap.height

        // Determine sampling area width
        val sampleAreaWidth = (bitmap.width * 0.08).toInt().coerceAtLeast(2)
        val startX = if (isLeftEdge) 0 else bitmap.width - sampleAreaWidth
        val endX = if (isLeftEdge) sampleAreaWidth else bitmap.width

        // Sample at regular intervals
        for (i in 0 until sampleCount) {
            val normalizedY = i / (sampleCount - 1f)
            val sampleY = (normalizedY * (height - 1)).toInt()

            // Average color from a small patch around this point
            val patchSize = 3
            val sampleColors = mutableListOf<Int>()

            for (x in startX until endX) {
                for (y in (sampleY - patchSize).coerceAtLeast(0)..(sampleY + patchSize).coerceAtMost(height - 1)) {
                    sampleColors.add(bitmap.getPixel(x, y))
                }
            }

            val avgColor = averageColors(sampleColors)
            val enhancedColor = enhanceColor(avgColor)
            result.add(Pair(normalizedY, enhancedColor))
        }

        return result
    }

    private fun averageColors(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.BLACK

        var redSum = 0
        var greenSum = 0
        var blueSum = 0
        var alphaSum = 0

        for (color in colors) {
            redSum += Color.red(color)
            greenSum += Color.green(color)
            blueSum += Color.blue(color)
            alphaSum += Color.alpha(color)
        }

        val count = colors.size
        return Color.argb(
            alphaSum / count,
            redSum / count,
            greenSum / count,
            blueSum / count
        )
    }

    private fun applyLetterboxMode(drawable: Drawable, size: Int, orientation: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val mode = prefs.getString(PREF_KEY_LETTERBOX_MODE, DEFAULT_LETTERBOX_MODE) ?: DEFAULT_LETTERBOX_MODE

        // Debug statement to verify the selected mode
        Log.d(TAG, "Letterbox mode: $mode (size: $size, orientation: $orientation)")

        // Cancel any existing processing jobs first
        managerScope.coroutineContext.cancelChildren()

        // Apply default placeholders based on orientation
        if (orientation == LETTERBOX_ORIENTATION_HORIZONTAL) {
            // Horizontal letterboxing (top/bottom)
            val placeholder = when (mode) {
                LETTERBOX_MODE_BLACK -> ColorDrawable(Color.BLACK)
                LETTERBOX_MODE_BLUR -> ColorDrawable(Color.parseColor("#222222"))
                LETTERBOX_MODE_AMBIENT, LETTERBOX_MODE_AMBIENT_CLOUDS -> ColorDrawable(Color.BLACK)
                else -> ColorDrawable(Color.BLACK)
            }
            topLetterboxView?.setImageDrawable(placeholder)
            bottomLetterboxView?.setImageDrawable(placeholder)

            // Make sure the views are visible
            topLetterboxView?.visibility = View.VISIBLE
            bottomLetterboxView?.visibility = View.VISIBLE

            // Log the visibility state
            Log.d(TAG, "topLetterboxView visibility: ${topLetterboxView?.visibility == View.VISIBLE}, " +
                    "bottomLetterboxView visibility: ${bottomLetterboxView?.visibility == View.VISIBLE}")
        } else {
            // Vertical letterboxing (left/right)
            val placeholder = when (mode) {
                LETTERBOX_MODE_BLACK -> ColorDrawable(Color.BLACK)
                LETTERBOX_MODE_BLUR -> ColorDrawable(Color.parseColor("#222222"))
                LETTERBOX_MODE_AMBIENT, LETTERBOX_MODE_AMBIENT_CLOUDS -> ColorDrawable(Color.BLACK)
                else -> ColorDrawable(Color.BLACK)
            }
            leftLetterboxView?.setImageDrawable(placeholder)
            rightLetterboxView?.setImageDrawable(placeholder)

            // Make sure the views are visible
            leftLetterboxView?.visibility = View.VISIBLE
            rightLetterboxView?.visibility = View.VISIBLE

            // Log the visibility state
            Log.d(TAG, "leftLetterboxView visibility: ${leftLetterboxView?.visibility == View.VISIBLE}, " +
                    "rightLetterboxView visibility: ${rightLetterboxView?.visibility == View.VISIBLE}")
        }

        // Apply the actual effect
        when (mode) {
            LETTERBOX_MODE_BLACK -> {
                // Already applied placeholders
                Log.d(TAG, "Using black letterbox mode")
            }
            LETTERBOX_MODE_BLUR -> {
                Log.d(TAG, "Applying blur letterbox mode")
                applyBlurLetterbox(drawable, size, orientation)
            }
            LETTERBOX_MODE_AMBIENT -> {
                Log.d(TAG, "Applying ambient columns letterbox mode")
                applyAmbientColumnsLetterbox(drawable, size, orientation)
            }
            LETTERBOX_MODE_AMBIENT_CLOUDS -> {
                Log.d(TAG, "Applying ambient clouds letterbox mode")
                applyAmbientCloudsLetterbox(drawable, size, orientation)
            }
        }
    }

    private fun applyBlurLetterbox(drawable: Drawable, size: Int, orientation: Int) {
        if (orientation == LETTERBOX_ORIENTATION_HORIZONTAL) {
            // Horizontal letterboxing (top/bottom)

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
                        size.coerceAtMost(originalBitmap.height / 3)
                    )

                    val bottomEdgeBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        0,
                        originalBitmap.height - size.coerceAtMost(originalBitmap.height / 3),
                        originalBitmap.width,
                        size.coerceAtMost(originalBitmap.height / 3)
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
                        Log.e(TAG, "Error applying horizontal edge blur effect", e)
                        withContext(Dispatchers.Main) {
                            if (isActive) {
                                // Fallback to black in case of error
                                topLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                                bottomLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                            }
                        }
                    } else {
                        Log.d(TAG, "Horizontal edge blur processing was cancelled")
                    }
                }
            }
        } else {
            // Vertical letterboxing (left/right)

            // Use existing blurred edge bitmaps if available
            if (cachedBlurredLeftBitmap != null && cachedBlurredRightBitmap != null) {
                leftLetterboxView?.setImageBitmap(cachedBlurredLeftBitmap)
                rightLetterboxView?.setImageBitmap(cachedBlurredRightBitmap)
                return
            }

            // Start with placeholder
            leftLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
            rightLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))

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

                    // Extract the left and right portions exactly as they are
                    val leftEdgeBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        0,
                        0,
                        size.coerceAtMost(originalBitmap.width / 3),
                        originalBitmap.height
                    )

                    val rightEdgeBitmap = Bitmap.createBitmap(
                        originalBitmap,
                        originalBitmap.width - size.coerceAtMost(originalBitmap.width / 3),
                        0,
                        size.coerceAtMost(originalBitmap.width / 3),
                        originalBitmap.height
                    )

                    // Apply blur effect directly to these edges
                    val blurRadius = blurIntensity.coerceAtMost(MAX_BLUR_RADIUS)

                    // Scale down for better blurring performance
                    val scaleFactor = 0.5f
                    val scaledLeftBitmap = Bitmap.createScaledBitmap(
                        leftEdgeBitmap,
                        (leftEdgeBitmap.width * scaleFactor).toInt(),
                        (leftEdgeBitmap.height * scaleFactor).toInt(),
                        true
                    )

                    val scaledRightBitmap = Bitmap.createScaledBitmap(
                        rightEdgeBitmap,
                        (rightEdgeBitmap.width * scaleFactor).toInt(),
                        (rightEdgeBitmap.height * scaleFactor).toInt(),
                        true
                    )

                    // Recycle original edges
                    leftEdgeBitmap.recycle()
                    rightEdgeBitmap.recycle()

                    // Apply blur
                    val blurredLeftBitmap = applyRenderScriptBlur(scaledLeftBitmap, blurRadius)
                    val blurredRightBitmap = applyRenderScriptBlur(scaledRightBitmap, blurRadius)

                    // Recycle scaled bitmaps
                    scaledLeftBitmap.recycle()
                    scaledRightBitmap.recycle()

                    if (blurredLeftBitmap == null || blurredRightBitmap == null) {
                        Log.e(TAG, "Failed to create blurred edge bitmaps")
                        return@launch
                    }

                    // Scale back to full height but maintain original aspect ratio
                    val leftFinalBitmap = Bitmap.createScaledBitmap(
                        blurredLeftBitmap,
                        (blurredLeftBitmap.width * (originalBitmap.height.toFloat() / blurredLeftBitmap.height)).toInt(),
                        originalBitmap.height,
                        true
                    )

                    val rightFinalBitmap = Bitmap.createScaledBitmap(
                        blurredRightBitmap,
                        (blurredRightBitmap.width * (originalBitmap.height.toFloat() / blurredRightBitmap.height)).toInt(),
                        originalBitmap.height,
                        true
                    )

                    // Recycle blurred bitmaps
                    blurredLeftBitmap.recycle()
                    blurredRightBitmap.recycle()

                    // Save for reuse
                    cachedBlurredLeftBitmap = leftFinalBitmap
                    cachedBlurredRightBitmap = rightFinalBitmap

                    // Apply on main thread
                    withContext(Dispatchers.Main) {
                        if (isActive) {
                            leftLetterboxView?.setImageBitmap(leftFinalBitmap)
                            rightLetterboxView?.setImageBitmap(rightFinalBitmap)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error applying vertical edge blur effect", e)
                        withContext(Dispatchers.Main) {
                            if (isActive) {
                                // Fallback to black in case of error
                                leftLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                                rightLetterboxView?.setImageDrawable(ColorDrawable(Color.BLACK))
                            }
                        }
                    } else {
                        Log.d(TAG, "Vertical edge blur processing was cancelled")
                    }
                }
            }
        }
    }

    private fun applyRenderScriptBlur(bitmap: Bitmap, radius: Float): Bitmap? {
        return try {
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val outAlloc = Allocation.createFromBitmap(rs, output)

            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            blur.setRadius(radius)
            blur.setInput(input)
            blur.forEach(outAlloc)

            outAlloc.copyTo(output)

            input.destroy()
            outAlloc.destroy()
            blur.destroy()
            rs.destroy()
            output
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript blur error", e)
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        try {
            // If drawable is already a BitmapDrawable, just return its bitmap
            if (drawable is BitmapDrawable) {
                return drawable.bitmap
            }

            // Use the intrinsic dimensions if available, otherwise a default size
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1

            // Create a bitmap with the drawable's dimensions
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Draw the drawable onto the bitmap
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to bitmap", e)
            return null
        }
    }
}