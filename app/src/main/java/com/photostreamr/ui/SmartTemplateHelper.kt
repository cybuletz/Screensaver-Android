package com.photostreamr.ui

import android.content.Context
import android.graphics.*
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
import kotlin.math.roundToInt

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

            // Use enhanced face-aware, max-zoom-out, never-letterbox cropping for each region
            val croppedBitmaps: MutableMap<Int, Bitmap> = mutableMapOf()
            assignments.forEach { (regionIndex, photoIndex) ->
                if (regionIndex >= regions.size || photoIndex >= photoAnalyses.size) return@forEach
                val analysis = photoAnalyses[photoIndex]
                val region = regions[regionIndex]
                val regionW = region.rect.width().toInt()
                val regionH = region.rect.height().toInt()
                val cropped = smartFaceAwareCrop(
                    analysis.bitmap,
                    regionW,
                    regionH,
                    analysis.faceRegions,
                    FACE_PADDING_FRACTION
                )
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
     * Smart, maximum-zoom-out face-aware crop with better extreme aspect ratio handling.
     * REQUIREMENTS:
     * 1. Always fill the target region completely (no letterboxing)
     * 2. Show as much of the photo as possible (maximum zoom-out)
     * 3. Ensure all faces (with padding) are fully visible
     * 4. If no faces detected, use maximal center crop
     * 5. Handle extreme aspect ratio mismatches intelligently
     */
    private fun smartFaceAwareCrop(
        src: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        faceRects: List<RectF>?,
        paddingFraction: Float
    ): Bitmap {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight
        val srcRatio = srcW / srcH

        // Extensive logging to debug container/ratio issues
        Log.d(TAG, "📏 CROP INPUT: src=${srcW.toInt()}x${srcH.toInt()} ratio=${String.format("%.3f", srcRatio)}, " +
                "target=${targetWidth}x${targetHeight} ratio=${String.format("%.3f", targetRatio)}")

        if (faceRects != null) {
            for (i in faceRects.indices) {
                val face = faceRects[i]
                Log.d(TAG, "👤 Face #${i+1}: left=${face.left.toInt()}, top=${face.top.toInt()}, " +
                        "right=${face.right.toInt()}, bottom=${face.bottom.toInt()}, " +
                        "size=${face.width().toInt()}x${face.height().toInt()}")
            }
        }

        // Check for invalid target dimensions
        if (targetWidth <= 0 || targetHeight <= 0) {
            Log.e(TAG, "❌ INVALID TARGET DIMENSIONS: ${targetWidth}x${targetHeight}. Using source dimensions.")
            return src
        }

        // Detect extreme aspect ratio mismatch
        val isExtremeRatioMismatch = (targetRatio > 2.5f && srcRatio < 1.0f) ||
                (targetRatio < 0.4f && srcRatio > 1.0f) ||
                (targetRatio / srcRatio > 4.0f) ||
                (srcRatio / targetRatio > 4.0f)

        if (isExtremeRatioMismatch) {
            Log.w(TAG, "⚠️ EXTREME RATIO MISMATCH: source ${String.format("%.2f", srcRatio)} vs target ${String.format("%.2f", targetRatio)}")
        }

        // STEP 1: Calculate the maximum possible crop that fills the target (no letterbox)
        val maxCropW: Float
        val maxCropH: Float
        val maxCropLeft: Float
        val maxCropTop: Float

        if (srcRatio > targetRatio) {
            // Image is wider than target: crop sides
            maxCropH = srcH
            maxCropW = maxCropH * targetRatio
            maxCropLeft = (srcW - maxCropW) / 2f // center by default
            maxCropTop = 0f
        } else {
            // Image is taller than target: crop top/bottom
            maxCropW = srcW
            maxCropH = maxCropW / targetRatio
            maxCropLeft = 0f
            maxCropTop = (srcH - maxCropH) / 2f // center by default
        }

        Log.d(TAG, "🔍 MAX CROP: ${maxCropW.toInt()}x${maxCropH.toInt()} at (${maxCropLeft.toInt()},${maxCropTop.toInt()})")

        // STEP 2: If no faces, use the maximum center crop (maximal zoom-out)
        if (faceRects.isNullOrEmpty()) {
            Log.d(TAG, "No faces detected, using maximum center crop")
            return createCroppedBitmap(
                src,
                maxCropLeft.toInt(),
                maxCropTop.toInt(),
                maxCropW.toInt(),
                maxCropH.toInt(),
                targetWidth,
                targetHeight
            )
        }

        // STEP 3: Calculate bounding box containing all faces with padding
        var minX = srcW
        var minY = srcH
        var maxX = 0f
        var maxY = 0f

        // Calculate face padding with minimums and maximums
        val minPaddingPixels = min(srcW, srcH) * 0.05f // Minimum padding relative to image size
        val maxPaddingPercentage = 0.15f // Maximum padding as percentage of image dimension

        for (face in faceRects) {
            // Calculate padding based on face size but with minimums and maximums
            val padW = min(
                max(face.width() * paddingFraction, minPaddingPixels),
                srcW * maxPaddingPercentage
            )
            val padH = min(
                max(face.height() * paddingFraction, minPaddingPixels),
                srcH * maxPaddingPercentage
            )

            minX = min(minX, face.left - padW)
            minY = min(minY, face.top - padH)
            maxX = max(maxX, face.right + padW)
            maxY = max(maxY, face.bottom + padH)
        }

        // Ensure face bounds stay within image dimensions
        minX = minX.coerceAtLeast(0f)
        minY = minY.coerceAtLeast(0f)
        maxX = maxX.coerceAtMost(srcW)
        maxY = maxY.coerceAtMost(srcH)

        val faceWidth = maxX - minX
        val faceHeight = maxY - minY
        val faceCenterX = (minX + maxX) / 2f
        val faceCenterY = (minY + maxY) / 2f

        Log.d(TAG, "👥 FACE REGION: ${faceWidth.toInt()}x${faceHeight.toInt()} at (${minX.toInt()},${minY.toInt()}), center at (${faceCenterX.toInt()},${faceCenterY.toInt()})")

        // SPECIAL HANDLING FOR EXTREME ASPECT RATIO MISMATCHES
        if (isExtremeRatioMismatch) {
            // For extreme ratios, prioritize showing the faces clearly
            // Create a crop that's centered on faces but extends as much as possible

            // Step 1: Start with a rectangle around the faces at the target aspect ratio
            var cropW: Float
            var cropH: Float

            if (faceWidth / faceHeight > targetRatio) {
                // Faces are wider than target - use face width and calculate height
                cropW = faceWidth
                cropH = cropW / targetRatio
            } else {
                // Faces are taller than target or same ratio - use face height and calculate width
                cropH = faceHeight
                cropW = cropH * targetRatio
            }

            // Step 2: Expand this rectangle as much as possible while maintaining aspect ratio
            val canExpandHoriz = cropW < srcW
            val canExpandVert = cropH < srcH

            if (canExpandHoriz && canExpandVert) {
                // Can expand in both directions
                val horizExpansion = min(srcW / cropW, 3.0f) // Limit excessive expansion
                val vertExpansion = min(srcH / cropH, 3.0f) // Limit excessive expansion
                val expansion = min(horizExpansion, vertExpansion)

                cropW *= expansion
                cropH *= expansion

                Log.d(TAG, "🔄 Expanding crop by factor: ${String.format("%.2f", expansion)}")
            }

            // Step 3: Center the expanded crop on the faces
            var cropLeft = faceCenterX - cropW / 2f
            var cropTop = faceCenterY - cropH / 2f

            // Step 4: Adjust if crop exceeds image bounds
            if (cropLeft < 0) cropLeft = 0f
            if (cropTop < 0) cropTop = 0f
            if (cropLeft + cropW > srcW) cropLeft = srcW - cropW
            if (cropTop + cropH > srcH) cropTop = srcH - cropH

            // Final safety check
            cropW = min(cropW, srcW)
            cropH = min(cropH, srcH)

            Log.d(TAG, "📐 Extreme ratio crop: ${cropW.toInt()}x${cropH.toInt()} at (${cropLeft.toInt()},${cropTop.toInt()})")

            return createCroppedBitmap(
                src,
                cropLeft.toInt(),
                cropTop.toInt(),
                cropW.toInt(),
                cropH.toInt(),
                targetWidth,
                targetHeight
            )
        }

        // NORMAL HANDLING FOR NON-EXTREME RATIOS

        // Check if all faces fit within the maximum crop
        val facesInsideMaxCrop =
            minX >= maxCropLeft &&
                    maxX <= maxCropLeft + maxCropW &&
                    minY >= maxCropTop &&
                    maxY <= maxCropTop + maxCropH

        // Determine how much of the faces region is inside the max crop
        val faceAreaInsideCrop = calculateOverlapPercentage(
            RectF(minX, minY, maxX, maxY),
            RectF(maxCropLeft, maxCropTop, maxCropLeft + maxCropW, maxCropTop + maxCropH)
        )

        Log.d(TAG, "📊 Face region overlap with max crop: ${String.format("%.1f", faceAreaInsideCrop * 100)}%")

        // If faces are nearly inside the max crop (>85%), consider them inside
        val effectivelyInsideMaxCrop = facesInsideMaxCrop || faceAreaInsideCrop > 0.85f

        if (effectivelyInsideMaxCrop) {
            // Best case: all faces fit in max crop (or very close to it), use maximum zoom-out
            Log.d(TAG, "👍 All faces effectively fit in maximum crop, using maximum zoom-out")
            return createCroppedBitmap(
                src,
                maxCropLeft.toInt(),
                maxCropTop.toInt(),
                maxCropW.toInt(),
                maxCropH.toInt(),
                targetWidth,
                targetHeight
            )
        }

        // Try to adjust crop position while keeping max dimensions
        var adjustedLeft = maxCropLeft
        var adjustedTop = maxCropTop

        // If faces are partially outside the crop, try shifting the crop window
        if (faceAreaInsideCrop > 0.2f) { // At least some significant portion of faces is in the default crop
            // Shift left/right as needed
            if (minX < maxCropLeft) {
                adjustedLeft = minX
            } else if (maxX > maxCropLeft + maxCropW) {
                adjustedLeft = maxX - maxCropW
            }

            // Shift up/down as needed
            if (minY < maxCropTop) {
                adjustedTop = minY
            } else if (maxY > maxCropTop + maxCropH) {
                adjustedTop = maxY - maxCropH
            }

            // Ensure crop stays within image bounds
            adjustedLeft = adjustedLeft.coerceIn(0f, max(0f, srcW - maxCropW))
            adjustedTop = adjustedTop.coerceIn(0f, max(0f, srcH - maxCropH))

            // Check if shifted crop contains all faces
            val shiftedCropContainsFaces =
                minX >= adjustedLeft &&
                        maxX <= adjustedLeft + maxCropW &&
                        minY >= adjustedTop &&
                        maxY <= adjustedTop + maxCropH

            if (shiftedCropContainsFaces) {
                Log.d(TAG, "👍 Shifted crop contains all faces, using adjusted position")
                return createCroppedBitmap(
                    src,
                    adjustedLeft.toInt(),
                    adjustedTop.toInt(),
                    maxCropW.toInt(),
                    maxCropH.toInt(),
                    targetWidth,
                    targetHeight
                )
            }
        }

        // If we need to zoom in to fit faces, try to be smart about it
        // Calculate minimal crop dimensions needed for faces
        var minCropW = faceWidth
        var minCropH = faceHeight

        // Adjust to target aspect ratio
        if (minCropW / minCropH < targetRatio) {
            // Faces are taller than target ratio, expand width
            minCropW = minCropH * targetRatio
        } else {
            // Faces are wider than target ratio, expand height
            minCropH = minCropW / targetRatio
        }

        // Center on faces
        var minCropLeft = faceCenterX - (minCropW / 2f)
        var minCropTop = faceCenterY - (minCropH / 2f)

        // Keep crop within image bounds
        minCropLeft = minCropLeft.coerceIn(0f, max(0f, srcW - minCropW))
        minCropTop = minCropTop.coerceIn(0f, max(0f, srcH - minCropH))

        // Try to expand this crop as much as possible while keeping faces visible
        var expandedW = minCropW
        var expandedH = minCropH
        var expandedLeft = minCropLeft
        var expandedTop = minCropTop

        // Try expanding up to the edges of the image
        val horizRoom = min(minCropLeft, srcW - (minCropLeft + minCropW))
        val vertRoom = min(minCropTop, srcH - (minCropTop + minCropH))

        if (horizRoom > 0 || vertRoom > 0) {
            // Calculate how much we can expand while maintaining aspect ratio
            val expandFactor = min(
                if (horizRoom > 0) 1f + (2f * horizRoom / minCropW) else 1f,
                if (vertRoom > 0) 1f + (2f * vertRoom / minCropH) else 1f
            )

            if (expandFactor > 1.05f) { // Only expand if significant (>5%)
                expandedW = minCropW * expandFactor
                expandedH = minCropH * expandFactor
                expandedLeft = faceCenterX - (expandedW / 2f)
                expandedTop = faceCenterY - (expandedH / 2f)

                // Ensure expanded crop stays within image bounds
                expandedLeft = expandedLeft.coerceIn(0f, max(0f, srcW - expandedW))
                expandedTop = expandedTop.coerceIn(0f, max(0f, srcH - expandedH))

                Log.d(TAG, "🔄 Expanded minimum crop by factor: ${String.format("%.2f", expandFactor)}")
            }
        }

        // Final safety check
        expandedW = min(expandedW, srcW)
        expandedH = min(expandedH, srcH)

        Log.d(TAG, "📐 Final crop: ${expandedW.toInt()}x${expandedH.toInt()} at (${expandedLeft.toInt()},${expandedTop.toInt()})")

        return createCroppedBitmap(
            src,
            expandedLeft.toInt(),
            expandedTop.toInt(),
            expandedW.toInt(),
            expandedH.toInt(),
            targetWidth,
            targetHeight
        )
    }

    /**
     * Calculate percentage of rectangle A that overlaps with rectangle B
     */
    private fun calculateOverlapPercentage(rectA: RectF, rectB: RectF): Float {
        // Calculate intersection
        val left = max(rectA.left, rectB.left)
        val top = max(rectA.top, rectB.top)
        val right = min(rectA.right, rectB.right)
        val bottom = min(rectA.bottom, rectB.bottom)

        // Check if there is an intersection
        if (left >= right || top >= bottom) {
            return 0f // No overlap
        }

        // Calculate areas
        val intersectionArea = (right - left) * (bottom - top)
        val rectAArea = rectA.width() * rectA.height()

        // Return percentage of rectA that overlaps with rectB
        return if (rectAArea > 0) (intersectionArea / rectAArea) else 0f
    }

    /**
     * Helper method to create and scale a cropped bitmap with safety checks
     */
    private fun createCroppedBitmap(
        src: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        // Safety checks for invalid dimensions
        val safeLeft = left.coerceIn(0, src.width - 1)
        val safeTop = top.coerceIn(0, src.height - 1)
        val safeWidth = width.coerceIn(1, src.width - safeLeft)
        val safeHeight = height.coerceIn(1, src.height - safeTop)

        if (safeLeft != left || safeTop != top || safeWidth != width || safeHeight != height) {
            Log.w(TAG, "⚠️ Crop dimensions adjusted for safety: ${width}x${height} at (${left},${top}) -> ${safeWidth}x${safeHeight} at (${safeLeft},${safeTop})")
        }

        try {
            val cropped = Bitmap.createBitmap(src, safeLeft, safeTop, safeWidth, safeHeight)
            return Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating cropped bitmap", e)
            // Fall back to original bitmap or a simple center crop
            return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
        }
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