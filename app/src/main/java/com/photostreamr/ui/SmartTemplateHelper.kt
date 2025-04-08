package com.photostreamr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

/**
 * Helper class that acts as a bridge between SmartPhotoLayoutManager and other components
 */
@Singleton
class SmartTemplateHelper @Inject constructor(
    private val context: Context,
    private val smartPhotoLayoutManager: SmartPhotoLayoutManager,
    private val photoResizeManager: PhotoResizeManager
) {
    companion object {
        private const val TAG = "SmartTemplateHelper"
    }

    /**
     * Determine the best template type based on photos and container dimensions
     */
    suspend fun determineBestTemplate(
        photos: List<Bitmap>,
        containerWidth: Int,
        containerHeight: Int
    ): Int = withContext(Dispatchers.Default) {
        if (photos.size < 2) {
            return@withContext -1 // Use single photo display
        }

        try {
            // Analyze all photos for face detection and other properties
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

    /**
     * Create a smart template drawable based on the provided photos
     */
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
            // Single photo case - delegate to photo resize manager
            if (photos.size == 1 || templateType == -1) {
                return@withContext createSinglePhotoTemplate(photos[0], containerWidth, containerHeight)
            }

            // Analyze all photos
            val photoAnalyses = smartPhotoLayoutManager.analyzePhotos(photos)

            // Create layout regions for the template
            val regions = smartPhotoLayoutManager.createLayoutRegions(
                templateType,
                containerWidth,
                containerHeight
            )

            // Assign photos to regions
            val assignments = smartPhotoLayoutManager.assignPhotosToRegions(photoAnalyses, regions)

            // Create final template
            val templateBitmap = smartPhotoLayoutManager.createFinalTemplate(
                photoAnalyses,
                regions,
                assignments,
                containerWidth,
                containerHeight
            )

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
     * Create a single photo template with ambient effects
     */
    private suspend fun createSinglePhotoTemplate(
        photo: Bitmap,
        containerWidth: Int,
        containerHeight: Int
    ): Drawable = withContext(Dispatchers.Default) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val letterboxMode = prefs.getString(
            PhotoResizeManager.PREF_KEY_LETTERBOX_MODE,
            PhotoResizeManager.DEFAULT_LETTERBOX_MODE
        ) ?: PhotoResizeManager.DEFAULT_LETTERBOX_MODE

        // Create a bitmap with the container dimensions
        val resultBitmap = Bitmap.createBitmap(
            containerWidth,
            containerHeight,
            Bitmap.Config.ARGB_8888
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

        // Draw the photo
        canvas.drawBitmap(
            photo,
            null,
            rect,
            null
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