package com.photostreamr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.photostreamr.PhotoRepository
import com.photostreamr.glide.GlideApp
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class MultiPhotoLayoutManager @Inject constructor(
    private val context: Context,
    private val photoManager: PhotoRepository,
    private val photoPreloader: PhotoPreloader
) {
    companion object {
        private const val TAG = "MultiPhotoLayoutManager"

        // Layout types
        const val LAYOUT_TYPE_2_VERTICAL = 0
        const val LAYOUT_TYPE_2_HORIZONTAL = 1
        const val LAYOUT_TYPE_3_MAIN_LEFT = 2
        const val LAYOUT_TYPE_3_MAIN_RIGHT = 3
        const val LAYOUT_TYPE_4_GRID = 4

        // Add new dynamic layout types
        const val LAYOUT_TYPE_DYNAMIC = 5
        const val LAYOUT_TYPE_DYNAMIC_COLLAGE = 6
        const val LAYOUT_TYPE_DYNAMIC_MASONRY = 7

        // Minimum number of photos for dynamic layouts
        const val MIN_PHOTOS_DYNAMIC = 3
        private const val MAX_PHOTOS_DYNAMIC = 7

        // Border settings
        private const val BORDER_WIDTH = 8f
        private const val BORDER_COLOR = Color.WHITE
    }

    // Job for coroutine management
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    // Cache for loaded images to be composed
    private val imageCache = ConcurrentHashMap<String, Bitmap>()

    // Callback interface for when templates are ready
    interface TemplateReadyCallback {
        fun onTemplateReady(result: Drawable, layoutType: Int)
        fun onTemplateError(error: String)
    }

    private fun safeGetBitmap(url: String): Bitmap? {
        // Check if the bitmap exists in cache and is not recycled
        val cached = imageCache[url]
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        // Remove any recycled bitmaps from cache
        if (cached != null && cached.isRecycled) {
            imageCache.remove(url)
        }

        return null
    }


    /**
     * Create a multi-photo template based on the specified layout type
     */
    fun createTemplate(
        containerWidth: Int,
        containerHeight: Int,
        currentPhotoIndex: Int,
        layoutType: Int,
        callback: TemplateReadyCallback
    ) {
        // Clean up resources before creating a new template
        prepareCacheForNewTemplate()

        // Add validation and fallback for container dimensions
        if (containerWidth <= 0 || containerHeight <= 0) {
            // Try to get dimensions directly from context if passed dimensions are invalid
            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            if (screenWidth > 0 && screenHeight > 0) {
                Log.d(TAG, "Using screen dimensions as fallback: ${screenWidth}x${screenHeight}")

                // Retry with screen dimensions
                createTemplateWithValidDimensions(
                    screenWidth,
                    screenHeight,
                    currentPhotoIndex,
                    layoutType,
                    callback
                )
                return
            } else {
                callback.onTemplateError("Invalid container dimensions and couldn't get screen dimensions")
                return
            }
        }

        // Continue with valid dimensions
        createTemplateWithValidDimensions(containerWidth, containerHeight, currentPhotoIndex, layoutType, callback)
    }

    // Update createTemplate method in MultiPhotoLayoutManager.kt
    fun createTemplateWithValidDimensions(
        width: Int,
        height: Int,
        currentPhotoIndex: Int,
        layoutType: Int,
        callback: TemplateReadyCallback
    ) {
        managerScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                if (photoCount < 2) {
                    callback.onTemplateError("Not enough photos available")
                    return@launch
                }

                // Determine indices of photos to include in template
                val requiredCount = getRequiredPhotoCount(layoutType)
                val photoIndices = getPhotoIndices(currentPhotoIndex, photoCount, requiredCount)

                // Load all required photos
                val photoBitmaps = withContext(Dispatchers.IO) {
                    loadPhotos(photoIndices)
                }

                if (photoBitmaps.size < getRequiredPhotoCount(layoutType)) {
                    callback.onTemplateError("Failed to load enough photos")
                    return@launch
                }

                // Create the template based on layout type
                val templateBitmap = when (layoutType) {
                    LAYOUT_TYPE_2_VERTICAL -> createTwoPhotoVerticalTemplate(
                        width, height, photoBitmaps
                    )
                    LAYOUT_TYPE_2_HORIZONTAL -> createTwoPhotoHorizontalTemplate(
                        width, height, photoBitmaps
                    )
                    LAYOUT_TYPE_3_MAIN_LEFT -> createThreePhotoMainLeftTemplate(
                        width, height, photoBitmaps
                    )
                    LAYOUT_TYPE_3_MAIN_RIGHT -> createThreePhotoMainRightTemplate(
                        width, height, photoBitmaps
                    )
                    LAYOUT_TYPE_4_GRID -> createFourPhotoGridTemplate(
                        width, height, photoBitmaps
                    )
                    LAYOUT_TYPE_DYNAMIC -> createDynamicTemplate(
                        width, height, photoBitmaps
                    )
                    LAYOUT_TYPE_DYNAMIC_COLLAGE -> createDynamicCollageTemplate(
                        width, height, photoBitmaps
                    )
                    LAYOUT_TYPE_DYNAMIC_MASONRY -> createDynamicMasonryTemplate(
                        width, height, photoBitmaps
                    )
                    else -> {
                        callback.onTemplateError("Unknown layout type")
                        return@launch
                    }
                }

                // Convert to drawable and notify callback
                val templateDrawable = BitmapDrawable(context.resources, templateBitmap)
                callback.onTemplateReady(templateDrawable, layoutType)

            } catch (e: Exception) {
                Log.e(TAG, "Error creating template", e)
                callback.onTemplateError("Failed to create template: ${e.message}")
            }
        }
    }

    /**
     * Get the number of photos required for a specific layout type
     */
    private fun getRequiredPhotoCount(layoutType: Int): Int {
        return when (layoutType) {
            LAYOUT_TYPE_2_VERTICAL, LAYOUT_TYPE_2_HORIZONTAL -> 2
            LAYOUT_TYPE_3_MAIN_LEFT, LAYOUT_TYPE_3_MAIN_RIGHT -> 3
            LAYOUT_TYPE_4_GRID -> 4
            LAYOUT_TYPE_DYNAMIC -> MIN_PHOTOS_DYNAMIC
            LAYOUT_TYPE_DYNAMIC_COLLAGE -> MIN_PHOTOS_DYNAMIC
            LAYOUT_TYPE_DYNAMIC_MASONRY -> MIN_PHOTOS_DYNAMIC
            else -> 2
        }
    }

    /**
     * Get photo indices to use in template
     */
    private fun getPhotoIndices(currentIndex: Int, totalPhotos: Int, requiredCount: Int): List<Int> {
        val indices = mutableListOf<Int>()
        val usedIndices = mutableSetOf<Int>()

        // Start with current photo
        indices.add(currentIndex)
        usedIndices.add(currentIndex)

        // If we don't have enough photos, just return what we can
        if (totalPhotos < requiredCount) {
            // Add whatever additional photos we can
            var nextIndex = (currentIndex + 1) % totalPhotos
            while (indices.size < totalPhotos && nextIndex != currentIndex) {
                indices.add(nextIndex)
                nextIndex = (nextIndex + 1) % totalPhotos
            }

            Log.w(TAG, "Not enough unique photos (needed: $requiredCount, available: $totalPhotos)")
            return indices
        }

        // We have enough photos, ensure we get unique ones
        val random = java.util.Random()

        // Add subsequent photos, ensuring they're unique
        while (indices.size < requiredCount) {
            // Try to get a random index not already used
            var nextIndex: Int
            var attempts = 0

            do {
                nextIndex = random.nextInt(totalPhotos)
                attempts++

                // If we're struggling to find unique photos, use sequential as backup
                if (attempts > 50) {
                    for (i in 0 until totalPhotos) {
                        if (!usedIndices.contains(i)) {
                            nextIndex = i
                            break
                        }
                    }
                    break
                }
            } while (usedIndices.contains(nextIndex))

            // Add the unique index
            indices.add(nextIndex)
            usedIndices.add(nextIndex)
        }

        Log.d(TAG, "Selected unique photo indices: $indices")
        return indices
    }

    /**
     * Load photos by their indices
     */
    private suspend fun loadPhotos(indices: List<Int>): List<Bitmap> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Bitmap>()
        val deferredResults = indices.map { index ->
            async {
                try {
                    val photoUrl = photoManager.getPhotoUrl(index) ?: return@async null

                    // Check if we need to use cached version for Google Photos
                    val isGooglePhotosUri = photoUrl.contains("com.google.android.apps.photos") ||
                            photoUrl.contains("googleusercontent.com")

                    val urlToUse = if (isGooglePhotosUri) {
                        photoManager.persistentPhotoCache?.getCachedPhotoUri(photoUrl) ?: return@async null
                    } else {
                        photoUrl
                    }

                    // Safely check for cached bitmap
                    val cachedBitmap = safeGetBitmap(urlToUse)
                    if (cachedBitmap != null) {
                        return@async cachedBitmap
                    }

                    // Check if preloaded
                    val preloadedResource = photoPreloader.getPreloadedResource(urlToUse)
                    if (preloadedResource != null) {
                        try {
                            // Create a copy of the bitmap to avoid recycling issues
                            val source = preloadedResource.toBitmap()
                            val copy = source.copy(source.config, true)
                            imageCache[urlToUse] = copy
                            return@async copy
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting preloaded resource to bitmap", e)
                        }
                    }

                    // Load with Glide - use a try-catch to handle exceptions
                    try {
                        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
                            Glide.with(context)
                                .asBitmap()
                                .load(urlToUse)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .listener(object : RequestListener<Bitmap> {
                                    override fun onLoadFailed(
                                        e: GlideException?,
                                        model: Any?,
                                        target: Target<Bitmap>,
                                        isFirstResource: Boolean
                                    ): Boolean {
                                        Log.e(TAG, "Failed to load bitmap: $urlToUse", e)
                                        continuation.resume(null, null)
                                        return false
                                    }

                                    override fun onResourceReady(
                                        resource: Bitmap,
                                        model: Any,
                                        target: Target<Bitmap>,
                                        dataSource: DataSource,
                                        isFirstResource: Boolean
                                    ): Boolean {
                                        // Make a copy to avoid recycling issues
                                        val copy = resource.copy(resource.config, true)
                                        continuation.resume(copy, null)
                                        return true
                                    }
                                })
                                .submit()
                        }

                        if (bitmap != null && !bitmap.isRecycled) {
                            imageCache[urlToUse] = bitmap
                            return@async bitmap
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception loading bitmap with Glide", e)
                    }

                    return@async null
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading photo bitmap", e)
                    return@async null
                }
            }
        }

        // Await all results and filter out nulls
        deferredResults.awaitAll().filterNotNull().forEach {
            if (!it.isRecycled) {
                results.add(it)
            } else {
                Log.w(TAG, "Filtered out recycled bitmap")
            }
        }

        results
    }

    fun prepareCacheForNewTemplate() {
        // Clear existing bitmaps to prevent resource issues
        val iterator = imageCache.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val bitmap = entry.value
            if (bitmap.isRecycled) {
                // Remove already recycled bitmaps
                iterator.remove()
            }
        }
    }

    /**
     * Create a template with 2 photos stacked vertically
     */
    private fun createTwoPhotoVerticalTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Calculate rectangles for each photo (accounting for border)
        val topRect = Rect(
            0,
            0,
            width,
            height / 2 - (BORDER_WIDTH / 2).toInt()
        )

        val bottomRect = Rect(
            0,
            height / 2 + (BORDER_WIDTH / 2).toInt(),
            width,
            height
        )

        // Draw first photo in top half
        drawPhotoInRect(canvas, photos[0], topRect)

        // Draw second photo in bottom half
        drawPhotoInRect(canvas, photos[1], bottomRect)

        // Draw border between photos
        canvas.drawLine(
            0f,
            height / 2f,
            width.toFloat(),
            height / 2f,
            paint
        )

        return resultBitmap
    }

    /**
     * Create a template with 2 photos side by side horizontally
     */
    private fun createTwoPhotoHorizontalTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Calculate rectangles for each photo (accounting for border)
        val leftRect = Rect(
            0,
            0,
            width / 2 - (BORDER_WIDTH / 2).toInt(),
            height
        )

        val rightRect = Rect(
            width / 2 + (BORDER_WIDTH / 2).toInt(),
            0,
            width,
            height
        )

        // Draw first photo in left half
        drawPhotoInRect(canvas, photos[0], leftRect)

        // Draw second photo in right half
        drawPhotoInRect(canvas, photos[1], rightRect)

        // Draw border between photos
        canvas.drawLine(
            width / 2f,
            0f,
            width / 2f,
            height.toFloat(),
            paint
        )

        return resultBitmap
    }

    /**
     * Create a template with 3 photos, with main photo on left
     */
    private fun createThreePhotoMainLeftTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Calculate divisions (left side is 2/3, right side splits into two equal parts)
        val leftWidth = (width * 0.6).toInt()
        val rightWidth = width - leftWidth

        // Define rectangles for each photo
        val leftRect = Rect(
            0,
            0,
            leftWidth - (BORDER_WIDTH / 2).toInt(),
            height
        )

        val topRightRect = Rect(
            leftWidth + (BORDER_WIDTH / 2).toInt(),
            0,
            width,
            height / 2 - (BORDER_WIDTH / 2).toInt()
        )

        val bottomRightRect = Rect(
            leftWidth + (BORDER_WIDTH / 2).toInt(),
            height / 2 + (BORDER_WIDTH / 2).toInt(),
            width,
            height
        )

        // Draw the photos
        drawPhotoInRect(canvas, photos[0], leftRect)
        drawPhotoInRect(canvas, photos[1], topRightRect)
        drawPhotoInRect(canvas, photos[2], bottomRightRect)

        // Draw borders
        canvas.drawLine(leftWidth.toFloat(), 0f, leftWidth.toFloat(), height.toFloat(), paint)
        canvas.drawLine(
            leftWidth.toFloat(),
            height / 2f,
            width.toFloat(),
            height / 2f,
            paint
        )

        return resultBitmap
    }

    /**
     * Create a template with 3 photos, with main photo on right
     */
    private fun createThreePhotoMainRightTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Calculate divisions (right side is 2/3, left side splits into two equal parts)
        val rightWidth = (width * 0.6).toInt()
        val leftWidth = width - rightWidth

        // Define rectangles for each photo
        val rightRect = Rect(
            leftWidth + (BORDER_WIDTH / 2).toInt(),
            0,
            width,
            height
        )

        val topLeftRect = Rect(
            0,
            0,
            leftWidth - (BORDER_WIDTH / 2).toInt(),
            height / 2 - (BORDER_WIDTH / 2).toInt()
        )

        val bottomLeftRect = Rect(
            0,
            height / 2 + (BORDER_WIDTH / 2).toInt(),
            leftWidth - (BORDER_WIDTH / 2).toInt(),
            height
        )

        // Draw the photos
        drawPhotoInRect(canvas, photos[0], rightRect)
        drawPhotoInRect(canvas, photos[1], topLeftRect)
        drawPhotoInRect(canvas, photos[2], bottomLeftRect)

        // Draw borders
        canvas.drawLine(leftWidth.toFloat(), 0f, leftWidth.toFloat(), height.toFloat(), paint)
        canvas.drawLine(
            0f,
            height / 2f,
            leftWidth.toFloat(),
            height / 2f,
            paint
        )

        return resultBitmap
    }

    /**
     * Create a template with 4 photos in a grid
     */
    private fun createFourPhotoGridTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Calculate rectangles for each photo
        val topLeftRect = Rect(
            0,
            0,
            width / 2 - (BORDER_WIDTH / 2).toInt(),
            height / 2 - (BORDER_WIDTH / 2).toInt()
        )

        val topRightRect = Rect(
            width / 2 + (BORDER_WIDTH / 2).toInt(),
            0,
            width,
            height / 2 - (BORDER_WIDTH / 2).toInt()
        )

        val bottomLeftRect = Rect(
            0,
            height / 2 + (BORDER_WIDTH / 2).toInt(),
            width / 2 - (BORDER_WIDTH / 2).toInt(),
            height
        )

        val bottomRightRect = Rect(
            width / 2 + (BORDER_WIDTH / 2).toInt(),
            height / 2 + (BORDER_WIDTH / 2).toInt(),
            width,
            height
        )

        // Draw the photos
        drawPhotoInRect(canvas, photos[0], topLeftRect)
        drawPhotoInRect(canvas, photos[1], topRightRect)
        drawPhotoInRect(canvas, photos[2], bottomLeftRect)
        drawPhotoInRect(canvas, photos[3], bottomRightRect)

        // Draw borders
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)

        return resultBitmap
    }

    /**
     * Improved method to draw a photo in a specified rectangle while maintaining aspect ratio
     * and ensuring proper filling of the space without edge slivers
     */
    private fun drawPhotoInRect(canvas: Canvas, photo: Bitmap, rect: Rect) {
        // Calculate scaling factors
        val sourceRatio = photo.width.toFloat() / photo.height
        val targetRatio = rect.width().toFloat() / rect.height()

        // Create matrix for transformation
        val matrix = Matrix()

        // We'll always use CENTER_CROP approach for better filling
        // This ensures photo always fills the entire rectangle
        var scale: Float
        var offsetX = 0f
        var offsetY = 0f

        if (sourceRatio > targetRatio) {
            // Source is wider than target: scale to fit height and crop sides
            scale = rect.height().toFloat() / photo.height

            // Calculate how much to crop from sides
            val scaledWidth = photo.width * scale
            val cropX = (scaledWidth - rect.width()) / 2

            // Scale then translate to center the photo
            matrix.setScale(scale, scale)
            matrix.postTranslate(rect.left - cropX, rect.top.toFloat())
        } else {
            // Source is taller than target: scale to fit width and crop top/bottom
            scale = rect.width().toFloat() / photo.width

            // Calculate how much to crop from top/bottom
            val scaledHeight = photo.height * scale
            val cropY = (scaledHeight - rect.height()) / 2

            // Scale then translate to center the photo
            matrix.setScale(scale, scale)
            matrix.postTranslate(rect.left.toFloat(), rect.top - cropY)
        }

        // Add a small zoom factor to ensure no tiny gaps at edges due to rounding
        val zoomFactor = 1.005f
        matrix.postScale(zoomFactor, zoomFactor, rect.centerX().toFloat(), rect.centerY().toFloat())

        // Draw the bitmap with the calculated transformation
        canvas.drawBitmap(photo, matrix, null)

        // Draw debug border to see section boundaries during development
        // val debugPaint = Paint().apply {
        //     style = Paint.Style.STROKE
        //     color = Color.RED
        //     strokeWidth = 2f
        // }
        // canvas.drawRect(rect, debugPaint)
    }

    /**
     * Clear the image cache
     */
    fun clearCache() {
        imageCache.forEach { (_, bitmap) ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        imageCache.clear()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        clearCache()
        managerJob.cancel()
    }

    /**
     * Creates a dynamic template with varied layout
     */
    private fun createDynamicTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        // Choose a dynamic layout style based on available photos
        val effectivePhotos = photos.take(MAX_PHOTOS_DYNAMIC)

        return when (kotlin.random.Random.nextInt(3)) {
            0 -> createDynamicGridTemplate(width, height, effectivePhotos)
            1 -> createDynamicCollageTemplate(width, height, effectivePhotos)
            else -> createDynamicMasonryTemplate(width, height, effectivePhotos)
        }
    }

    /**
     * Creates a dynamic grid template with varied cell sizes
     */
    private fun createDynamicGridTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Determine grid layout based on photo count
        val photoCount = photos.size

        // Create rectangles for photo placement
        val rects = when {
            photoCount <= 3 -> createThreePhotoGrid(width, height)
            photoCount <= 4 -> createFourPhotoGrid(width, height)
            photoCount <= 5 -> createFivePhotoGrid(width, height)
            else -> createSixPlusPhotoGrid(width, height, photoCount)
        }

        // Draw photos into rectangles
        rects.forEachIndexed { index, rect ->
            if (index < photos.size) {
                drawPhotoInRect(canvas, photos[index], rect)
            }
        }

        // Draw borders
        rects.forEach { rect ->
            canvas.drawRect(rect, paint)
        }

        return resultBitmap
    }

    /**
     * Creates a dynamic collage template with overlapping photos
     */
    private fun createDynamicCollageTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Create slightly rotated and overlapping rectangles
        val rects = mutableListOf<Rect>()
        val rotations = mutableListOf<Float>()

        // Size the main photo to be centered and a bit smaller than the screen
        val mainPhotoSize = min(width, height) * 0.7f
        val mainPhotoLeft = (width - mainPhotoSize) / 2
        val mainPhotoTop = (height - mainPhotoSize) / 2

        // Add the main photo rectangle
        rects.add(Rect(
            mainPhotoLeft.toInt(),
            mainPhotoTop.toInt(),
            (mainPhotoLeft + mainPhotoSize).toInt(),
            (mainPhotoTop + mainPhotoSize).toInt()
        ))
        rotations.add(0f)  // No rotation for main photo

        // Add additional photo rectangles with slight overlap and rotation
        val random = kotlin.random.Random
        for (i in 1 until photos.size) {
            // Random size between 40% to 65% of the main photo
            val photoSize = mainPhotoSize * (0.4f + random.nextFloat() * 0.25f)

            // Random position with some overlap with main photo
            val overlap = photoSize * 0.2f
            val left = random.nextFloat() * (width - photoSize + overlap) - overlap/2
            val top = random.nextFloat() * (height - photoSize + overlap) - overlap/2

            rects.add(Rect(
                left.toInt(),
                top.toInt(),
                (left + photoSize).toInt(),
                (top + photoSize).toInt()
            ))

            // Random slight rotation between -10 and 10 degrees
            rotations.add((random.nextFloat() * 20f) - 10f)
        }

        // Draw photos in reverse order so main photo is on top
        for (i in photos.size - 1 downTo 0) {
            if (i < rects.size && i < rotations.size) {
                drawRotatedPhotoInRect(canvas, photos[i], rects[i], rotations[i])
                canvas.drawRect(rects[i], paint)
            }
        }

        return resultBitmap
    }

    /**
     * Creates a dynamic masonry template (like Pinterest)
     */
    private fun createDynamicMasonryTemplate(
        width: Int,
        height: Int,
        photos: List<Bitmap>
    ): Bitmap {
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // Fill with black background
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Determine number of columns based on photo count and orientation
        val numColumns = if (width > height) {
            when {
                photos.size <= 3 -> 3
                else -> 4
            }
        } else {
            when {
                photos.size <= 3 -> 2
                else -> 3
            }
        }

        // Column width with border spacing
        val colWidth = (width - (numColumns + 1) * BORDER_WIDTH) / numColumns

        // Track the current bottom of each column
        val columnBottoms = FloatArray(numColumns) { (BORDER_WIDTH) }

        // Distribute photos across columns
        photos.forEachIndexed { index, photo ->
            // Find column with the smallest bottom value
            val colIndex = columnBottoms.indices.minByOrNull { columnBottoms[it] } ?: 0

            // Calculate aspect-correct height based on photo dimensions
            val photoRatio = photo.height.toFloat() / photo.width
            val cellHeight = (colWidth * photoRatio)

            // Create rect for this photo
            val left = BORDER_WIDTH + colIndex * (colWidth + BORDER_WIDTH)
            val top = columnBottoms[colIndex]
            val right = left + colWidth
            val bottom = top + cellHeight

            val rect = Rect(
                left.toInt(),
                top.toInt(),
                right.toInt(),
                bottom.toInt()
            )

            // Draw the photo and its border
            drawPhotoInRect(canvas, photo, rect)
            canvas.drawRect(rect, paint)

            // Update column bottom
            columnBottoms[colIndex] = bottom + BORDER_WIDTH
        }

        return resultBitmap
    }

    /**
     * Helper method to draw a photo with rotation
     */
    private fun drawRotatedPhotoInRect(canvas: Canvas, photo: Bitmap, rect: Rect, rotation: Float) {
        // Save canvas state before rotation
        canvas.save()

        // Rotate around center of rectangle
        canvas.rotate(
            rotation,
            rect.exactCenterX(),
            rect.exactCenterY()
        )

        // Draw photo
        drawPhotoInRect(canvas, photo, rect)

        // Restore canvas to previous state
        canvas.restore()
    }

    /**
     * Helper method to create grid for 3 photos
     */
    private fun createThreePhotoGrid(width: Int, height: Int): List<Rect> {
        val rects = mutableListOf<Rect>()

        if (width > height) {
            // Landscape orientation: large photo on left, two stacked on right
            val leftWidth = (width * 0.6).toInt()
            val rightWidth = width - leftWidth - BORDER_WIDTH.toInt()

            // Left large photo
            rects.add(Rect(0, 0, leftWidth, height))

            // Top right photo
            rects.add(Rect(
                leftWidth + BORDER_WIDTH.toInt(),
                0,
                width,
                height / 2 - BORDER_WIDTH.toInt() / 2
            ))

            // Bottom right photo
            rects.add(Rect(
                leftWidth + BORDER_WIDTH.toInt(),
                height / 2 + BORDER_WIDTH.toInt() / 2,
                width,
                height
            ))
        } else {
            // Portrait orientation: large photo on top, two side by side on bottom
            val topHeight = (height * 0.6).toInt()
            val bottomHeight = height - topHeight - BORDER_WIDTH.toInt()

            // Top large photo
            rects.add(Rect(0, 0, width, topHeight))

            // Bottom left photo
            rects.add(Rect(
                0,
                topHeight + BORDER_WIDTH.toInt(),
                width / 2 - BORDER_WIDTH.toInt() / 2,
                height
            ))

            // Bottom right photo
            rects.add(Rect(
                width / 2 + BORDER_WIDTH.toInt() / 2,
                topHeight + BORDER_WIDTH.toInt(),
                width,
                height
            ))
        }

        return rects
    }

    /**
     * Helper method to create grid for 4 photos
     */
    private fun createFourPhotoGrid(width: Int, height: Int): List<Rect> {
        val rects = mutableListOf<Rect>()

        // Simple 2x2 grid
        val halfWidth = width / 2
        val halfHeight = height / 2

        // Top left
        rects.add(Rect(
            0,
            0,
            halfWidth - BORDER_WIDTH.toInt() / 2,
            halfHeight - BORDER_WIDTH.toInt() / 2
        ))

        // Top right
        rects.add(Rect(
            halfWidth + BORDER_WIDTH.toInt() / 2,
            0,
            width,
            halfHeight - BORDER_WIDTH.toInt() / 2
        ))

        // Bottom left
        rects.add(Rect(
            0,
            halfHeight + BORDER_WIDTH.toInt() / 2,
            halfWidth - BORDER_WIDTH.toInt() / 2,
            height
        ))

        // Bottom right
        rects.add(Rect(
            halfWidth + BORDER_WIDTH.toInt() / 2,
            halfHeight + BORDER_WIDTH.toInt() / 2,
            width,
            height
        ))

        return rects
    }

    /**
     * Helper method to create grid for 5 photos
     */
    private fun createFivePhotoGrid(width: Int, height: Int): List<Rect> {
        val rects = mutableListOf<Rect>()

        if (width > height) {
            // Landscape: 3 photos on top row, 2 on bottom
            val rowHeight = height / 2
            val topColWidth = width / 3
            val bottomColWidth = width / 2

            // Top row - 3 photos
            for (i in 0 until 3) {
                rects.add(Rect(
                    i * topColWidth + (if (i > 0) BORDER_WIDTH.toInt() else 0),
                    0,
                    (i + 1) * topColWidth - (if (i < 2) BORDER_WIDTH.toInt() else 0),
                    rowHeight - BORDER_WIDTH.toInt() / 2
                ))
            }

            // Bottom row - 2 photos
            for (i in 0 until 2) {
                rects.add(Rect(
                    i * bottomColWidth + (if (i > 0) BORDER_WIDTH.toInt() else 0),
                    rowHeight + BORDER_WIDTH.toInt() / 2,
                    (i + 1) * bottomColWidth - (if (i < 1) BORDER_WIDTH.toInt() else 0),
                    height
                ))
            }
        } else {
            // Portrait: 2 photos on top row, 3 on bottom
            val rowHeight = height / 2
            val topColWidth = width / 2
            val bottomColWidth = width / 3

            // Top row - 2 photos
            for (i in 0 until 2) {
                rects.add(Rect(
                    i * topColWidth + (if (i > 0) BORDER_WIDTH.toInt() else 0),
                    0,
                    (i + 1) * topColWidth - (if (i < 1) BORDER_WIDTH.toInt() else 0),
                    rowHeight - BORDER_WIDTH.toInt() / 2
                ))
            }

            // Bottom row - 3 photos
            for (i in 0 until 3) {
                rects.add(Rect(
                    i * bottomColWidth + (if (i > 0) BORDER_WIDTH.toInt() else 0),
                    rowHeight + BORDER_WIDTH.toInt() / 2,
                    (i + 1) * bottomColWidth - (if (i < 2) BORDER_WIDTH.toInt() else 0),
                    height
                ))
            }
        }

        return rects
    }

    /**
     * Helper method to create grid for 6 or more photos
     */
    private fun createSixPlusPhotoGrid(width: Int, height: Int, photoCount: Int): List<Rect> {
        val rects = mutableListOf<Rect>()

        // For 6+ photos, create a grid with dynamic cell counts
        val numColumns = if (width > height) 3 else 2
        val numRows = (photoCount + numColumns - 1) / numColumns  // Ceiling division

        val cellWidth = width / numColumns
        val cellHeight = height / numRows

        for (row in 0 until numRows) {
            for (col in 0 until numColumns) {
                val index = row * numColumns + col
                if (index < photoCount) {
                    rects.add(Rect(
                        col * cellWidth + (if (col > 0) BORDER_WIDTH.toInt() / 2 else 0),
                        row * cellHeight + (if (row > 0) BORDER_WIDTH.toInt() / 2 else 0),
                        (col + 1) * cellWidth - (if (col < numColumns - 1) BORDER_WIDTH.toInt() / 2 else 0),
                        (row + 1) * cellHeight - (if (row < numRows - 1) BORDER_WIDTH.toInt() / 2 else 0)
                    ))
                }
            }
        }

        return rects
    }
}