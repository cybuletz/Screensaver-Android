package com.photostreamr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.view.View
import androidx.annotation.WorkerThread
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.*
import java.util.concurrent.ConcurrentHashMap
import android.os.Build

/**
 * Manages intelligent photo layouts using ML Kit face detection for subject-aware cropping
 * and smart template selection based on photo content and screen orientation.
 */
@Singleton
class SmartPhotoLayoutManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "SmartPhotoLayoutManager"

        // Face detection confidence threshold
        private const val FACE_DETECTION_CONFIDENCE = 0.75f

        // Maximum number of faces to detect in each photo
        private const val MAX_FACES = 5

        // Border settings
        private const val BORDER_WIDTH = 8f
        private const val BORDER_COLOR = Color.WHITE

        // Importance weighting for different areas of the image (for saliency fallback)
        private const val CENTER_WEIGHT = 1.5f

        // Minimum weight to consider a photo suitable for a specific position
        private const val MIN_CROP_SUITABILITY = 0.6f

        // Layout compatibility with orientations
        private val PORTRAIT_COMPATIBLE_LAYOUTS = setOf(
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_LEFT,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_RIGHT,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY
        )

        private val LANDSCAPE_COMPATIBLE_LAYOUTS = setOf(
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_LEFT,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_RIGHT,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE
        )

        // Bitmap pool settings
        private const val MAX_BITMAPS_PER_SIZE = 3
    }

    /**
     * Inner class for bitmap pooling to reduce memory fragmentation
     */
    private inner class BitmapPool {
        // Map of dimensions to lists of available bitmaps
        private val bitmapPool = ConcurrentHashMap<Pair<Int, Int>, MutableList<Bitmap>>()

        // Set to track bitmaps that are currently in use
        private val inUseBitmaps = mutableSetOf<Bitmap>()

        // Statistics for monitoring
        private var totalCreatedBitmaps = 0
        private var totalReusedBitmaps = 0
        private var totalReleasedBitmaps = 0

        /**
         * Get a bitmap from the pool or create a new one if none available
         */
        @Synchronized
        fun getBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
            val key = Pair(width, height)

            // Try to get an existing bitmap of the right size from the pool
            val availableBitmaps = bitmapPool[key]

            if (availableBitmaps != null && availableBitmaps.isNotEmpty()) {
                // We found an existing bitmap of the right size
                val bitmap = availableBitmaps.removeAt(availableBitmaps.size - 1)

                // Mark this bitmap as in use
                inUseBitmaps.add(bitmap)

                // IMPORTANT: Always clear completely with black
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)

                totalReusedBitmaps++

                Log.d(TAG, "♻️ Reused bitmap from pool: ${width}x${height} ($config)")

                return bitmap
            }

            // No existing bitmap available, create a new one
            val newBitmap = Bitmap.createBitmap(width, height, config)
            inUseBitmaps.add(newBitmap)
            totalCreatedBitmaps++

            Log.d(TAG, "🆕 Created new bitmap: ${width}x${height} ($config)")

            return newBitmap
        }

        /**
         * Release a bitmap back to the pool when no longer needed
         */
        @Synchronized
        fun releaseBitmap(bitmap: Bitmap?) {
            if (bitmap == null || bitmap.isRecycled) {
                return
            }

            // Verify this bitmap was previously marked as in use
            if (!inUseBitmaps.remove(bitmap)) {
                Log.w(TAG, "⚠️ Attempted to release a bitmap that wasn't marked as in use")
                return
            }

            val key = Pair(bitmap.width, bitmap.height)

            // Get or create the list for this bitmap size
            val bitmapsForSize = bitmapPool.getOrPut(key) { mutableListOf() }

            // Only keep a limited number of bitmaps per size to avoid excessive memory usage
            if (bitmapsForSize.size < MAX_BITMAPS_PER_SIZE) {
                bitmapsForSize.add(bitmap)
                totalReleasedBitmaps++

                Log.d(TAG, "♻️ Released bitmap to pool: ${bitmap.width}x${bitmap.height}, " +
                        "pool size for this dimension: ${bitmapsForSize.size}")
            } else {
                // Too many bitmaps of this size already in pool, recycle this one
                bitmap.recycle()
                Log.d(TAG, "🗑️ Recycled bitmap (pool full): ${bitmap.width}x${bitmap.height}")
            }
        }

        /**
         * Emergency cleanup of the bitmap pool
         */
        @Synchronized
        fun clearPool() {
            var recycledCount = 0

            // Recycle all bitmaps in the pool
            bitmapPool.values.forEach { bitmaps ->
                bitmaps.forEach { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                        recycledCount++
                    }
                }
                bitmaps.clear()
            }

            bitmapPool.clear()

            Log.d(TAG, "🧹 Cleared bitmap pool, recycled $recycledCount bitmaps")
        }

        /**
         * Get statistics about the bitmap pool
         */
        @Synchronized
        fun getStats(): String {
            var totalPoolSize = 0
            var totalPoolMemoryBytes = 0L

            bitmapPool.forEach { (size, bitmaps) ->
                totalPoolSize += bitmaps.size

                // Calculate memory usage for this bitmap size
                bitmaps.firstOrNull()?.let { bitmap ->
                    val bytesPerBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        bitmap.allocationByteCount
                    } else {
                        bitmap.rowBytes * bitmap.height
                    }

                    totalPoolMemoryBytes += bytesPerBitmap * bitmaps.size
                }
            }

            // Count in-use bitmaps as well
            var inUseMemoryBytes = 0L
            inUseBitmaps.forEach { bitmap ->
                inUseMemoryBytes += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    bitmap.allocationByteCount
                } else {
                    bitmap.rowBytes * bitmap.height
                }
            }

            return "Pool: $totalPoolSize bitmaps, In-use: ${inUseBitmaps.size} bitmaps, " +
                    "Memory: ${formatBytes(totalPoolMemoryBytes)} pooled + " +
                    "${formatBytes(inUseMemoryBytes)} in-use"
        }

        private fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }

    // Create the bitmap pool
    private val bitmapPool = BitmapPool()

    // Face detector with high precision but high latency
    private val faceDetector by lazy {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setMinFaceSize(0.1f) // Detect smaller faces
            .build().let { options ->
                FaceDetection.getClient(options)
            }
    }

    // Data class for photo analysis results
    data class PhotoAnalysis(
        val bitmap: Bitmap,
        val aspectRatio: Float, // width / height
        val faces: List<Face> = emptyList(),
        val faceRegions: List<RectF> = emptyList(),
        val isPortrait: Boolean = aspectRatio < 1.0f,
        val isLandscape: Boolean = aspectRatio > 1.0f,
        val isSquare: Boolean = abs(aspectRatio - 1.0f) < 0.05f,
        val saliencyMap: FloatArray? = null,
        val faceDetectionSucceeded: Boolean = faces.isNotEmpty(),
        val dominantFaceRegion: RectF? = null
    )

    // Data class for layout regions with suitability scores for each photo
    data class LayoutRegion(
        val rect: RectF,
        val aspectRatio: Float,
        val photoSuitabilityScores: MutableMap<Int, Float> = mutableMapOf()
    )

    /**
     * Analyzes a list of photos for face detection and saliency
     */
    suspend fun analyzePhotos(photos: List<Bitmap>): List<PhotoAnalysis> = withContext(Dispatchers.Default) {
        photos.mapIndexed { index, bitmap ->
            async {
                try {
                    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                    // Detect faces in the bitmap
                    val faces = detectFaces(bitmap)

                    // Convert Face objects to RectF for easier handling
                    val faceRegions = faces.map { face ->
                        face.boundingBox.let { box ->
                            RectF(
                                box.left.toFloat(),
                                box.top.toFloat(),
                                box.right.toFloat(),
                                box.bottom.toFloat()
                            )
                        }
                    }

                    // Calculate dominant face region (largest face or composite of faces)
                    val dominantFaceRegion = if (faceRegions.isNotEmpty()) {
                        calculateDominantFaceRegion(faceRegions, bitmap.width, bitmap.height)
                    } else {
                        null
                    }

                    // Generate saliency map as fallback
                    val saliencyMap = if (faces.isEmpty()) {
                        generateSaliencyMap(bitmap)
                    } else {
                        null
                    }

                    Log.d(TAG, "Photo ${index+1} analysis - Aspect ratio: $aspectRatio, Faces detected: ${faces.size}")

                    PhotoAnalysis(
                        bitmap = bitmap,
                        aspectRatio = aspectRatio,
                        faces = faces,
                        faceRegions = faceRegions,
                        isPortrait = aspectRatio < 0.95f,
                        isLandscape = aspectRatio > 1.05f,
                        isSquare = aspectRatio >= 0.95f && aspectRatio <= 1.05f,
                        saliencyMap = saliencyMap,
                        faceDetectionSucceeded = faces.isNotEmpty(),
                        dominantFaceRegion = dominantFaceRegion
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error analyzing photo ${index+1}", e)
                    // Return basic analysis with no face detection in case of error
                    PhotoAnalysis(
                        bitmap = bitmap,
                        aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat(),
                        faceDetectionSucceeded = false
                    )
                }
            }
        }.awaitAll()
    }

    /**
     * Calculate a composite region encompassing all important faces or the largest face
     * with more reliable face region detection
     */
    private fun calculateDominantFaceRegion(faceRegions: List<RectF>, imgWidth: Int, imgHeight: Int): RectF {
        if (faceRegions.isEmpty()) {
            // If no faces, return center region with generous size
            val centerSize = min(imgWidth, imgHeight) * 0.7f
            return RectF(
                (imgWidth - centerSize) / 2,
                (imgHeight - centerSize) / 2,
                (imgWidth + centerSize) / 2,
                (imgHeight + centerSize) / 2
            )
        }

        // Find the largest face
        val largestFace = faceRegions.maxByOrNull { it.width() * it.height() }

        // For single face or when largest face is dominant (significantly larger than others)
        if (faceRegions.size == 1 || isLargestFaceDominant(faceRegions, largestFace)) {
            // Return largest face with generous padding
            return largestFace?.let {
                padFaceRegion(it, imgWidth, imgHeight, paddingFactor = 0.7f)
            } ?: RectF(0f, 0f, imgWidth.toFloat(), imgHeight.toFloat())
        }

        // For multiple significant faces, create a region that encompasses all faces
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = 0f
        var maxY = 0f

        faceRegions.forEach { face ->
            minX = min(minX, face.left)
            minY = min(minY, face.top)
            maxX = max(maxX, face.right)
            maxY = max(maxY, face.bottom)
        }

        // Add generous padding (20% of the image size)
        val paddingX = imgWidth * 0.2f
        val paddingY = imgHeight * 0.2f

        return RectF(
            max(0f, minX - paddingX),
            max(0f, minY - paddingY),
            min(imgWidth.toFloat(), maxX + paddingX),
            min(imgHeight.toFloat(), maxY + paddingY)
        )
    }

    /**
     * Determines if the largest face is significantly larger than other faces
     */
    private fun isLargestFaceDominant(faces: List<RectF>, largestFace: RectF?): Boolean {
        if (largestFace == null || faces.size <= 1) return false

        val largestArea = largestFace.width() * largestFace.height()
        var secondLargestArea = 0f

        // Find second largest face
        for (face in faces) {
            val area = face.width() * face.height()
            if (face != largestFace && area > secondLargestArea) {
                secondLargestArea = area
            }
        }

        // If largest face is at least 1.5x the size of second largest, consider it dominant
        return largestArea > 0 && secondLargestArea > 0 && (largestArea / secondLargestArea) > 1.5f
    }

    /**
     * Add padding around a face region with adjustable padding factor
     */
    private fun padFaceRegion(face: RectF, imgWidth: Int, imgHeight: Int, paddingFactor: Float = 0.5f): RectF {
        // Calculate face dimensions
        val faceWidth = face.width()
        val faceHeight = face.height()

        // Add generous padding (based on paddingFactor)
        val paddingX = faceWidth * paddingFactor
        val paddingY = faceHeight * paddingFactor

        return RectF(
            max(0f, face.left - paddingX),
            max(0f, face.top - paddingY),
            min(imgWidth.toFloat(), face.right + paddingX),
            min(imgHeight.toFloat(), face.bottom + paddingY)
        )
    }

    /**
     * Add padding around a face region
     */
    private fun padFaceRegion(face: RectF, imgWidth: Int, imgHeight: Int): RectF {
        // Calculate face dimensions
        val faceWidth = face.width()
        val faceHeight = face.height()

        // Add generous padding (100% of face size)
        val paddingX = faceWidth * 0.5f
        val paddingY = faceHeight * 0.5f

        return RectF(
            max(0f, face.left - paddingX),
            max(0f, face.top - paddingY),
            min(imgWidth.toFloat(), face.right + paddingX),
            min(imgHeight.toFloat(), face.bottom + paddingY)
        )
    }

    /**
     * Detect faces in a bitmap using ML Kit with improved reliability
     */
    @WorkerThread
    private suspend fun detectFaces(bitmap: Bitmap): List<Face> = suspendCancellableCoroutine { continuation ->
        try {
            // Always resize larger images for better detection performance
            val maxDimension = 1280
            val processedBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Log.d(TAG, "Resizing image from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight} for face detection")
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            val inputImage = InputImage.fromBitmap(processedBitmap, 0)

            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    // Accept almost all faces, with very permissive filtering
                    val highConfidenceFaces = faces.filter {
                        // Accept faces with wider angle tolerance
                        it.headEulerAngleY.absoluteValue < 45
                    }.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                        .take(MAX_FACES)

                    // If no faces detected with initial filter, try again with even more permissive criteria
                    val finalFaces = if (highConfidenceFaces.isEmpty() && faces.isNotEmpty()) {
                        Log.d(TAG, "No faces passed initial filter, using all detected faces")
                        faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                            .take(MAX_FACES)
                    } else {
                        highConfidenceFaces
                    }

                    // Debug logging
                    Log.d(TAG, "Face detection results: ${faces.size} faces found, ${finalFaces.size} kept")

                    // Clean up resized bitmap if we created one
                    if (processedBitmap != bitmap) {
                        processedBitmap.recycle()
                    }

                    continuation.resume(finalFaces)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)

                    // Clean up resized bitmap if we created one
                    if (processedBitmap != bitmap) {
                        processedBitmap.recycle()
                    }

                    continuation.resume(emptyList())
                }

            continuation.invokeOnCancellation {
                // Clean up resized bitmap if we created one
                if (processedBitmap != bitmap) {
                    processedBitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in face detection", e)
            continuation.resume(emptyList())
        }
    }

    /**
     * Generate a simple saliency map for when face detection fails
     * Uses center-weighted and edge detection approach
     */
    private fun generateSaliencyMap(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val resizedWidth = 32 // Use small size for efficiency
        val resizedHeight = 32

        // Resize the image for faster processing
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)

        // Create saliency map array
        val saliencyMap = FloatArray(resizedWidth * resizedHeight)

        // Process each pixel for saliency
        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {
                val pixel = resizedBitmap.getPixel(x, y)

                // Extract RGB values
                val r = Color.red(pixel) / 255f
                val g = Color.green(pixel) / 255f
                val b = Color.blue(pixel) / 255f

                // Calculate intensity
                val intensity = 0.299f * r + 0.587f * g + 0.114f * b

                // Calculate distance from center (normalized to 0-1)
                val centerX = resizedWidth / 2f
                val centerY = resizedHeight / 2f
                val distX = Math.abs(x - centerX) / centerX
                val distY = Math.abs(y - centerY) / centerY
                val dist = Math.sqrt((distX * distX + distY * distY).toDouble()).toFloat() / 1.414f

                // Center weighting factor (centers are more important)
                val centerWeight = 1.0f - dist

                // Simple saliency formula: intensity * centerWeight
                saliencyMap[y * resizedWidth + x] = intensity * centerWeight * CENTER_WEIGHT
            }
        }

        // Second pass: edge detection for additional saliency
        val edgeSaliencyMap = FloatArray(resizedWidth * resizedHeight)
        for (y in 1 until resizedHeight - 1) {
            for (x in 1 until resizedWidth - 1) {
                val index = y * resizedWidth + x

                // Simple Sobel-like edge detection
                val dx = abs(saliencyMap[(y) * resizedWidth + (x + 1)] -
                        saliencyMap[(y) * resizedWidth + (x - 1)])
                val dy = abs(saliencyMap[(y + 1) * resizedWidth + (x)] -
                        saliencyMap[(y - 1) * resizedWidth + (x)])

                // Edge magnitude
                val edge = min(1.0f, sqrt(dx * dx + dy * dy))

                // Combine with original saliency
                edgeSaliencyMap[index] = saliencyMap[index] + edge * 0.5f
            }
        }

        return edgeSaliencyMap
    }

    /**
     * Determine the best template layout based on photo analyses and screen orientation
     */
    fun determineBestLayout(
        photoAnalyses: List<PhotoAnalysis>,
        containerWidth: Int,
        containerHeight: Int
    ): Int {
        val isContainerLandscape = containerWidth > containerHeight

        // Calculate the number of photos with detectable faces
        val photosWithFaces = photoAnalyses.count { it.faces.isNotEmpty() }
        val totalPhotos = photoAnalyses.size

        // If there aren't enough photos, return single photo template
        if (totalPhotos < 2) {
            return -1 // Indicate should use single photo
        }

        // Get possible layouts based on container orientation
        val possibleLayouts = if (isContainerLandscape) {
            LANDSCAPE_COMPATIBLE_LAYOUTS
        } else {
            PORTRAIT_COMPATIBLE_LAYOUTS
        }

        // Determine required photo count for each layout
        val layoutPhotoCountMap = mapOf(
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL to 2,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL to 2,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_LEFT to 3,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_RIGHT to 3,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID to 4,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC to 3,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE to 3,
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY to 3
        )

        // Filter layouts based on available photo count
        val feasibleLayouts = possibleLayouts.filter { layoutType ->
            layoutPhotoCountMap[layoutType] ?: Int.MAX_VALUE <= totalPhotos
        }

        if (feasibleLayouts.isEmpty()) {
            return -1 // Not enough photos for any layout
        }

        // Score layouts based on photo content and orientation
        val layoutScores = mutableMapOf<Int, Float>()

        for (layoutType in feasibleLayouts) {
            var score = 0f

            // Analyze layout-specific factors
            when (layoutType) {
                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL -> {
                    // Prefer 2_VERTICAL if we have tall photos
                    score += photoAnalyses.count { it.isPortrait } * 0.5f
                    // Extra points if there are faces in both photos
                    if (photosWithFaces >= 2) score += 1.0f
                }

                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL -> {
                    // Prefer 2_HORIZONTAL if we have wide photos
                    score += photoAnalyses.count { it.isLandscape } * 0.5f
                    // Extra points if there are faces in both photos
                    if (photosWithFaces >= 2) score += 1.0f
                }

                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_LEFT,
                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_RIGHT -> {
                    // Main photo should have faces, smaller photos can be scenery
                    val mainPhotoHasFaces = photoAnalyses.any { it.faces.isNotEmpty() }
                    if (mainPhotoHasFaces) score += 2.0f
                    // Benefit from having more photos with faces
                    score += min(photosWithFaces, 3) * 0.3f
                }

                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID -> {
                    // Grid works well with square photos and multiple faces
                    score += photoAnalyses.count { it.isSquare } * 0.25f
                    score += min(photosWithFaces, 4) * 0.25f
                }

                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC,
                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE,
                EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY -> {
                    // Dynamic layouts benefit from diverse photo shapes
                    val hasPortrait = photoAnalyses.any { it.isPortrait }
                    val hasLandscape = photoAnalyses.any { it.isLandscape }
                    val hasSquare = photoAnalyses.any { it.isSquare }

                    if (hasPortrait && hasLandscape) score += 1.0f
                    if (hasSquare) score += 0.5f
                    score += min(photosWithFaces, photoAnalyses.size) * 0.2f
                }
            }

            // Store the score
            layoutScores[layoutType] = score
        }

        // Get the highest scoring layout
        val bestLayout = layoutScores.maxByOrNull { it.value }?.key
            ?: feasibleLayouts.random()

        Log.d(TAG, "Best layout determined: $bestLayout with scores: $layoutScores")
        return bestLayout
    }

    /**
     * Creates layout regions for a given template type
     */
    fun createLayoutRegions(
        templateType: Int,
        containerWidth: Int,
        containerHeight: Int,
        borderWidth: Float = BORDER_WIDTH
    ): List<LayoutRegion> {
        val regions = mutableListOf<LayoutRegion>()
        val halfBorderWidth = borderWidth / 2

        when (templateType) {
            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL -> {
                // Two stacked photos
                val topRegion = LayoutRegion(
                    rect = RectF(
                        0f,
                        0f,
                        containerWidth.toFloat(),
                        (containerHeight / 2f) - halfBorderWidth
                    ),
                    aspectRatio = containerWidth.toFloat() / ((containerHeight / 2f) - halfBorderWidth)
                )

                val bottomRegion = LayoutRegion(
                    rect = RectF(
                        0f,
                        (containerHeight / 2f) + halfBorderWidth,
                        containerWidth.toFloat(),
                        containerHeight.toFloat()
                    ),
                    aspectRatio = containerWidth.toFloat() / ((containerHeight / 2f) - halfBorderWidth)
                )

                regions.add(topRegion)
                regions.add(bottomRegion)
            }

            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL -> {
                // Two side-by-side photos
                val leftRegion = LayoutRegion(
                    rect = RectF(
                        0f,
                        0f,
                        (containerWidth / 2f) - halfBorderWidth,
                        containerHeight.toFloat()
                    ),
                    aspectRatio = ((containerWidth / 2f) - halfBorderWidth) / containerHeight.toFloat()
                )

                val rightRegion = LayoutRegion(
                    rect = RectF(
                        (containerWidth / 2f) + halfBorderWidth,
                        0f,
                        containerWidth.toFloat(),
                        containerHeight.toFloat()
                    ),
                    aspectRatio = ((containerWidth / 2f) - halfBorderWidth) / containerHeight.toFloat()
                )

                regions.add(leftRegion)
                regions.add(rightRegion)
            }

            // In createLayoutRegions()

            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_SMART -> {
                val isLandscape = containerWidth > containerHeight
                // Use a truly random decision every time this code runs:
                val randomBool = java.util.Random().nextBoolean()
                // Alternatively: val randomBool = (System.nanoTime() % 2L == 0L)

                if (isLandscape) {
                    if (randomBool) {
                        // Main LEFT variant
                        val leftSectionWidth = (containerWidth * 0.6f).toInt()
                        val rightSectionWidth = containerWidth - leftSectionWidth

                        val mainRegion = LayoutRegion(
                            rect = RectF(
                                0f,
                                0f,
                                leftSectionWidth - halfBorderWidth,
                                containerHeight.toFloat()
                            ),
                            aspectRatio = (leftSectionWidth - halfBorderWidth) / containerHeight.toFloat()
                        )
                        val topRightRegion = LayoutRegion(
                            rect = RectF(
                                leftSectionWidth + halfBorderWidth,
                                0f,
                                containerWidth.toFloat(),
                                (containerHeight / 2f) - halfBorderWidth
                            ),
                            aspectRatio = rightSectionWidth.toFloat() / ((containerHeight / 2f) - halfBorderWidth)
                        )
                        val bottomRightRegion = LayoutRegion(
                            rect = RectF(
                                leftSectionWidth + halfBorderWidth,
                                (containerHeight / 2f) + halfBorderWidth,
                                containerWidth.toFloat(),
                                containerHeight.toFloat()
                            ),
                            aspectRatio = rightSectionWidth.toFloat() / ((containerHeight / 2f) - halfBorderWidth)
                        )
                        regions.add(mainRegion)
                        regions.add(topRightRegion)
                        regions.add(bottomRightRegion)
                    } else {
                        // Main RIGHT variant
                        val rightSectionWidth = (containerWidth * 0.6f).toInt()
                        val leftSectionWidth = containerWidth - rightSectionWidth

                        val mainRegion = LayoutRegion(
                            rect = RectF(
                                leftSectionWidth + halfBorderWidth,
                                0f,
                                containerWidth.toFloat(),
                                containerHeight.toFloat()
                            ),
                            aspectRatio = (rightSectionWidth - halfBorderWidth) / containerHeight.toFloat()
                        )
                        val topLeftRegion = LayoutRegion(
                            rect = RectF(
                                0f,
                                0f,
                                leftSectionWidth - halfBorderWidth,
                                (containerHeight / 2f) - halfBorderWidth
                            ),
                            aspectRatio = (leftSectionWidth - halfBorderWidth) / ((containerHeight / 2f) - halfBorderWidth)
                        )
                        val bottomLeftRegion = LayoutRegion(
                            rect = RectF(
                                0f,
                                (containerHeight / 2f) + halfBorderWidth,
                                leftSectionWidth - halfBorderWidth,
                                containerHeight.toFloat()
                            ),
                            aspectRatio = (leftSectionWidth - halfBorderWidth) / ((containerHeight / 2f) - halfBorderWidth)
                        )
                        regions.add(mainRegion)
                        regions.add(topLeftRegion)
                        regions.add(bottomLeftRegion)
                    }
                } else {
                    if (randomBool) {
                        // Main TOP variant
                        val mainHeight = (containerHeight * 0.6f)
                        val bottomHeight = containerHeight - mainHeight

                        val mainRegion = LayoutRegion(
                            rect = RectF(
                                0f,
                                0f,
                                containerWidth.toFloat(),
                                mainHeight - halfBorderWidth
                            ),
                            aspectRatio = containerWidth.toFloat() / (mainHeight - halfBorderWidth)
                        )
                        val bottomLeftRegion = LayoutRegion(
                            rect = RectF(
                                0f,
                                mainHeight + halfBorderWidth,
                                (containerWidth / 2f) - halfBorderWidth,
                                containerHeight.toFloat()
                            ),
                            aspectRatio = ((containerWidth / 2f) - halfBorderWidth) / (containerHeight - mainHeight - halfBorderWidth)
                        )
                        val bottomRightRegion = LayoutRegion(
                            rect = RectF(
                                (containerWidth / 2f) + halfBorderWidth,
                                mainHeight + halfBorderWidth,
                                containerWidth.toFloat(),
                                containerHeight.toFloat()
                            ),
                            aspectRatio = ((containerWidth / 2f) - halfBorderWidth) / (containerHeight - mainHeight - halfBorderWidth)
                        )
                        regions.add(mainRegion)
                        regions.add(bottomLeftRegion)
                        regions.add(bottomRightRegion)
                    } else {
                        // Main BOTTOM variant
                        val mainHeight = (containerHeight * 0.6f)
                        val topHeight = containerHeight - mainHeight

                        val mainRegion = LayoutRegion(
                            rect = RectF(
                                0f,
                                containerHeight - mainHeight + halfBorderWidth,
                                containerWidth.toFloat(),
                                containerHeight.toFloat()
                            ),
                            aspectRatio = containerWidth.toFloat() / (mainHeight - halfBorderWidth)
                        )
                        val topLeftRegion = LayoutRegion(
                            rect = RectF(
                                0f,
                                0f,
                                (containerWidth / 2f) - halfBorderWidth,
                                topHeight - halfBorderWidth
                            ),
                            aspectRatio = ((containerWidth / 2f) - halfBorderWidth) / (topHeight - halfBorderWidth)
                        )
                        val topRightRegion = LayoutRegion(
                            rect = RectF(
                                (containerWidth / 2f) + halfBorderWidth,
                                0f,
                                containerWidth.toFloat(),
                                topHeight - halfBorderWidth
                            ),
                            aspectRatio = ((containerWidth / 2f) - halfBorderWidth) / (topHeight - halfBorderWidth)
                        )
                        regions.add(mainRegion)
                        regions.add(topLeftRegion)
                        regions.add(topRightRegion)
                    }
                }
            }

            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_LEFT -> {
                // Large photo on left, two smaller on right
                val leftSectionWidth = (containerWidth * 0.6f).toInt()
                val rightSectionWidth = containerWidth - leftSectionWidth

                val mainRegion = LayoutRegion(
                    rect = RectF(
                        0f,
                        0f,
                        leftSectionWidth - halfBorderWidth,
                        containerHeight.toFloat()
                    ),
                    aspectRatio = (leftSectionWidth - halfBorderWidth) / containerHeight.toFloat()
                )

                val topRightRegion = LayoutRegion(
                    rect = RectF(
                        leftSectionWidth + halfBorderWidth,
                        0f,
                        containerWidth.toFloat(),
                        (containerHeight / 2f) - halfBorderWidth
                    ),
                    aspectRatio = rightSectionWidth.toFloat() / ((containerHeight / 2f) - halfBorderWidth)
                )

                val bottomRightRegion = LayoutRegion(
                    rect = RectF(
                        leftSectionWidth + halfBorderWidth,
                        (containerHeight / 2f) + halfBorderWidth,
                        containerWidth.toFloat(),
                        containerHeight.toFloat()
                    ),
                    aspectRatio = rightSectionWidth.toFloat() / ((containerHeight / 2f) - halfBorderWidth)
                )

                regions.add(mainRegion)
                regions.add(topRightRegion)
                regions.add(bottomRightRegion)
            }

            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_RIGHT -> {
                // Large photo on right, two smaller on left
                val rightSectionWidth = (containerWidth * 0.6f).toInt()
                val leftSectionWidth = containerWidth - rightSectionWidth

                val mainRegion = LayoutRegion(
                    rect = RectF(
                        leftSectionWidth + halfBorderWidth,
                        0f,
                        containerWidth.toFloat(),
                        containerHeight.toFloat()
                    ),
                    aspectRatio = (rightSectionWidth - halfBorderWidth) / containerHeight.toFloat()
                )

                val topLeftRegion = LayoutRegion(
                    rect = RectF(
                        0f,
                        0f,
                        leftSectionWidth - halfBorderWidth,
                        (containerHeight / 2f) - halfBorderWidth
                    ),
                    aspectRatio = (leftSectionWidth - halfBorderWidth) / ((containerHeight / 2f) - halfBorderWidth)
                )

                val bottomLeftRegion = LayoutRegion(
                    rect = RectF(
                        0f,
                        (containerHeight / 2f) + halfBorderWidth,
                        leftSectionWidth - halfBorderWidth,
                        containerHeight.toFloat()
                    ),
                    aspectRatio = (leftSectionWidth - halfBorderWidth) / ((containerHeight / 2f) - halfBorderWidth)
                )

                regions.add(mainRegion)
                regions.add(topLeftRegion)
                regions.add(bottomLeftRegion)
            }

            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID -> {
                // Four photos in a grid
                regions.add(
                    LayoutRegion(
                        rect = RectF(
                            0f,
                            0f,
                            (containerWidth / 2f) - halfBorderWidth,
                            (containerHeight / 2f) - halfBorderWidth
                        ),
                        aspectRatio = 1.0f
                    )
                )

                regions.add(
                    LayoutRegion(
                        rect = RectF(
                            (containerWidth / 2f) + halfBorderWidth,
                            0f,
                            containerWidth.toFloat(),
                            (containerHeight / 2f) - halfBorderWidth
                        ),
                        aspectRatio = 1.0f
                    )
                )

                regions.add(
                    LayoutRegion(
                        rect = RectF(
                            0f,
                            (containerHeight / 2f) + halfBorderWidth,
                            (containerWidth / 2f) - halfBorderWidth,
                            containerHeight.toFloat()
                        ),
                        aspectRatio = 1.0f
                    )
                )

                regions.add(
                    LayoutRegion(
                        rect = RectF(
                            (containerWidth / 2f) + halfBorderWidth,
                            (containerHeight / 2f) + halfBorderWidth,
                            containerWidth.toFloat(),
                            containerHeight.toFloat()
                        ),
                        aspectRatio = 1.0f
                    )
                )
            }

            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY -> {
                val isLandscape = containerWidth > containerHeight

                if (isLandscape) {
                    // LANDSCAPE ORIENTATION - 6 photos truly irregular masonry

                    // Define irregular grid structure with distinctly different sized regions

                    // Left section: 35% of width with two unequal photos (top 40%, bottom 60%)
                    val leftSectionWidth = containerWidth * 0.35f
                    val leftSectionSplit = containerHeight * 0.4f

                    // Middle section: 30% of width with three unequal photos
                    val middleSectionWidth = containerWidth * 0.3f
                    val middleSection1Height = containerHeight * 0.25f
                    val middleSection2Height = containerHeight * 0.45f
                    // Middle section 3 takes the remaining height

                    // Right section: 35% of width with a single large photo

                    // Photo 1: Top-left (medium)
                    val region1 = LayoutRegion(
                        rect = RectF(
                            0f,
                            0f,
                            leftSectionWidth - halfBorderWidth,
                            leftSectionSplit - halfBorderWidth
                        ),
                        aspectRatio = (leftSectionWidth - halfBorderWidth) / (leftSectionSplit - halfBorderWidth)
                    )

                    // Photo 2: Bottom-left (slightly larger)
                    val region2 = LayoutRegion(
                        rect = RectF(
                            0f,
                            leftSectionSplit + halfBorderWidth,
                            leftSectionWidth - halfBorderWidth,
                            containerHeight.toFloat()
                        ),
                        aspectRatio = (leftSectionWidth - halfBorderWidth) / (containerHeight - leftSectionSplit - halfBorderWidth)
                    )

                    // Photo 3: Top-middle (small)
                    val region3 = LayoutRegion(
                        rect = RectF(
                            leftSectionWidth + halfBorderWidth,
                            0f,
                            leftSectionWidth + middleSectionWidth - halfBorderWidth,
                            middleSection1Height - halfBorderWidth
                        ),
                        aspectRatio = (middleSectionWidth - borderWidth) / (middleSection1Height - halfBorderWidth)
                    )

                    // Photo 4: Middle-middle (medium)
                    val region4 = LayoutRegion(
                        rect = RectF(
                            leftSectionWidth + halfBorderWidth,
                            middleSection1Height + halfBorderWidth,
                            leftSectionWidth + middleSectionWidth - halfBorderWidth,
                            middleSection1Height + middleSection2Height - halfBorderWidth
                        ),
                        aspectRatio = (middleSectionWidth - borderWidth) / (middleSection2Height - borderWidth)
                    )

                    // Photo 5: Bottom-middle (small)
                    val region5 = LayoutRegion(
                        rect = RectF(
                            leftSectionWidth + halfBorderWidth,
                            middleSection1Height + middleSection2Height + halfBorderWidth,
                            leftSectionWidth + middleSectionWidth - halfBorderWidth,
                            containerHeight.toFloat()
                        ),
                        aspectRatio = (middleSectionWidth - borderWidth) / (containerHeight - middleSection1Height - middleSection2Height - halfBorderWidth)
                    )

                    // Photo 6: Right (largest, full height)
                    val region6 = LayoutRegion(
                        rect = RectF(
                            leftSectionWidth + middleSectionWidth + halfBorderWidth,
                            0f,
                            containerWidth.toFloat(),
                            containerHeight.toFloat()
                        ),
                        aspectRatio = (containerWidth - leftSectionWidth - middleSectionWidth - halfBorderWidth) / containerHeight.toFloat()
                    )

                    regions.add(region1)
                    regions.add(region2)
                    regions.add(region3)
                    regions.add(region4)
                    regions.add(region5)
                    regions.add(region6)
                } else {
                    // PORTRAIT ORIENTATION - 5 photos truly irregular masonry

                    // Top section: 35% of height with two unequal photos (left 40%, right 60%)
                    val topSectionHeight = containerHeight * 0.38f
                    val topSectionSplit = containerWidth * 0.42f

                    // Middle section: 30% of height with two unequal photos (left 55%, right 45%)
                    val middleSectionHeight = containerHeight * 0.32f
                    val middleSectionSplit = containerWidth * 0.55f

                    // Bottom section: 30% of height with a single large photo

                    // Photo 1: Top-left (small)
                    val region1 = LayoutRegion(
                        rect = RectF(
                            0f,
                            0f,
                            topSectionSplit - halfBorderWidth,
                            topSectionHeight - halfBorderWidth
                        ),
                        aspectRatio = (topSectionSplit - halfBorderWidth) / (topSectionHeight - halfBorderWidth)
                    )

                    // Photo 2: Top-right (medium)
                    val region2 = LayoutRegion(
                        rect = RectF(
                            topSectionSplit + halfBorderWidth,
                            0f,
                            containerWidth.toFloat(),
                            topSectionHeight - halfBorderWidth
                        ),
                        aspectRatio = (containerWidth - topSectionSplit - halfBorderWidth) / (topSectionHeight - halfBorderWidth)
                    )

                    // Photo 3: Middle-left (wide)
                    val region3 = LayoutRegion(
                        rect = RectF(
                            0f,
                            topSectionHeight + halfBorderWidth,
                            middleSectionSplit - halfBorderWidth,
                            topSectionHeight + middleSectionHeight - halfBorderWidth
                        ),
                        aspectRatio = (middleSectionSplit - halfBorderWidth) / (middleSectionHeight - borderWidth)
                    )

                    // Photo 4: Middle-right (narrow)
                    val region4 = LayoutRegion(
                        rect = RectF(
                            middleSectionSplit + halfBorderWidth,
                            topSectionHeight + halfBorderWidth,
                            containerWidth.toFloat(),
                            topSectionHeight + middleSectionHeight - halfBorderWidth
                        ),
                        aspectRatio = (containerWidth - middleSectionSplit - halfBorderWidth) / (middleSectionHeight - borderWidth)
                    )

                    // Photo 5: Bottom (largest, full width)
                    val region5 = LayoutRegion(
                        rect = RectF(
                            0f,
                            topSectionHeight + middleSectionHeight + halfBorderWidth,
                            containerWidth.toFloat(),
                            containerHeight.toFloat()
                        ),
                        aspectRatio = containerWidth.toFloat() / (containerHeight - topSectionHeight - middleSectionHeight - halfBorderWidth)
                    )

                    regions.add(region1)
                    regions.add(region2)
                    regions.add(region3)
                    regions.add(region4)
                    regions.add(region5)
                }
            }

            EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE -> {
                // Use more photos for dynamic collage (minimum 12, more for larger screens)
                val screenArea = containerWidth * containerHeight
                val requiredPhotoCount = max(12, min(25, screenArea / (250 * 250)))

                Log.d(TAG, "Dynamic Collage requested with $requiredPhotoCount photos")

                // Create scattered collage layout with rotated photos
                return createScatteredCollageLayout(
                    containerWidth,
                    containerHeight,
                    requiredPhotoCount
                )
            }
        }

        return regions
    }

    /**
     * Create a scattered photo collage layout with rotation and natural overlapping
     * This simulates photos thrown on a table with random angles and positions
     * while ensuring better coverage with larger photos
     */
    fun createScatteredCollageLayout(
        containerWidth: Int,
        containerHeight: Int,
        requestedPhotoCount: Int,
        maxPhotoSize: Float = 0.65f // Increased from 0.55f for bigger photos
    ): List<LayoutRegion> {
        val regions = mutableListOf<LayoutRegion>()
        val random = java.util.Random()

        // Safety check
        if (containerWidth <= 0 || containerHeight <= 0) {
            Log.e(TAG, "Invalid container dimensions for scattered collage")
            return regions
        }

        // Calculate optimal photo count based on screen size - use FEWER photos for larger displays
        // to allow photos to be bigger and reduce crowding
        val minPhotos = 8  // Reduced from 12
        val screenArea = containerWidth * containerHeight
        val baseCount = (screenArea / (350 * 350)).coerceIn(minPhotos, 20) // Larger divisor = fewer photos

        // Use fewer photos than requested to ensure better sizing
        val effectiveCount = min(baseCount, requestedPhotoCount)

        Log.d(TAG, "Creating scattered collage with $effectiveCount photos for screen ${containerWidth}x${containerHeight}")

        // Calculate optimal photo size to ensure good coverage
        // Larger photos allow for better visibility of content
        val containerDiagonal = sqrt(containerWidth.toFloat() * containerWidth +
                containerHeight.toFloat() * containerHeight)

        // Calculate larger photo sizes for better visibility
        val optimalSize = min(
            containerDiagonal * 0.3f, // Increased from 0.22f for bigger photos
            min(containerWidth, containerHeight) * maxPhotoSize
        )

        // Create a coverage map to ensure even distribution
        val coverageMap = Array(containerWidth) { FloatArray(containerHeight) { 0f } }

        // Create a placement grid with lower density for better spacing
        val gridCellSize = (optimalSize * 0.85f).toInt() // Increased from 0.65f to reduce overlap
        val gridRows = (containerHeight / gridCellSize) + 1
        val gridCols = (containerWidth / gridCellSize) + 1
        val usedCells = mutableSetOf<Pair<Int, Int>>()

        // Check if we're in landscape mode
        val isLandscape = containerWidth > containerHeight

        // Initialize important points that must be covered to ensure full screen usage
        val screenCoverage = mutableListOf(
            Pair(containerWidth * 0.05f, containerHeight * 0.05f), // Top-left
            Pair(containerWidth * 0.95f, containerHeight * 0.05f), // Top-right
            Pair(containerWidth * 0.05f, containerHeight * 0.95f), // Bottom-left
            Pair(containerWidth * 0.95f, containerHeight * 0.95f), // Bottom-right
            Pair(containerWidth * 0.5f, containerHeight * 0.05f),  // Top-center
            Pair(containerWidth * 0.5f, containerHeight * 0.95f),  // Bottom-center
            Pair(containerWidth * 0.05f, containerHeight * 0.5f),  // Left-center
            Pair(containerWidth * 0.95f, containerHeight * 0.5f),  // Right-center
            Pair(containerWidth * 0.5f, containerHeight * 0.5f),   // Center
            Pair(containerWidth * 0.25f, containerHeight * 0.25f), // Top-left quadrant
            Pair(containerWidth * 0.75f, containerHeight * 0.25f), // Top-right quadrant
            Pair(containerWidth * 0.25f, containerHeight * 0.75f), // Bottom-left quadrant
            Pair(containerWidth * 0.75f, containerHeight * 0.75f)  // Bottom-right quadrant
        )

        // Force at least one large photo directly in the right-bottom area in landscape mode
        // Precisely positioned to cover the commonly empty area
        if (isLandscape) {
            // Remove any existing right-bottom point
            screenCoverage.removeAll { it.first > containerWidth * 0.6f && it.second > containerHeight * 0.6f }

            // Add a targeted point for the right-bottom empty area
            screenCoverage.add(0, Pair(containerWidth * 0.8f, containerHeight * 0.75f))
        }

        // Track placed regions for better distribution
        val placedCenters = mutableListOf<PointF>()

        // Place photos at strategic points first for better coverage
        val strategicPoints = screenCoverage.take(min(screenCoverage.size, effectiveCount))
        for (i in 0 until strategicPoints.size) {
            val position = strategicPoints[i]

            // Extra large photo in right-bottom for landscape mode (first position from our priority list)
            val isRightBottomLandscape = isLandscape && i == 0 &&
                    position.first > containerWidth * 0.6f &&
                    position.second > containerHeight * 0.6f

            // Size variation - give right-bottom area a much larger photo in landscape
            val sizeVariation = if (isRightBottomLandscape) {
                1.3f + (random.nextFloat() * 0.3f) // 130-160% size for right-bottom
            } else {
                0.85f + (random.nextFloat() * 0.55f) // 85-140% normal size variation
            }

            val photoSize = optimalSize * sizeVariation

            // Random aspect ratio - make right-bottom photo wider in landscape to cover more area
            val aspectRatio = if (isRightBottomLandscape) {
                1.1f + (random.nextFloat() * 0.3f) // 1.1-1.4 (wider) for right-bottom
            } else {
                0.65f + (random.nextFloat() * 1.05f) // 0.65-1.7 normal variation
            }

            // Calculate width and height based on aspect ratio
            val photoWidth: Float
            val photoHeight: Float

            if (aspectRatio < 1.0f) {
                photoHeight = photoSize
                photoWidth = photoHeight * aspectRatio
            } else {
                photoWidth = photoSize
                photoHeight = photoWidth / aspectRatio
            }

            // Random rotation angle - less rotation for right-bottom in landscape
            val rotation = if (isRightBottomLandscape) {
                -15f + (random.nextFloat() * 30f) // Less rotation (-15 to +15) for right-bottom
            } else {
                -35f + (random.nextFloat() * 70f) // Normal rotation (-35 to +35)
            }

            // Less position variation for right-bottom area to ensure proper placement
            val posVariation = if (isRightBottomLandscape) 0.05f else 0.2f
            val centerX = position.first + (random.nextFloat() * gridCellSize * posVariation - gridCellSize * posVariation/2)
            val centerY = position.second + (random.nextFloat() * gridCellSize * posVariation - gridCellSize * posVariation/2)

            // Calculate corners of rectangle
            val rect = RectF(
                centerX - photoWidth / 2,
                centerY - photoHeight / 2,
                centerX + photoWidth / 2,
                centerY + photoHeight / 2
            )

            // Create layout region with rotation data
            val layoutRegion = LayoutRegion(
                rect = rect,
                aspectRatio = aspectRatio
            )

            // Store rotation in suitability scores
            layoutRegion.photoSuitabilityScores[-1] = rotation

            // Track placement and update coverage map
            placedCenters.add(PointF(centerX, centerY))
            updateCoverageMap(coverageMap, centerX, centerY, photoWidth, photoHeight, containerWidth, containerHeight)

            regions.add(layoutRegion)
        }

        // Then add photos to fill the rest of the screen
        for (i in regions.size until effectiveCount) {
            // Size variation (80-140% of optimal size) - larger average size
            val sizeVariation = 0.8f + (random.nextFloat() * 0.6f)
            val photoSize = optimalSize * sizeVariation

            // Wider variation in aspect ratio (0.65 to 1.7)
            val aspectRatio = 0.65f + (random.nextFloat() * 1.05f)

            // Calculate width and height based on aspect ratio
            val photoWidth: Float
            val photoHeight: Float

            if (aspectRatio < 1.0f) {
                photoHeight = photoSize
                photoWidth = photoHeight * aspectRatio
            } else {
                photoWidth = photoSize
                photoHeight = photoWidth / aspectRatio
            }

            // Random rotation angle between -35 and 35 degrees
            val rotation = -35f + (random.nextFloat() * 70f)

            // Special handling for landscape mode - prioritize right half bottom positions
            val position: Pair<Float, Float> = if (isLandscape && i % 3 == 0) {
                // Specifically target right-bottom quadrant with some variation
                val rightX = containerWidth * (0.7f + random.nextFloat() * 0.25f)
                val bottomY = containerHeight * (0.6f + random.nextFloat() * 0.35f)

                // Skip if very close to other photos
                var tooClose = false
                for (center in placedCenters) {
                    val distance = sqrt((rightX - center.x) * (rightX - center.x) +
                            (bottomY - center.y) * (bottomY - center.y))
                    if (distance < photoWidth * 0.5f) {
                        tooClose = true
                        break
                    }
                }

                if (tooClose) {
                    findLowCoveragePosition(
                        coverageMap,
                        containerWidth,
                        containerHeight,
                        photoWidth,
                        photoHeight,
                        placedCenters,
                        gridCellSize,
                        isLandscape
                    )
                } else {
                    Pair(rightX, bottomY)
                }
            } else {
                findLowCoveragePosition(
                    coverageMap,
                    containerWidth,
                    containerHeight,
                    photoWidth,
                    photoHeight,
                    placedCenters,
                    gridCellSize,
                    isLandscape
                )
            }

            val centerX = position.first
            val centerY = position.second

            // Ensure at least 80% of the photo is on screen
            val adjustedCenterX = centerX.coerceIn(photoWidth * 0.4f, containerWidth - photoWidth * 0.4f)
            val adjustedCenterY = centerY.coerceIn(photoHeight * 0.4f, containerHeight - photoHeight * 0.4f)

            // Calculate rectangle
            val rect = RectF(
                adjustedCenterX - photoWidth / 2,
                adjustedCenterY - photoHeight / 2,
                adjustedCenterX + photoWidth / 2,
                adjustedCenterY + photoHeight / 2
            )

            // Create layout region with rotation data
            val layoutRegion = LayoutRegion(
                rect = rect,
                aspectRatio = aspectRatio
            )

            // Store rotation in suitability scores
            layoutRegion.photoSuitabilityScores[-1] = rotation

            // Track placement and update coverage map
            placedCenters.add(PointF(adjustedCenterX, adjustedCenterY))
            updateCoverageMap(coverageMap, adjustedCenterX, adjustedCenterY, photoWidth, photoHeight,
                containerWidth, containerHeight)

            regions.add(layoutRegion)
        }

        // Add one extra larger photo specifically targeting right-bottom in landscape mode
        // This is a last-resort addition if we still have empty space
        if (isLandscape) {
            // Create a large photo specifically for the right-bottom area
            val extraSizeVariation = 1.2f + (random.nextFloat() * 0.3f) // 120-150% size
            val extraPhotoSize = optimalSize * extraSizeVariation

            // Make it wider for better coverage
            val extraAspectRatio = 1.1f + (random.nextFloat() * 0.4f)

            val extraPhotoWidth = extraPhotoSize
            val extraPhotoHeight = extraPhotoWidth / extraAspectRatio

            // Limited rotation for better coverage
            val extraRotation = -10f + (random.nextFloat() * 20f)

            // Precisely position in the right-bottom area
            val extraCenterX = containerWidth * 0.78f
            val extraCenterY = containerHeight * 0.76f

            // Check if this would overlap too much with existing photos
            var tooClose = false
            for (center in placedCenters) {
                val distance = sqrt(
                    (extraCenterX - center.x) * (extraCenterX - center.x) +
                            (extraCenterY - center.y) * (extraCenterY - center.y)
                )
                // Only add if not too crowded
                if (distance < extraPhotoWidth * 0.4f) {
                    tooClose = true
                    break
                }
            }

            // If not too close, add the extra photo
            if (!tooClose) {
                val rect = RectF(
                    extraCenterX - extraPhotoWidth / 2,
                    extraCenterY - extraPhotoHeight / 2,
                    extraCenterX + extraPhotoWidth / 2,
                    extraCenterY + extraPhotoHeight / 2
                )

                val layoutRegion = LayoutRegion(
                    rect = rect,
                    aspectRatio = extraAspectRatio
                )

                layoutRegion.photoSuitabilityScores[-1] = extraRotation
                regions.add(layoutRegion)
            }
        }

        Log.d(TAG, "Created enhanced scattered collage layout with ${regions.size} photos")
        return regions
    }

    /**
     * Find a position with low photo coverage
     */
    private fun findLowCoveragePosition(
        coverageMap: Array<FloatArray>,
        containerWidth: Int,
        containerHeight: Int,
        photoWidth: Float,
        photoHeight: Float,
        placedCenters: List<PointF>,
        gridCellSize: Int,
        isLandscape: Boolean
    ): Pair<Float, Float> {
        val random = java.util.Random()
        var bestScore = Float.MAX_VALUE
        var bestX = containerWidth / 2f
        var bestY = containerHeight / 2f

        // Try multiple positions to find one with least coverage
        val sampleCount = 15

        for (i in 0 until sampleCount) {
            // Generate a random position
            val x = random.nextFloat() * containerWidth
            val y = random.nextFloat() * containerHeight

            // Skip if too close to container edge
            if (x < photoWidth * 0.4f || x > containerWidth - photoWidth * 0.4f ||
                y < photoHeight * 0.4f || y > containerHeight - photoHeight * 0.4f) {
                continue
            }

            // Calculate coverage score for this position
            var score = 0f

            // Calculate bounds
            val left = max(0, (x - photoWidth / 2).toInt())
            val top = max(0, (y - photoHeight / 2).toInt())
            val right = min(containerWidth - 1, (x + photoWidth / 2).toInt())
            val bottom = min(containerHeight - 1, (y + photoHeight / 2).toInt())

            // Sample coverage
            for (sx in left..right step gridCellSize/2) {
                for (sy in top..bottom step gridCellSize/2) {
                    if (sx < containerWidth && sy < containerHeight) {
                        score += coverageMap[sx][sy]
                    }
                }
            }

            // Add penalty for proximity to other photos
            for (center in placedCenters) {
                val distance = sqrt((x - center.x) * (x - center.x) + (y - center.y) * (y - center.y))

                // Stronger avoidance of very close photos
                if (distance < gridCellSize) {
                    score += (gridCellSize - distance) * 8f
                }
            }

            // Prefer positions away from center (to ensure corners get filled)
            val distanceFromCenter = sqrt(
                (x - containerWidth/2f) * (x - containerWidth/2f) +
                        (y - containerHeight/2f) * (y - containerHeight/2f)
            )
            val maxDistance = sqrt((containerWidth/2f * containerWidth/2f) +
                    (containerHeight/2f * containerHeight/2f))

            // Adjust score with distance bonus (less penalty for edge positions)
            score -= (distanceFromCenter / maxDistance) * 1.5f

            // In landscape mode, give a STRONG bonus to right-bottom quadrant
            if (isLandscape && x > containerWidth * 0.65f && y > containerHeight * 0.55f) {
                score -= 4.0f // Much stronger preference
            }

            // If this position has better score, use it
            if (score < bestScore) {
                bestScore = score
                bestX = x
                bestY = y
            }
        }

        return Pair(bestX, bestY)
    }

    /**
     * Update coverage map to track where photos have been placed
     */
    private fun updateCoverageMap(
        coverageMap: Array<FloatArray>,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        containerWidth: Int,
        containerHeight: Int
    ) {
        val left = max(0, (centerX - width / 2).toInt())
        val top = max(0, (centerY - height / 2).toInt())
        val right = min(containerWidth - 1, (centerX + width / 2).toInt())
        val bottom = min(containerHeight - 1, (centerY + height / 2).toInt())

        for (x in left..right) {
            for (y in top..bottom) {
                coverageMap[x][y] += 1.0f
            }
        }
    }

    /**
     * Apply rotation to a photo for scattered collage effect
     * Enhanced with slight shadow for depth
     */
    fun applyPhotoRotation(
        canvas: Canvas,
        bitmap: Bitmap,
        region: LayoutRegion,
        paint: Paint
    ) {
        // Get rotation angle from the region's suitability scores
        val rotation = region.photoSuitabilityScores[-1] ?: 0f

        // Calculate center point of the region
        val centerX = region.rect.centerX()
        val centerY = region.rect.centerY()

        // Create shadow paint
        val shadowPaint = Paint(paint)
        shadowPaint.colorFilter = null
        shadowPaint.setShadowLayer(10f, 3f, 3f, Color.argb(90, 0, 0, 0))

        // Save canvas state before rotation
        canvas.save()

        // Rotate canvas around center point
        canvas.rotate(rotation, centerX, centerY)

        // Draw a subtle shadow for the rotated photo (rotated with the canvas)
        val shadowRect = RectF(
            region.rect.left + 3f,
            region.rect.top + 3f,
            region.rect.right + 3f,
            region.rect.bottom + 3f
        )
        canvas.drawRect(shadowRect, shadowPaint)

        // Draw the photo
        canvas.drawBitmap(
            bitmap,
            null,
            region.rect,
            paint
        )

        // Restore canvas state
        canvas.restore()
    }

    /**
     * Assign photos to regions to optimize layout aesthetics
     */
    fun assignPhotosToRegions(
        photoAnalyses: List<PhotoAnalysis>,
        regions: List<LayoutRegion>
    ): Map<Int, Int> {
        val assignments = mutableMapOf<Int, Int>()

        // Quick safety check
        if (photoAnalyses.isEmpty() || regions.isEmpty()) {
            Log.w(TAG, "Unable to assign photos to regions: no photos or regions")
            return assignments
        }

        // Special case for DYNAMIC_COLLAGE - no need for complex assignment
        // Check if these are rotated regions (used in scattered collage)
        val isRotatedLayout = regions.isNotEmpty() && regions[0].photoSuitabilityScores.containsKey(-1)

        if (isRotatedLayout) {
            Log.d(TAG, "Assigning photos to rotated collage regions: ${regions.size} regions, ${photoAnalyses.size} photos")
            // For rotated collage, just assign photos in sequence (or randomly)
            val photoIndices = photoAnalyses.indices.toList().shuffled() // Random order

            for (regionIndex in regions.indices) {
                if (regionIndex < photoIndices.size) {
                    assignments[regionIndex] = photoIndices[regionIndex]
                }
            }

            return assignments
        }

        // For other layouts, use more sophisticated assignment based on photo content

        // Initialize suitability scores for each photo in each region
        for (regionIndex in regions.indices) {
            val region = regions[regionIndex]
            for (photoIndex in photoAnalyses.indices) {
                val photo = photoAnalyses[photoIndex]
                val score = calculateSuitabilityScore(photo, region)
                region.photoSuitabilityScores[photoIndex] = score
            }
        }

        // Create sets to track which photos and regions have been assigned
        val assignedPhotos = mutableSetOf<Int>()
        val assignedRegions = mutableSetOf<Int>()

        // Use greedy algorithm to assign photos to regions
        // First, handle any "must have" assignments (very high scores)
        for (regionIndex in regions.indices) {
            val region = regions[regionIndex]

            // Find best photo for this region that hasn't been assigned yet
            val candidates = region.photoSuitabilityScores
                .filter { (photoIndex, score) ->
                    !assignedPhotos.contains(photoIndex) && score >= 0.9f
                }

            if (candidates.isNotEmpty()) {
                val (photoIndex, _) = candidates.maxByOrNull { it.value } ?: continue
                assignments[regionIndex] = photoIndex
                assignedPhotos.add(photoIndex)
                assignedRegions.add(regionIndex)
            }
        }

        // Next, assign remaining regions based on best available score
        for (regionIndex in regions.indices) {
            if (assignedRegions.contains(regionIndex)) continue

            val region = regions[regionIndex]

            // Find best photo for this region that hasn't been assigned yet
            val candidates = region.photoSuitabilityScores
                .filter { (photoIndex, _) -> !assignedPhotos.contains(photoIndex) }

            if (candidates.isNotEmpty()) {
                val (photoIndex, _) = candidates.maxByOrNull { it.value } ?: continue
                assignments[regionIndex] = photoIndex
                assignedPhotos.add(photoIndex)
                assignedRegions.add(regionIndex)
            }
        }

        // If we have more regions than photos, wrap around
        if (assignments.size < regions.size) {
            for (regionIndex in regions.indices) {
                if (!assignments.containsKey(regionIndex)) {
                    // Assign a photo that's already been used elsewhere
                    val photoIndex = regionIndex % photoAnalyses.size
                    assignments[regionIndex] = photoIndex
                }
            }
        }

        return assignments
    }

    /**
     * Calculate how suitable a photo is for a specific region
     */
    private fun calculateSuitabilityScore(photo: PhotoAnalysis, region: LayoutRegion): Float {
        var score = 0.5f // Base score

        // Aspect ratio match is important
        val aspectRatioScore = calculateAspectRatioScore(photo.aspectRatio, region.aspectRatio)
        score += aspectRatioScore * 0.4f // Weight aspect ratio heavily

        // If face detection succeeded, consider face positioning
        if (photo.faceDetectionSucceeded && photo.dominantFaceRegion != null) {
            // For a region with aspect ratio close to the photo, faces are more important
            if (aspectRatioScore > 0.7f) {
                score += 0.3f // Bonus for having faces when aspect ratio is good
            }
        }

        // For regions of extreme aspect ratio, prefer photos of similar extreme
        if (region.aspectRatio > 1.5f && photo.isLandscape) {
            score += 0.2f // Bonus for wide photos in wide regions
        } else if (region.aspectRatio < 0.7f && photo.isPortrait) {
            score += 0.2f // Bonus for tall photos in tall regions
        }

        // If region is large (determined by normalized area), prefer photos with faces
        val isLargeRegion = region.rect.width() * region.rect.height() > 0.3f
        if (isLargeRegion && photo.faces.isNotEmpty()) {
            score += 0.2f // Bonus for faces in large regions
        }

        return score.coerceIn(0f, 1f) // Ensure score is between 0 and 1
    }

    /**
     * Calculate how well two aspect ratios match
     * Returns a value between 0 (complete mismatch) and 1 (perfect match)
     */
    private fun calculateAspectRatioScore(photoAspect: Float, regionAspect: Float): Float {
        val ratio = if (photoAspect > regionAspect) {
            regionAspect / photoAspect
        } else {
            photoAspect / regionAspect
        }

        // Apply a non-linear scale to emphasize good matches
        // 1.0 = perfect match, 0.8 = good match, 0.6 = acceptable, < 0.5 = poor
        return when {
            ratio > 0.9f -> 1.0f
            ratio > 0.8f -> 0.9f
            ratio > 0.7f -> 0.8f
            ratio > 0.6f -> 0.7f
            ratio > 0.5f -> 0.6f
            ratio > 0.4f -> 0.4f
            ratio > 0.3f -> 0.2f
            else -> 0.0f
        }
    }

    private fun Matrix.setCropToRegion(
        bitmap: Bitmap,
        targetWidth: Float,
        targetHeight: Float,
        salientRegion: RectF
    ) {
        val sourceWidth = bitmap.width.toFloat()
        val sourceHeight = bitmap.height.toFloat()

        // Calculate the center of the salient region
        val salientCenterX = salientRegion.centerX()
        val salientCenterY = salientRegion.centerY()

        // Calculate source and target aspect ratios
        val sourceRatio = sourceWidth / sourceHeight
        val targetRatio = targetWidth / targetHeight

        // Determine scale factor to fit either width or height
        val scale: Float
        var offsetX = 0f
        var offsetY = 0f

        if (sourceRatio > targetRatio) {
            // Source is wider than target - scale to fit height
            scale = targetHeight / sourceHeight

            // Calculate width after scaling
            val scaledWidth = sourceWidth * scale

            // Calculate how much total width to crop
            val cropWidth = scaledWidth - targetWidth

            // This is the key fix: Calculate offset to properly center the salient region
            // Map the salient center from source to target coordinates
            val scaledSalientCenterX = salientCenterX * scale
            // Calculate ideal offset to center the salient region
            val idealOffset = (targetWidth / 2) - scaledSalientCenterX

            // Constrain offset to avoid going out of bounds
            offsetX = idealOffset.coerceIn(-cropWidth, 0f)
        } else {
            // Source is taller than target - scale to fit width
            scale = targetWidth / sourceWidth

            // Calculate height after scaling
            val scaledHeight = sourceHeight * scale

            // Calculate how much to crop from top/bottom
            val cropHeight = scaledHeight - targetHeight

            // Calculate offset to center the salient region (same logic as above)
            val scaledSalientCenterY = salientCenterY * scale
            val idealOffset = (targetHeight / 2) - scaledSalientCenterY

            // Constrain offset to keep within image bounds
            offsetY = idealOffset.coerceIn(-cropHeight, 0f)
        }

        // Apply transformations
        this.setScale(scale, scale)
        this.postTranslate(offsetX, offsetY)
    }

    /**
     * Direct method to analyze and crop a photo to fill the container
     * while maintaining important content (faces)
     */
    suspend fun createSmartCroppedPhoto(
        bitmap: Bitmap,
        containerWidth: Int,
        containerHeight: Int
    ): Bitmap = withContext(Dispatchers.Default) {
        try {
            // First analyze the photo to detect faces
            val photoAnalysis = analyzePhotos(listOf(bitmap)).firstOrNull()
                ?: return@withContext createCenterCroppedBitmap(bitmap, containerWidth, containerHeight)

            // If face detection succeeded, use it for smart cropping
            if (photoAnalysis.faceDetectionSucceeded && photoAnalysis.dominantFaceRegion != null) {
                Log.d(TAG, "Face detected, creating face-aware cropped photo")

                // Create a cropped bitmap using face information
                return@withContext createCropWithFaceAwareness(
                    bitmap,
                    containerWidth,
                    containerHeight,
                    photoAnalysis.dominantFaceRegion
                )
            } else {
                // Fall back to center crop
                Log.d(TAG, "No faces detected, using center crop")
                return@withContext createCenterCroppedBitmap(bitmap, containerWidth, containerHeight)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating smart cropped photo", e)
            return@withContext createCenterCroppedBitmap(bitmap, containerWidth, containerHeight)
        }
    }

    /**
     * Create a cropped bitmap considering face positions
     */
    fun createCropWithFaceAwareness(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        faceRegion: RectF
    ): Bitmap {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height

        // Calculate the target aspect ratio
        val targetRatio = targetWidth.toFloat() / targetHeight

        // Calculate source crop dimensions that maintain target aspect ratio
        val sourceCropWidth: Int
        val sourceCropHeight: Int

        if (targetRatio > sourceWidth.toFloat() / sourceHeight) {
            // Target is wider than source - fit to width
            sourceCropWidth = sourceWidth
            sourceCropHeight = (sourceCropWidth / targetRatio).toInt()
        } else {
            // Target is taller than source - fit to height
            sourceCropHeight = sourceHeight
            sourceCropWidth = (sourceCropHeight * targetRatio).toInt()
        }

        // Calculate crop origin to center on face region
        val faceCenterX = faceRegion.centerX()
        val faceCenterY = faceRegion.centerY()

        // Calculate origin to center face in crop
        var cropX = (faceCenterX - sourceCropWidth / 2).toInt()
        var cropY = (faceCenterY - sourceCropHeight / 2).toInt()

        // Adjust if crop goes outside image bounds
        if (cropX < 0) cropX = 0
        if (cropY < 0) cropY = 0
        if (cropX + sourceCropWidth > sourceWidth) cropX = sourceWidth - sourceCropWidth
        if (cropY + sourceCropHeight > sourceHeight) cropY = sourceHeight - sourceCropHeight

        // Use bitmap pool to get a bitmap of the right size and configuration
        val config = if (targetWidth * targetHeight > 800_000) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val resultBitmap = bitmapPool.getBitmap(targetWidth, targetHeight, config)

        // Create an intermediate cropped bitmap to apply the cropping
        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            cropX,
            cropY,
            sourceCropWidth.coerceAtMost(sourceWidth - cropX),
            sourceCropHeight.coerceAtMost(sourceHeight - cropY)
        )

        // Draw the scaled bitmap onto the pooled bitmap
        val canvas = Canvas(resultBitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        val scaleX = targetWidth.toFloat() / croppedBitmap.width
        val scaleY = targetHeight.toFloat() / croppedBitmap.height

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY)

        canvas.drawBitmap(croppedBitmap, matrix, paint)

        // Clean up the intermediate bitmap
        croppedBitmap.recycle()

        return resultBitmap
    }

    /**
     * Helper method to create bitmaps with high quality
     * - Always uses ARGB_8888 for best quality
     */
    private fun createOptimizedBitmap(width: Int, height: Int): Bitmap {
        // Always use ARGB_8888 for maximum quality
        Log.d(TAG, "Creating bitmap ${width}x${height} with ARGB_8888 for best quality")
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Release a bitmap back to the pool when no longer needed
     */
    fun releaseBitmap(bitmap: Bitmap?) {
        bitmapPool.releaseBitmap(bitmap)
    }

    /**
     * Clear all bitmaps from pool (for low memory situations)
     */
    fun clearBitmapPool() {
        bitmapPool.clearPool()
    }

    /**
     * Get bitmap pool statistics (for debugging)
     */
    fun getBitmapPoolStats(): String {
        return bitmapPool.getStats()
    }

    /**
     * Create a simple center cropped bitmap as fallback
     */
    private fun createCenterCroppedBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height

        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val targetRatio = targetWidth.toFloat() / targetHeight

        val sourceCropWidth: Int
        val sourceCropHeight: Int
        var cropX = 0
        var cropY = 0

        if (sourceRatio > targetRatio) {
            // Source is wider than target - crop sides
            sourceCropHeight = sourceHeight
            sourceCropWidth = (targetRatio * sourceHeight).toInt()
            cropX = (sourceWidth - sourceCropWidth) / 2
        } else {
            // Source is taller than target - crop top/bottom
            sourceCropWidth = sourceWidth
            sourceCropHeight = (sourceWidth / targetRatio).toInt()
            cropY = (sourceHeight - sourceCropHeight) / 2
        }

        // Use bitmap pool to get a bitmap
        val config = if (targetWidth * targetHeight > 800_000) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val resultBitmap = bitmapPool.getBitmap(targetWidth, targetHeight, config)

        // Create an intermediate cropped bitmap
        val croppedBitmap = Bitmap.createBitmap(
            bitmap,
            cropX,
            cropY,
            sourceCropWidth.coerceAtMost(sourceWidth - cropX),
            sourceCropHeight.coerceAtMost(sourceHeight - cropY)
        )

        // Draw the scaled bitmap onto the pooled bitmap
        val canvas = Canvas(resultBitmap)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        val scaleX = targetWidth.toFloat() / croppedBitmap.width
        val scaleY = targetHeight.toFloat() / croppedBitmap.height

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY)

        canvas.drawBitmap(croppedBitmap, matrix, paint)

        // Clean up the intermediate bitmap
        croppedBitmap.recycle()

        return resultBitmap
    }

    /**
     * Composite all photos into a final template
     */
    fun createFinalTemplate(
        photos: List<PhotoAnalysis>,
        regions: List<LayoutRegion>,
        assignments: Map<Int, Int>,
        containerWidth: Int,
        containerHeight: Int
    ): Bitmap {
        // Get a bitmap from the pool
        val resultBitmap = createOptimizedBitmap(containerWidth, containerHeight)
        val canvas = Canvas(resultBitmap)

        // Always fill with solid black first to avoid any partial rendering
        canvas.drawColor(Color.BLACK)

        val paint = Paint().apply {
            isAntiAlias = true
            color = BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        // Draw each photo in its assigned region
        assignments.forEach { (regionIndex, photoIndex) ->
            if (regionIndex < regions.size && photoIndex < photos.size) {
                val region = regions[regionIndex]
                val photo = photos[photoIndex]

                if (photo.bitmap.isRecycled) {
                    Log.e(TAG, "Skipping recycled bitmap at index $photoIndex")
                    return@forEach
                }

                // Paint a solid black background for this region first
                // to ensure we don't have transparency issues
                val bgPaint = Paint().apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                }
                canvas.drawRect(region.rect, bgPaint)

                // Calculate matrix for fitting the photo into the region
                val matrix = Matrix()
                val sourceWidth = photo.bitmap.width.toFloat()
                val sourceHeight = photo.bitmap.height.toFloat()
                val targetWidth = region.rect.width()
                val targetHeight = region.rect.height()

                // Standard center crop calculation
                val sourceRatio = sourceWidth / sourceHeight
                val targetRatio = targetWidth / targetHeight

                if (sourceRatio > targetRatio) {
                    // Source is wider than target - scale to fit height
                    val scale = targetHeight / sourceHeight
                    val scaledWidth = sourceWidth * scale
                    val left = (targetWidth - scaledWidth) / 2f

                    matrix.setScale(scale, scale)
                    matrix.postTranslate(region.rect.left + left, region.rect.top)
                } else {
                    // Source is taller than target - scale to fit width
                    val scale = targetWidth / sourceWidth
                    val scaledHeight = sourceHeight * scale
                    val top = (targetHeight - scaledHeight) / 2f

                    matrix.setScale(scale, scale)
                    matrix.postTranslate(region.rect.left, region.rect.top + top)
                }

                // Draw the photo in its region
                val photoPaint = Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                }

                canvas.save()
                canvas.clipRect(region.rect)
                canvas.drawBitmap(photo.bitmap, matrix, photoPaint)
                canvas.restore()
            }
        }

        // Draw borders between photos
        regions.forEach { region ->
            canvas.drawRect(region.rect, paint)
        }

        return resultBitmap
    }
}