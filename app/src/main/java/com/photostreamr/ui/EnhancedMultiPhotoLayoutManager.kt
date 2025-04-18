package com.photostreamr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.preference.PreferenceManager
import com.photostreamr.PhotoRepository
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import kotlin.random.Random

/**
 * Enhanced Multi-Photo Layout Manager that uses ML Kit face detection
 * for intelligent template creation
 */
@Singleton
class EnhancedMultiPhotoLayoutManager @Inject constructor(
    private val context: Context,
    private val photoManager: PhotoRepository,
    private val photoPreloader: PhotoPreloader,
    private val smartPhotoLayoutManager: SmartPhotoLayoutManager,
    private val smartTemplateHelper: SmartTemplateHelper
) {
    companion object {
        private const val TAG = "EnhancedMultiPhotoLayoutManager"

        // Layout types - directly defined here instead of referencing MultiPhotoLayoutManager
        const val LAYOUT_TYPE_2_VERTICAL = 0
        const val LAYOUT_TYPE_2_HORIZONTAL = 1
        const val LAYOUT_TYPE_3_MAIN_LEFT = 2
        const val LAYOUT_TYPE_3_MAIN_RIGHT = 3
        const val LAYOUT_TYPE_4_GRID = 4
        const val LAYOUT_TYPE_DYNAMIC = 5
        const val LAYOUT_TYPE_DYNAMIC_COLLAGE = 6
        const val LAYOUT_TYPE_DYNAMIC_MASONRY = 7
        const val LAYOUT_TYPE_3_SMART = 8

        // Minimum number of photos for templates
        const val MIN_PHOTOS_FOR_TEMPLATE = 2
        const val MIN_PHOTOS_DYNAMIC = 3

        // Border settings
        private const val BORDER_WIDTH = 8f
        private const val BORDER_COLOR = Color.WHITE

        // Default transition duration in ms if preference not found
        private const val DEFAULT_TRANSITION_DURATION_MS = 2000L
    }

    // Job for coroutine management
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    // Callback interface for when templates are ready (same as MultiPhotoLayoutManager)
    interface TemplateReadyCallback {
        fun onTemplateReady(result: Drawable, layoutType: Int)
        fun onTemplateError(error: String)
    }

    /**
     * Get the transition duration from preferences in milliseconds
     */
    private fun getTransitionDurationMs(): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Get the value (1-5) from preferences and convert to milliseconds
        val durationSetting = prefs.getInt("transition_duration", 2)
        return durationSetting * 1000L
    }

    /**
     * Create a template based on available photos and container dimensions
     * ENHANCED: Better handling of template type selection
     */
    fun createTemplate(
        containerWidth: Int,
        containerHeight: Int,
        currentPhotoIndex: Int,
        layoutType: Int,
        callback: TemplateReadyCallback
    ) {
        // Validate dimensions first
        if (containerWidth <= 0 || containerHeight <= 0) {
            callback.onTemplateError("Invalid container dimensions: ${containerWidth}x${containerHeight}")
            return
        }

        managerScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()

                // Check if we have enough photos for a template
                if (photoCount < MIN_PHOTOS_FOR_TEMPLATE) {
                    Log.d(TAG, "Not enough photos for template, using single photo")
                    createSinglePhotoTemplate(containerWidth, containerHeight, currentPhotoIndex, callback)
                    return@launch
                }

                // Determine indices of photos to include
                val requiredCount = getRequiredPhotoCount(layoutType)
                val photoIndices = getPhotoIndices(currentPhotoIndex, photoCount, requiredCount)

                // Load all photos
                val photoBitmaps = withContext(Dispatchers.IO) {
                    loadPhotos(photoIndices)
                }

                if (photoBitmaps.isEmpty()) {
                    callback.onTemplateError("Failed to load photos")
                    return@launch
                }

                // If we couldn't load enough photos, fall back to single photo
                if (photoBitmaps.size < MIN_PHOTOS_FOR_TEMPLATE) {
                    Log.d(TAG, "Not enough photos loaded, using single photo")
                    if (photoBitmaps.isNotEmpty()) {
                        val singlePhotoDrawable = BitmapDrawable(context.resources, photoBitmaps[0])
                        callback.onTemplateReady(singlePhotoDrawable, -1)
                    } else {
                        callback.onTemplateError("Failed to load any photos")
                    }
                    return@launch
                }

                // Determine best template type if DYNAMIC was specified
                val finalLayoutType = if (layoutType == LAYOUT_TYPE_DYNAMIC) {
                    withContext(Dispatchers.Default) {
                        smartTemplateHelper.determineBestTemplate(
                            photoBitmaps,
                            containerWidth,
                            containerHeight,
                            layoutType  // Pass requested type for context
                        )
                    }
                } else {
                    layoutType
                }

                // Check if this layout is compatible with the current orientation
                val isCompatible = smartTemplateHelper.isTemplateCompatibleWithOrientation(
                    finalLayoutType,
                    containerWidth,
                    containerHeight
                )

                // If not compatible, fall back to a more suitable layout
                val effectiveLayoutType = if (!isCompatible) {
                    Log.d(TAG, "Template type $finalLayoutType not compatible with orientation, using fallback")
                    if (containerWidth > containerHeight) {
                        LAYOUT_TYPE_2_HORIZONTAL
                    } else {
                        LAYOUT_TYPE_2_VERTICAL
                    }
                } else {
                    finalLayoutType
                }

                // Create template using SmartTemplateHelper
                val templateDrawable = smartTemplateHelper.createSmartTemplate(
                    photoBitmaps,
                    containerWidth,
                    containerHeight,
                    effectiveLayoutType
                )

                if (templateDrawable != null) {
                    callback.onTemplateReady(templateDrawable, effectiveLayoutType)
                } else {
                    callback.onTemplateError("Failed to create template")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error creating template", e)
                callback.onTemplateError("Error: ${e.message}")
            }
        }
    }

    /**
     * Create a template with a single photo and ambient effects
     */
    private suspend fun createSinglePhotoTemplate(
        containerWidth: Int,
        containerHeight: Int,
        photoIndex: Int,
        callback: TemplateReadyCallback
    ) {
        try {
            val photoUrl = photoManager.getPhotoUrl(photoIndex) ?: run {
                callback.onTemplateError("Failed to get photo URL")
                return
            }

            // Check if we need to use cached version for Google Photos
            val isGooglePhotosUri = photoUrl.contains("com.google.android.apps.photos") ||
                    photoUrl.contains("googleusercontent.com")

            val urlToUse = if (isGooglePhotosUri) {
                photoManager.persistentPhotoCache?.getCachedPhotoUri(photoUrl) ?: run {
                    callback.onTemplateError("Google Photos URI not cached")
                    return
                }
            } else {
                photoUrl
            }

            // Load the photo
            val photoBitmap = withContext(Dispatchers.IO) {
                loadSinglePhoto(urlToUse)
            }

            if (photoBitmap == null) {
                callback.onTemplateError("Failed to load photo")
                return
            }

            // Create ambient template
            val templateDrawable = smartTemplateHelper.createSmartTemplate(
                listOf(photoBitmap),
                containerWidth,
                containerHeight,
                -1 // Special value to indicate single photo
            )

            if (templateDrawable != null) {
                callback.onTemplateReady(templateDrawable, -1)
            } else {
                callback.onTemplateError("Failed to create single photo template")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating single photo template", e)
            callback.onTemplateError("Error: ${e.message}")
        }
    }

    /**
     * Get the number of photos required for a specific layout
     */
    private fun getRequiredPhotoCount(layoutType: Int): Int {
        return when (layoutType) {
            LAYOUT_TYPE_2_VERTICAL, LAYOUT_TYPE_2_HORIZONTAL -> 2
            LAYOUT_TYPE_3_MAIN_LEFT, LAYOUT_TYPE_3_MAIN_RIGHT, LAYOUT_TYPE_3_SMART -> 3
            LAYOUT_TYPE_4_GRID -> 4
            LAYOUT_TYPE_DYNAMIC -> 3
            LAYOUT_TYPE_DYNAMIC_COLLAGE -> 12  // Minimum 12 photos for collage
            LAYOUT_TYPE_DYNAMIC_MASONRY -> 3
            else -> 2
        }
    }

    /**
     * Get unique photo indices to use in the template
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
        val random = Random.Default

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

                    // Check if preloaded
                    val preloadedResource = photoPreloader.getPreloadedResource(urlToUse)
                    if (preloadedResource != null && preloadedResource is BitmapDrawable) {
                        try {
                            // Create a copy of the bitmap to avoid recycling issues
                            val source = preloadedResource.bitmap
                            if (!source.isRecycled) {
                                val copy = source.copy(source.config, true)
                                return@async copy
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using preloaded resource", e)
                        }
                    }

                    // Load with Glide if not preloaded
                    loadSinglePhoto(urlToUse)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading photo", e)
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

    /**
     * Load a single photo and return as Bitmap
     */
    private suspend fun loadSinglePhoto(url: String): Bitmap? = suspendCancellableCoroutine { continuation ->
        try {
            com.bumptech.glide.Glide.with(context.applicationContext)
                .asBitmap()
                .load(url)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .listener(object : com.bumptech.glide.request.RequestListener<Bitmap> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<Bitmap>,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.e(TAG, "Failed to load bitmap: $url", e)
                        continuation.resume(null) {
                            Log.w(TAG, "Continuation was cancelled during load failure", it)
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Bitmap,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<Bitmap>,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        // Make a copy to avoid recycling issues
                        try {
                            val copy = resource.copy(resource.config, true)
                            continuation.resume(copy) {
                                Log.w(TAG, "Continuation was cancelled after resource ready", it)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error copying bitmap", e)
                            continuation.resume(null) {
                                Log.w(TAG, "Continuation was cancelled during copy error", it)
                            }
                        }
                        return true
                    }
                })
                .submit()

            continuation.invokeOnCancellation {
                Log.d(TAG, "Loading cancelled for URL: $url")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading bitmap with Glide", e)
            continuation.resume(null) {
                Log.w(TAG, "Continuation was cancelled during exception", it)
            }
        }
    }

    /**
     * Apply transition to the template with the correct duration from settings
     */
    fun applyTemplateTransition(
        views: PhotoTransitionEffects.TransitionViews,
        drawable: Drawable,
        nextIndex: Int,
        callback: PhotoTransitionEffects.TransitionCompletionCallback
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val transitionEffect = prefs.getString("transition_effect", "fade") ?: "fade"
        val transitionDuration = getTransitionDurationMs()

        Log.d(TAG, "Applying template transition with duration: $transitionDuration ms")

        val transitionEffects = PhotoTransitionEffects(context)
        transitionEffects.performTransition(
            views = views,
            resource = drawable,
            nextIndex = nextIndex,
            transitionEffect = transitionEffect,
            transitionDuration = transitionDuration,
            callback = callback
        )
    }

    /**
     * Clear any cached resources
     */
    fun cleanup() {
        managerJob.cancel()
    }
}