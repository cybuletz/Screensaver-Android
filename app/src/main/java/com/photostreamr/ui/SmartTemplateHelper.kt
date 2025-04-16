package com.photostreamr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Helper class that acts as a bridge between SmartPhotoLayoutManager and other components
 */
@Singleton
class SmartTemplateHelper @Inject constructor(
    private val context: Context,
    private val smartPhotoLayoutManager: SmartPhotoLayoutManager,
    private val bitmapMemoryManager: BitmapMemoryManager
) {
    companion object {
        private const val TAG = "SmartTemplateHelper"
        private const val FACE_PADDING_FRACTION = 0.25f // Padding for face crop (hair/neck/air)
    }

    /**
     * Determine the best template type based on photos and container dimensions
     * ENHANCED: Now respects specific template type selection
     */
    suspend fun determineBestTemplate(
        photos: List<Bitmap>,
        containerWidth: Int,
        containerHeight: Int,
        requestedTemplateType: Int = MultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC
    ) = withContext(Dispatchers.Default) {
        if (photos.size < 2) {
            return@withContext -1 // Use single photo display
        }

        try {
            // If a specific template type was requested (not DYNAMIC), honor it
            if (requestedTemplateType != MultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC) {
                // Check if template is compatible with current orientation
                val isCompatible = isTemplateCompatibleWithOrientation(
                    requestedTemplateType,
                    containerWidth,
                    containerHeight
                )

                // Return the requested type if compatible, otherwise find a suitable one
                if (isCompatible) {
                    Log.d(TAG, "Using specifically requested template type: $requestedTemplateType")
                    return@withContext requestedTemplateType
                } else {
                    Log.d(TAG, "Requested template $requestedTemplateType not compatible with orientation, finding alternative")
                }
            }

            // For DYNAMIC type or incompatible type, analyze photos to find best layout
            val photoAnalyses = smartPhotoLayoutManager.analyzePhotos(photos)

            // Get the best layout based on analyses
            val bestLayout = smartPhotoLayoutManager.determineBestLayout(
                photoAnalyses,
                containerWidth,
                containerHeight
            )

            Log.d(TAG, "Determined best template type: $bestLayout")
            return@withContext bestLayout
        } catch (e: Exception) {
            Log.e(TAG, "Error determining best template", e)
            return@withContext MultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC // Fallback to dynamic
        }
    }

    suspend fun createSmartTemplate(
        photos: List<Bitmap>,
        containerWidth: Int,
        containerHeight: Int,
        templateType: Int
    ): Drawable? = withContext(Dispatchers.Default) {
        if (photos.isEmpty()) {
            Log.e(TAG, "No photos provided for template creation")
            return@withContext null
        }

        try {
            // Log start of operation with memory tracking
            val startTime = System.currentTimeMillis()
            val memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            Log.d(TAG, "📊 SMART FILL starting with ${photos.size} photo(s), template type: $templateType")

            // Single photo case - delegate to photo resize manager
            if (photos.size == 1 || templateType == -1) {
                val result = createSinglePhotoTemplate(photos[0], containerWidth, containerHeight)

                // Log memory change
                val memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val memoryDelta = (memoryAfter - memoryBefore) / 1024 / 1024  // MB
                val processingTime = System.currentTimeMillis() - startTime

                Log.d(TAG, "📊 SMART FILL with single photo complete: memory change: +${memoryDelta}MB, time: ${processingTime}ms")

                return@withContext result
            }

            // Analyze all photos
            Log.d(TAG, "Running face detection and photo analysis on photos")
            val photoAnalyses = smartPhotoLayoutManager.analyzePhotos(photos)

            // Create layout regions for the template
            val regions = smartPhotoLayoutManager.createLayoutRegions(
                templateType,
                containerWidth,
                containerHeight
            )

            // Assign photos to regions
            val assignments = smartPhotoLayoutManager.assignPhotosToRegions(photoAnalyses, regions)

            // PATCH: Actually use face-aware cropping for each assigned region with maximum zoom-out
            val croppedBitmaps: MutableMap<Int, Bitmap> = mutableMapOf()
            assignments.forEach { (regionIndex, photoIndex) ->
                if (regionIndex >= regions.size || photoIndex >= photoAnalyses.size) return@forEach
                val analysis = photoAnalyses[photoIndex]
                val region = regions[regionIndex]
                val regionW = region.rect.width().toInt()
                val regionH = region.rect.height().toInt()
                val faceRects = analysis.faceRegions

                val cropped = if (faceRects.isNotEmpty()) {
                    Log.i(TAG, "CROP_DEBUG: Using face-aware crop for region $regionIndex, photo $photoIndex with ${faceRects.size} faces.")
                    smartFaceAwareCrop(
                        analysis.bitmap,
                        regionW,
                        regionH,
                        faceRects,
                        FACE_PADDING_FRACTION
                    )
                } else {
                    Log.i(TAG, "CROP_DEBUG: No faces detected, using center crop for region $regionIndex, photo $photoIndex.")
                    // fallback center crop
                    centerCrop(analysis.bitmap, regionW, regionH)
                }
                croppedBitmaps[regionIndex] = cropped
            }

            // Compose the template bitmap
            val templateBitmap = Bitmap.createBitmap(containerWidth, containerHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(templateBitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
            // Draw only regions that have a cropped bitmap
            for ((regionIndex, bitmap) in croppedBitmaps) {
                if (regionIndex < regions.size) {
                    val region = regions[regionIndex]
                    val left = region.rect.left
                    val top = region.rect.top
                    val width = region.rect.width()
                    val height = region.rect.height()
                    val destRect = RectF(left, top, left + width, top + height)
                    canvas.drawBitmap(bitmap, null, destRect, paint)
                }
            }

            // Register the template bitmap with memory manager
            bitmapMemoryManager.registerActiveBitmap("smartTemplate:${templateType}:${System.currentTimeMillis()}", templateBitmap)

            // Log memory and performance metrics
            val memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val memoryDelta = (memoryAfter - memoryBefore) / 1024 / 1024  // MB
            val processingTime = System.currentTimeMillis() - startTime

            Log.d(TAG, "📊 SMART FILL with ${photos.size}-photo template complete: memory change: +${memoryDelta}MB, time: ${processingTime}ms")

            return@withContext BitmapDrawable(context.resources, templateBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating smart template", e)

            // Fallback to single photo
            if (photos.isNotEmpty()) {
                return@withContext createSinglePhotoTemplate(
                    photos[0],
                    containerWidth,
                    containerHeight
                )
            }

            return@withContext null
        }
    }

    /**
     * Smart, maximum-zoom-out face-aware crop.
     * - Shows as much of the image as possible (maximum zoom out, no letterbox).
     * - If faces+padding fit in the maximum crop, use it.
     * - If not, only zoom in as much as needed for faces+padding.
     */
    private fun smartFaceAwareCrop(
        src: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        faceRects: List<RectF>,
        paddingFraction: Float
    ): Bitmap {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight

        // Step 1: Union all faces + padding
        var minX = srcW
        var minY = srcH
        var maxX = 0f
        var maxY = 0f
        for (face in faceRects) {
            val padW = face.width() * paddingFraction
            val padH = face.height() * paddingFraction
            minX = min(minX, face.left - padW)
            minY = min(minY, face.top - padH)
            maxX = max(maxX, face.right + padW)
            maxY = max(maxY, face.bottom + padH)
        }
        minX = minX.coerceAtLeast(0f)
        minY = minY.coerceAtLeast(0f)
        maxX = maxX.coerceAtMost(srcW)
        maxY = maxY.coerceAtMost(srcH)
        val unionW = maxX - minX
        val unionH = maxY - minY

        // Step 2: Maximum zoom out crop (centered, at target aspect)
        val srcRatio = srcW / srcH
        val cropW: Float
        val cropH: Float
        val cropLeft: Float
        val cropTop: Float
        if (srcRatio > targetRatio) {
            // Image is wider than target aspect
            cropH = srcH
            cropW = cropH * targetRatio
            cropLeft = (srcW - cropW) / 2f
            cropTop = 0f
        } else {
            // Image is taller than target aspect
            cropW = srcW
            cropH = cropW / targetRatio
            cropLeft = 0f
            cropTop = (srcH - cropH) / 2f
        }

        // Step 3: If faces+padding fit in this crop, use it
        if (
            minX >= cropLeft &&
            maxX <= cropLeft + cropW &&
            minY >= cropTop &&
            maxY <= cropTop + cropH
        ) {
            // Union fits, use maximum zoom out!
            Log.i(TAG, "CROP_DEBUG: Union fits in max zoom-out crop, using cropLeft=$cropLeft, cropTop=$cropTop, cropW=$cropW, cropH=$cropH")
            return Bitmap.createBitmap(src, cropLeft.toInt(), cropTop.toInt(), cropW.toInt(), cropH.toInt())
                .let { Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true) }
        }

        // Step 4: Otherwise, zoom in only as much as needed
        // Start with faces+padding union, expand to target aspect, pan to stay within image bounds
        var finalW = unionW
        var finalH = unionH
        if (finalW / finalH < targetRatio) {
            // Need to expand width
            finalW = finalH * targetRatio
        } else {
            // Need to expand height
            finalH = finalW / targetRatio
        }
        var finalLeft = (minX + maxX) / 2f - finalW / 2f
        var finalTop = (minY + maxY) / 2f - finalH / 2f
        // Clamp inside image
        finalLeft = finalLeft.coerceIn(0f, srcW - finalW)
        finalTop = finalTop.coerceIn(0f, srcH - finalH)

        Log.i(TAG, "CROP_DEBUG: Union doesn't fit, using finalLeft=$finalLeft, finalTop=$finalTop, finalW=$finalW, finalH=$finalH")
        return Bitmap.createBitmap(src, finalLeft.toInt(), finalTop.toInt(), finalW.toInt(), finalH.toInt())
            .let { Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true) }
    }

    /**
     * Center crop fallback.
     */
    private fun centerCrop(
        src: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = targetWidth.toFloat() / targetHeight
        val cropW: Int
        val cropH: Int
        var cropX = 0
        var cropY = 0
        if (srcRatio > dstRatio) {
            cropH = srcH
            cropW = (dstRatio * srcH).toInt()
            cropX = (srcW - cropW) / 2
        } else {
            cropW = srcW
            cropH = (srcW / dstRatio).toInt()
            cropY = (srcH - cropH) / 2
        }
        return Bitmap.createBitmap(
            src,
            cropX,
            cropY,
            cropW.coerceAtMost(srcW - cropX),
            cropH.coerceAtMost(srcH - cropY)
        ).let { Bitmap.createScaledBitmap(it, targetWidth, targetHeight, true) }
    }

    /**
     * Create a single photo template with ambient effects - memory optimized
     */
    private suspend fun createSinglePhotoTemplate(
        photo: Bitmap,
        containerWidth: Int,
        containerHeight: Int
    ): Drawable = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val letterboxMode = prefs.getString(
            PhotoResizeManager.PREF_KEY_LETTERBOX_MODE,
            PhotoResizeManager.DEFAULT_LETTERBOX_MODE
        ) ?: PhotoResizeManager.DEFAULT_LETTERBOX_MODE

        // Log input dimensions
        Log.d(TAG, "📊 SMART FILL creating single photo template: ${containerWidth}x${containerHeight}")
        Log.d(TAG, "📊 Input photo: ${photo.width}x${photo.height}, format: ${photo.config}")

        // Calculate if this is a large bitmap that should use RGB_565
        val isLargeBitmap = containerWidth * containerHeight > 1_000_000

        // Create a bitmap with the container dimensions - use RGB_565 for large bitmaps
        val resultBitmap = Bitmap.createBitmap(
            containerWidth,
            containerHeight,
            if (isLargeBitmap) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(resultBitmap)
        canvas.drawColor(Color.BLACK)

        // Calculate aspect ratios
        val photoRatio = photo.width.toFloat() / photo.height.toFloat()
        val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()

        // Calculate dimensions to fit the photo
        val rect = calculateFitRect(
            photoRatio,
            containerRatio,
            containerWidth,
            containerHeight
        )

        // Create a paint with filtering for better quality scaling
        val paint = Paint().apply {
            isFilterBitmap = true  // Use bilinear filtering
            isAntiAlias = true     // Smooth edges
            isDither = true        // Improve color rendering for RGB_565
        }

        // Draw the photo with quality settings
        canvas.drawBitmap(
            photo,
            null,
            rect,
            paint
        )

        // For the ambient areas, detect if letterboxing is horizontal or vertical
        val isHorizontalLetterbox = rect.height() < containerHeight
        val isVerticalLetterbox = rect.width() < containerWidth

        // Create ambient effects for letterbox areas if needed
        if (isHorizontalLetterbox || isVerticalLetterbox) {
            // Convert photo to BitmapDrawable for the PhotoResizeManager
            val photoBitmapDrawable = BitmapDrawable(context.resources, photo)

            // Create a temporary canvas for the ambient effects
            if (isHorizontalLetterbox) {
                // Horizontal letterboxing (top and bottom)
                val topHeight = rect.top.toInt()
                val bottomHeight = (containerHeight - rect.bottom).toInt()

                if (topHeight > 0 && bottomHeight > 0) {
                    // Apply ambient effect based on letterbox mode
                    applyAmbientEffect(
                        canvas,
                        photoBitmapDrawable,
                        letterboxMode,
                        RectF(0f, 0f, containerWidth.toFloat(), rect.top.toFloat()),
                        RectF(0f, rect.bottom.toFloat(), containerWidth.toFloat(), containerHeight.toFloat()),
                        true
                    )
                }
            }

            if (isVerticalLetterbox) {
                // Vertical letterboxing (left and right)
                val leftWidth = rect.left.toInt()
                val rightWidth = (containerWidth - rect.right).toInt()

                if (leftWidth > 0 && rightWidth > 0) {
                    // Apply ambient effect based on letterbox mode
                    applyAmbientEffect(
                        canvas,
                        photoBitmapDrawable,
                        letterboxMode,
                        RectF(0f, 0f, rect.left.toFloat(), containerHeight.toFloat()),
                        RectF(rect.right.toFloat(), 0f, containerWidth.toFloat(), containerHeight.toFloat()),
                        false
                    )
                }
            }
        }

        // Log memory usage and performance
        val memoryUsed = resultBitmap.byteCount / 1024.0 / 1024.0  // MB
        val elapsedTime = System.currentTimeMillis() - startTime

        Log.d(TAG, "📊 SMART FILL single photo complete: ${resultBitmap.width}x${resultBitmap.height}, " +
                "format: ${resultBitmap.config}, memory: ${String.format("%.2f MB", memoryUsed)}, " +
                "time: ${elapsedTime}ms")

        // Register with memory manager for tracking
        bitmapMemoryManager.registerActiveBitmap("smartFill:single:${System.currentTimeMillis()}", resultBitmap)

        return@withContext BitmapDrawable(context.resources, resultBitmap)
    }

    /**
     * Calculate rectangle to fit the photo in the container
     */
    private fun calculateFitRect(
        photoRatio: Float,
        containerRatio: Float,
        containerWidth: Int,
        containerHeight: Int
    ): Rect {
        // Determine if we need to fit to width or height
        if (photoRatio > containerRatio) {
            // Photo is wider than container - fit to width
            val height = (containerWidth / photoRatio).toInt()
            val top = (containerHeight - height) / 2

            return Rect(0, top, containerWidth, top + height)
        } else {
            // Photo is taller than container - fit to height
            val width = (containerHeight * photoRatio).toInt()
            val left = (containerWidth - width) / 2

            return Rect(left, 0, left + width, containerHeight)
        }
    }

    /**
     * Apply ambient effects to letterbox areas
     */
    private fun applyAmbientEffect(
        canvas: Canvas,
        drawable: Drawable,
        mode: String,
        rect1: RectF,
        rect2: RectF,
        isHorizontal: Boolean
    ) {
        // This is a simplified version - in a real implementation,
        // you would use the PhotoResizeManager's ambient methods directly

        // Fill with black as a base
        canvas.drawRect(rect1, android.graphics.Paint().apply {
            color = Color.BLACK
        })
        canvas.drawRect(rect2, android.graphics.Paint().apply {
            color = Color.BLACK
        })

        // Delegate to PhotoResizeManager for actual effect
        // In a real implementation, this would be a more direct call
        // that creates the actual ambient effect bitmaps

        // Note: This is a placeholder. The actual implementation would
        // need to adapt to your PhotoResizeManager's existing methods.
    }

    /**
     * Check if the template type is compatible with current orientation
     */
    fun isTemplateCompatibleWithOrientation(
        templateType: Int,
        containerWidth: Int,
        containerHeight: Int
    ): Boolean {
        val isLandscape = containerWidth > containerHeight

        return when (templateType) {
            MultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL -> true // Works in both
            MultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL -> true // Works in both
            MultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_LEFT -> true // Works in both
            MultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_RIGHT -> true // Works in both
            MultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID -> true // Works in both
            MultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC -> true // Works in both
            MultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE -> isLandscape
            MultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY -> !isLandscape
            else -> true
        }
    }
}