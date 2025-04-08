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
        if (containerWidth <= 0 || containerHeight <= 0) {
            callback.onTemplateError("Invalid container dimensions")
            return
        }

        managerScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                if (photoCount < 2) {
                    callback.onTemplateError("Not enough photos available")
                    return@launch
                }

                // Determine indices of photos to include in template
                val photoIndices = getPhotoIndices(currentPhotoIndex, photoCount, getRequiredPhotoCount(layoutType))

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
                        containerWidth, containerHeight, photoBitmaps
                    )
                    LAYOUT_TYPE_2_HORIZONTAL -> createTwoPhotoHorizontalTemplate(
                        containerWidth, containerHeight, photoBitmaps
                    )
                    LAYOUT_TYPE_3_MAIN_LEFT -> createThreePhotoMainLeftTemplate(
                        containerWidth, containerHeight, photoBitmaps
                    )
                    LAYOUT_TYPE_3_MAIN_RIGHT -> createThreePhotoMainRightTemplate(
                        containerWidth, containerHeight, photoBitmaps
                    )
                    LAYOUT_TYPE_4_GRID -> createFourPhotoGridTemplate(
                        containerWidth, containerHeight, photoBitmaps
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
            else -> 2
        }
    }

    /**
     * Get photo indices to use in template
     */
    private fun getPhotoIndices(currentIndex: Int, totalPhotos: Int, requiredCount: Int): List<Int> {
        val indices = mutableListOf<Int>()
        indices.add(currentIndex) // Always include current photo

        // Add subsequent photos (wrapping around if necessary)
        var nextIndex = (currentIndex + 1) % totalPhotos
        while (indices.size < requiredCount && nextIndex != currentIndex) {
            indices.add(nextIndex)
            nextIndex = (nextIndex + 1) % totalPhotos
        }

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

                    // Check if already in our local cache
                    if (imageCache.containsKey(urlToUse)) {
                        return@async imageCache[urlToUse]
                    }

                    // Check if preloaded
                    val preloadedResource = photoPreloader.getPreloadedResource(urlToUse)
                    if (preloadedResource != null) {
                        try {
                            val bitmap = preloadedResource.toBitmap()
                            imageCache[urlToUse] = bitmap
                            return@async bitmap
                        } catch (e: Exception) {
                            Log.e(TAG, "Error converting preloaded resource to bitmap", e)
                        }
                    }

                    // Load with Glide
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
                                    continuation.resume(resource, null)
                                    return true
                                }
                            })
                            .submit()
                            .get()
                    }

                    if (bitmap != null) {
                        imageCache[urlToUse] = bitmap
                    }

                    return@async bitmap
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading photo bitmap", e)
                    return@async null
                }
            }
        }

        // Await all results and filter out nulls
        deferredResults.awaitAll().filterNotNull().forEach {
            results.add(it)
        }

        results
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
     * Helper method to draw a photo in a specified rectangle while maintaining aspect ratio
     */
    private fun drawPhotoInRect(canvas: Canvas, photo: Bitmap, rect: Rect) {
        // Calculate scaling factors
        val sourceRatio = photo.width.toFloat() / photo.height
        val targetRatio = rect.width().toFloat() / rect.height()

        val scale: Float
        val offsetX: Float
        val offsetY: Float

        if (sourceRatio > targetRatio) {
            // Source is wider than target: scale to fit height
            scale = rect.height().toFloat() / photo.height
            offsetX = (rect.width() - photo.width * scale) / 2
            offsetY = 0f
        } else {
            // Source is taller than target: scale to fit width
            scale = rect.width().toFloat() / photo.width
            offsetX = 0f
            offsetY = (rect.height() - photo.height * scale) / 2
        }

        // Create matrix for transformation
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(rect.left + offsetX, rect.top + offsetY)

        // Draw the bitmap
        canvas.drawBitmap(photo, matrix, null)
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
}