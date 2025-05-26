package com.photostreamr.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.photostreamr.PhotoRepository
import com.photostreamr.data.PhotoCache
import com.photostreamr.models.LoadingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import android.graphics.drawable.Drawable
import kotlin.random.Random
import kotlinx.coroutines.delay
import com.photostreamr.R
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.net.Uri
import androidx.preference.PreferenceManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.Animator
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.nativead.NativeAd
import com.photostreamr.music.SpotifyManager
import com.photostreamr.music.SpotifyPreferences
import android.os.Handler
import com.photostreamr.ads.AdManager
import com.photostreamr.version.AppVersionManager
import android.animation.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.animation.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import coil.imageLoader
import coil.load
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.photostreamr.photos.CoilImageLoadStrategy
import com.photostreamr.photos.ImageLoadStrategy


@Singleton
class PhotoDisplayManager @Inject constructor(
    private val photoManager: PhotoRepository,
    private val photoCache: PhotoCache,
    private val context: Context,
    private val spotifyManager: SpotifyManager,
    private val spotifyPreferences: SpotifyPreferences,
    private val adManager: AdManager,
    private val appVersionManager: AppVersionManager,
    private val photoResizeManager: PhotoResizeManager,
    private val photoPreloader: PhotoPreloader,
    private val enhancedMultiPhotoLayoutManager: EnhancedMultiPhotoLayoutManager,
    private val bitmapMemoryManager: BitmapMemoryManager,
    private val smartTemplateHelper: SmartTemplateHelper,
    private val smartPhotoLayoutManager: SmartPhotoLayoutManager,
    private val diskCacheManager: DiskCacheManager,
    private val imageLoadStrategy: CoilImageLoadStrategy
) : PhotoTransitionEffects.TransitionCompletionCallback,
    EnhancedMultiPhotoLayoutManager.TemplateReadyCallback {

    private val transitionEffects = PhotoTransitionEffects(context)

    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    private val _photoLoadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)

    private val _cacheStatusMessage = MutableStateFlow<String?>(null)

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val random = Random.Default

    private var lastLoadedSource: String? = null
    private var lastPhotoUrl: String? = null

    private var isScreensaverActive = false
    private var wasDisplayingPhotos = false

    private var hasLoadedPhotos = false

    private var isStartingDisplay = false

    private var currentNativeAd: NativeAd? = null
    private var isShowingNativeAd = false
    private var nativeAdDuration = 10000L // Display native ads for 10 seconds
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryRunnable: Runnable? = null
    private var lastPhotoChangeTime = System.currentTimeMillis()
    private val PHOTO_STUCK_THRESHOLD = 3 * 60 * 1000L // 3 minutes

    private var lastDisplayStartTime = 0L
    private val MIN_DISPLAY_INTERVAL = 1500L // 1.5 seconds minimum between display starts

    data class Views(
        val primaryView: ImageView,
        val overlayView: ImageView,
        val locationView: TextView?,
        val loadingIndicator: View?,
        val loadingMessage: TextView?,
        val container: View,
        val overlayMessageContainer: View?,
        val overlayMessageText: TextView?,
        val backgroundLoadingIndicator: View?,
        val topLetterboxView: ImageView?,
        val bottomLetterboxView: ImageView?,
        val leftLetterboxView: ImageView?,
        val rightLetterboxView: ImageView?
    )

    private var views: Views? = null
    private var lifecycleScope: LifecycleCoroutineScope? = null
    private var currentPhotoIndex = 0
    private var isTransitioning = false
    private var displayJob: Job? = null

    // Settings
    private var transitionDuration: Long = 5000
    private var showLocation: Boolean = false
    private var isRandomOrder: Boolean = false

    companion object {
        private const val TAG = "PhotoDisplayManager"
        const val PREF_KEY_INTERVAL = "photo_interval"
        const val DEFAULT_INTERVAL_SECONDS = 5
        private const val MILLIS_PER_SECOND = 1000L
    }

    init {
        // Preload immediately on initialization
        preloadDefaultPhoto()

        // Add cache status observation
        managerScope.launch {
            photoCache.cacheStatus.collect { status ->
                when (status) {
                    PhotoCache.CacheStatus.ERROR -> {
                        Log.e(TAG, "Cache error detected")
                        handleCacheError()
                    }
                    PhotoCache.CacheStatus.LOW_SPACE -> {
                        Log.w(TAG, "Cache space low")
                        photoCache.performSmartCleanup()
                    }
                    else -> {
                        _cacheStatusMessage.value = status.message
                    }
                }
            }
        }
    }

    override fun onTemplateReady(result: Drawable, layoutType: Int) {
        val views = this.views ?: return
        val currentScope = lifecycleScope ?: return

        if (isTransitioning) {
            Log.d(TAG, "Skipping template display - transition in progress")
            return  // Don't interrupt existing transitions
        }

        isTransitioning = true

        currentScope.launch {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val transitionEffect = prefs.getString("transition_effect", "fade") ?: "fade"

                // Get the current transition duration setting (1-5 seconds)
                val transitionDurationSetting = prefs.getInt("transition_duration", 2)
                val effectiveTransitionDuration = transitionDurationSetting * 1000L

                Log.d(TAG, "Applying template transition with duration: $effectiveTransitionDuration ms")

                resetViewProperties(views)

                Log.d(TAG, "Displaying multi-photo template with layout type: $layoutType")

                withContext(Dispatchers.Main) {
                    views.overlayView.setImageDrawable(result)
                    views.overlayView.visibility = View.VISIBLE

                    // Use the transition effects system for consistency
                    val transitionViews = PhotoTransitionEffects.TransitionViews(
                        primaryView = views.primaryView,
                        overlayView = views.overlayView,
                        container = views.container,
                        topLetterboxView = views.topLetterboxView,
                        bottomLetterboxView = views.bottomLetterboxView
                    )

                    transitionEffects.performTransition(
                        views = transitionViews,
                        resource = result,
                        nextIndex = currentPhotoIndex,
                        transitionEffect = transitionEffect,
                        transitionDuration = effectiveTransitionDuration,
                        callback = this@PhotoDisplayManager
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying template", e)
                isTransitioning = false // Make sure to reset flag even on error
                loadAndDisplayPhoto(false)  // Fall back to regular photo display
            }
        }
    }

    override fun onTemplateError(error: String) {
        Log.e(TAG, "Template creation error: $error")
        isTransitioning = false
        loadAndDisplayPhoto(false)  // Fall back to regular photo display
    }

    override fun onTransitionCompleted(resource: Drawable, nextIndex: Int) {
        val currentViews = views ?: return

        // Complete the current transition
        completeTransition(currentViews, resource, nextIndex)

        // CRITICAL: Force the photo index to advance for multi-template mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val displayMode = prefs.getString(PhotoResizeManager.PREF_KEY_PHOTO_SCALE,
            PhotoResizeManager.DEFAULT_DISPLAY_MODE) ?:
        PhotoResizeManager.DEFAULT_DISPLAY_MODE

        if (displayMode == PhotoResizeManager.DISPLAY_MODE_MULTI_TEMPLATE) {
            // Launch a coroutine to safely call getNextPhotoIndex
            lifecycleScope?.launch {
                try {
                    // Get the next photo index for the next template
                    val nextPhotoIndex = getNextPhotoIndex(currentPhotoIndex, photoManager.getPhotoCount())

                    // Update the current index
                    currentPhotoIndex = nextPhotoIndex
                    Log.d(TAG, "Advanced to next photo index for templates: $currentPhotoIndex")
                } catch (e: Exception) {
                    Log.e(TAG, "Error advancing to next photo index", e)
                }
            }
        }
    }

    fun isScreensaverActive(): Boolean = isScreensaverActive

    private fun loadPhotosInBackground() {
        lifecycleScope?.launch(Dispatchers.IO) {
            try {
                photoManager.loadPhotos()
                val photoCount = photoManager.getPhotoCount()

                if (photoCount > 0) {
                    photoCache.savePhotoState(true, photoManager.getPhotoUrl(0))
                    withContext(Dispatchers.Main) {
                        startPhotoDisplay()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos in background", e)
            }
        }
    }

    private fun persistPhotoState(source: String, url: String) {
        lastLoadedSource = source
        lastPhotoUrl = url
        photoCache.savePhotoState(true, url)
    }

    private fun handleCacheError() {
        lifecycleScope?.launch {
            try {
                // Try to recover from cache error
                photoCache.performSmartCleanup()
                // If we have photos loaded, continue displaying
                if (photoManager.getPhotoCount() > 0) {
                    startPhotoDisplay()
                } else {
                    showDefaultPhoto()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling cache error", e)
                showDefaultPhoto()
            }
        }
    }

    private fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    fun initialize(views: Views, scope: LifecycleCoroutineScope) {
        Log.d(TAG, "Initializing PhotoDisplayManager")
        this.views = views
        this.lifecycleScope = scope

        // Initialize resize manager if the views are provided
        if (views.topLetterboxView != null && views.bottomLetterboxView != null &&
            views.leftLetterboxView != null && views.rightLetterboxView != null) {
            photoResizeManager.initialize(
                views.primaryView,
                views.overlayView,
                views.topLetterboxView,
                views.bottomLetterboxView,
                views.leftLetterboxView,     // New
                views.rightLetterboxView,    // New
                views.container
            )

            // Apply current display mode (fill or fit)
            photoResizeManager.applyDisplayMode()
        }

        // Read transition duration from preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val transitionDurationSetting = prefs.getInt("transition_duration", 2)
        this.transitionDuration = transitionDurationSetting * 1000L
        Log.d(TAG, "Initialized transition duration to: ${this.transitionDuration}ms")

        // Store scope locally to avoid smart cast issue
        val currentScope = scope

        // Check for existing state
        currentScope.launch {
            try {
                val currentSources = PreferenceManager.getDefaultSharedPreferences(context)
                    .getStringSet("photo_source_selection", null)

                if (currentSources?.contains("google_photos") == true) {
                    loadPhotosInBackground()
                } else {
                    updatePhotoSources()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing photo state", e)
            }
        }
    }

    private fun showErrorMessage(error: String) {
        views?.let { views ->
            if (!isMainThread()) {
                views.container.post { showErrorMessage(error) }
                return
            }

            views.overlayMessageContainer?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                views.overlayMessageText?.text = context.getString(R.string.error_message, error)
                Log.d(TAG, "Error message displayed: $error")
            } ?: Log.e(TAG, "Message container is null")
        }
    }

    private suspend fun getNextPhotoIndex(currentIndex: Int, totalPhotos: Int): Int =
        withContext(Dispatchers.Default) {
            when {
                !isRandomOrder || totalPhotos <= 1 -> (currentIndex + 1) % totalPhotos
                totalPhotos == 2 -> (currentIndex + 1) % 2
                else -> {
                    var nextIndex: Int
                    do {
                        nextIndex = Random.nextInt(totalPhotos)
                    } while (nextIndex == currentIndex)
                    nextIndex
                }
            }
        }

    fun loadAndDisplayPhoto(fromCache: Boolean = false) {
        val startTime = System.currentTimeMillis()

        // IMPROVED: Better transition flag handling
        if (isTransitioning) {
            val lastPhotoChangeTimeDiff = System.currentTimeMillis() - lastPhotoChangeTime

            // More aggressive transition reset
            if (lastPhotoChangeTimeDiff > 5000) { // 5 seconds
                Log.w(TAG, "Transition flag has been stuck for over 5 seconds, forcibly resetting it")
                isTransitioning = false
            } else {
                Log.d(TAG, "Skipping photo load - transition in progress for ${lastPhotoChangeTimeDiff}ms")
                return
            }
        }

        // Always update the last photo change time on attempt
        lastPhotoChangeTime = System.currentTimeMillis()

        // Check if music should be playing
        if (isScreensaverActive && spotifyPreferences.isEnabled() &&
            spotifyManager.playbackState.value is SpotifyManager.PlaybackState.Idle) {
            spotifyManager.resume()
        }

        // Use the simplified ad check
        if (adManager.shouldShowNativeAd()) {
            Log.d(TAG, "Time to show a native ad!")
            displayNativeAd()
            return
        }

        // Store lifecycleScope in a local variable
        val currentScope = lifecycleScope ?: return
        val views = this.views ?: return

        // Check if we should show a single photo for memory cleanup or variety
        val shouldShowSinglePhoto = bitmapMemoryManager.shouldShowSinglePhoto()

        // Get available photos and filter out Google Photos URIs that aren't cached
        currentScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                if (photoCount == 0) {
                    Log.d(TAG, "No photos available, showing default photo with message")
                    showNoPhotosMessage()
                    return@launch
                }

                // Check which display mode we're in
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val displayMode = prefs.getString(PhotoResizeManager.PREF_KEY_PHOTO_SCALE,
                    PhotoResizeManager.DEFAULT_DISPLAY_MODE) ?:
                PhotoResizeManager.DEFAULT_DISPLAY_MODE

                Log.d(TAG, "Display mode: $displayMode (shouldShowSinglePhoto: $shouldShowSinglePhoto)")

                // If we should show a single photo due to memory pressure or visual variety,
                // override the display mode to use single photo display
                if (shouldShowSinglePhoto && displayMode == PhotoResizeManager.DISPLAY_MODE_MULTI_TEMPLATE) {
                    Log.d(TAG, "Memory manager requested single photo display - overriding template mode")
                    handleSinglePhotoMode(views, currentScope)
                    return@launch
                }

                // Handle based on display mode
                when (displayMode) {
                    PhotoResizeManager.DISPLAY_MODE_MULTI_TEMPLATE -> {
                        handleMultiTemplateMode(views, currentScope)
                    }
                    PhotoResizeManager.DISPLAY_MODE_SMART_FILL -> {
                        handleSmartFillMode(views, currentScope)
                    }
                    else -> {
                        handleSinglePhotoMode(views, currentScope)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finding valid photo", e)
                isTransitioning = false // Reset flag on error
                showDefaultPhoto()
            }
        }
    }

    // New helper method for handling multi-template mode
    private fun handleMultiTemplateMode(views: Views, currentScope: CoroutineScope) {
        val containerWidth = views.container.width
        val containerHeight = views.container.height

        if (containerWidth <= 0 || containerHeight <= 0) {
            Log.e(TAG, "Container has invalid dimensions: ${containerWidth}x${containerHeight}")

            // Add delay and retry once for container measurements
            currentScope.launch {
                delay(100)
                val updatedWidth = views.container.width
                val updatedHeight = views.container.height

                if (updatedWidth > 0 && updatedHeight > 0) {
                    Log.d(TAG, "Container dimensions valid after delay: ${updatedWidth}x${updatedHeight}")
                    createAndDisplayTemplate(views, updatedWidth, updatedHeight)
                } else {
                    Log.e(TAG, "Container still has invalid dimensions after delay")
                    showErrorMessage("Layout error - please restart the app")
                }
            }
            return
        }

        // Container dimensions are valid, proceed with template creation
        createAndDisplayTemplate(views, containerWidth, containerHeight)
    }

    // New helper method for handling smart fill mode (ML-based cropping)
    private fun handleSmartFillMode(views: Views, currentScope: CoroutineScope) {
        Log.d(TAG, "Using smart fill mode with ML-based cropping")

        // Start preloading for upcoming photos
        photoPreloader.startPreloading(currentPhotoIndex, isRandomOrder)

        currentScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                // Find a valid photo to display
                var foundValidPhoto = false
                var attemptsCount = 0
                val maxAttempts = photoCount * 2 // Prevent infinite loops

                while (!foundValidPhoto && attemptsCount < maxAttempts) {
                    attemptsCount++
                    val nextIndex = getNextPhotoIndex(currentPhotoIndex, photoCount)
                    val nextUrl = photoManager.getPhotoUrl(nextIndex) ?: continue

                    // Check if this is a Google Photos URI
                    val isGooglePhotosUri = nextUrl.contains("com.google.android.apps.photos") ||
                            nextUrl.contains("googleusercontent.com")

                    if (isGooglePhotosUri) {
                        // For Google Photos URIs, only use if we have a cached version
                        val cachedUri = photoManager.persistentPhotoCache?.getCachedPhotoUri(nextUrl)
                        if (cachedUri != null) {
                            // We have a cached version, use smart crop with ML
                            displaySmartCroppedPhoto(nextIndex, cachedUri, true)
                            foundValidPhoto = true
                        } else {
                            // No cached version, skip to next photo
                            Log.d(TAG, "Skipping uncached Google Photos URI: $nextUrl")
                            currentPhotoIndex = nextIndex
                        }
                    } else if (nextUrl.startsWith("content://")) {
                        // For local content URIs, prepare them properly
                        photoPreloader.prepareContentUri(nextUrl)
                        displaySmartCroppedPhoto(nextIndex, nextUrl, false)
                        foundValidPhoto = true
                    } else {
                        // Regular URI, display with smart crop
                        displaySmartCroppedPhoto(nextIndex, nextUrl, false)
                        foundValidPhoto = true
                    }
                }

                // If no valid photo was found after checking all photos
                if (!foundValidPhoto) {
                    Log.d(TAG, "No valid cached photos available, showing default photo")
                    showNoPhotosMessage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in smart fill mode", e)
                showDefaultPhoto()
            }
        }
    }

    // Helper method for displaying a photo with fit scalling and with smart ML-based cropping
    private fun displaySmartCroppedPhoto(photoIndex: Int, uri: String, isCached: Boolean) {
        val views = this.views ?: return
        val currentScope = lifecycleScope ?: return

        isTransitioning = true

        // Handle local content URI resources before starting transition
        if (uri.startsWith("content://") && Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            // Release previous URI resources
            val oldUri = photoManager.getPhotoUrl(currentPhotoIndex)
            if (oldUri != null && oldUri.startsWith("content://")) {
                photoPreloader.releaseContentUri(oldUri)
            }
        }

        currentScope.launch {
            try {
                // If this is a local content URI, prepare it
                if (uri.startsWith("content://")) {
                    photoPreloader.prepareContentUri(uri)
                }

                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val transitionEffect = prefs.getString("transition_effect", "fade") ?: "fade"
                val startTime = System.currentTimeMillis()
                val containerWidth = views.container.width
                val containerHeight = views.container.height

                resetViewProperties(views)

                Log.d(TAG, "Displaying smart cropped photo $photoIndex: $uri" + (if(isCached) " (cached)" else ""))

                withContext(Dispatchers.Main) {
                    // Load bitmap with Coil
                    try {
                        // Force CENTER_CROP scale type for smart fill mode
                        views.overlayView.scaleType = ImageView.ScaleType.CENTER_CROP
                        views.primaryView.scaleType = ImageView.ScaleType.CENTER_CROP

                        // Load the bitmap directly for processing
                        val bitmap = imageLoadStrategy.loadBitmap(uri)

                        if (bitmap != null) {
                            // Register local URI bitmaps with the memory manager
                            if (uri.startsWith("content://")) {
                                bitmapMemoryManager.registerActiveBitmap("localUri:${uri.hashCode()}", bitmap)
                                Log.d(TAG, "Registered local URI bitmap for tracking: $uri")
                            }

                            // Process with our direct smart cropping method
                            val smartCroppedBitmap = smartPhotoLayoutManager.createSmartCroppedPhoto(
                                bitmap,
                                containerWidth,
                                containerHeight
                            )

                            // Create drawable from the smart cropped bitmap
                            val drawable = BitmapDrawable(context.resources, smartCroppedBitmap)

                            // Apply the transition effect directly
                            views.overlayView.setImageDrawable(drawable)
                            views.overlayView.visibility = View.VISIBLE

                            // Use PhotoTransitionEffects directly
                            val transitionViews = PhotoTransitionEffects.TransitionViews(
                                primaryView = views.primaryView,
                                overlayView = views.overlayView,
                                container = views.container,
                                topLetterboxView = views.topLetterboxView,
                                bottomLetterboxView = views.bottomLetterboxView
                            )

                            transitionEffects.performTransition(
                                views = transitionViews,
                                resource = drawable,
                                nextIndex = photoIndex,
                                transitionEffect = transitionEffect,
                                transitionDuration = transitionDuration,
                                callback = this@PhotoDisplayManager
                            )
                        } else {
                            // If bitmap loading failed
                            Log.e(TAG, "Failed to load bitmap for smart crop: $uri")
                            isTransitioning = false

                            // Skip to next photo
                            currentPhotoIndex = photoIndex
                            loadAndDisplayPhoto(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing with ML Kit", e)
                        isTransitioning = false

                        // Skip to next photo
                        currentPhotoIndex = photoIndex
                        loadAndDisplayPhoto(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying smart cropped photo", e)
                isTransitioning = false
                showDefaultPhoto()
            }
        }
    }

    // New helper method for handling single photo mode (fill or fit)
    private fun handleSinglePhotoMode(views: Views, currentScope: CoroutineScope) {
        // Find a valid photo to display
        currentScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                var foundValidPhoto = false
                var attemptsCount = 0
                val maxAttempts = photoCount * 2 // Prevent infinite loops

                // Start preloading for upcoming photos
                photoPreloader.startPreloading(currentPhotoIndex, isRandomOrder)

                while (!foundValidPhoto && attemptsCount < maxAttempts) {
                    attemptsCount++
                    val nextIndex = getNextPhotoIndex(currentPhotoIndex, photoCount)
                    val nextUrl = photoManager.getPhotoUrl(nextIndex) ?: continue

                    // Check if this is a Google Photos URI
                    val isGooglePhotosUri = nextUrl.contains("com.google.android.apps.photos") ||
                            nextUrl.contains("googleusercontent.com")

                    if (isGooglePhotosUri) {
                        // For Google Photos URIs, only use if we have a cached version
                        val cachedUri = photoManager.persistentPhotoCache?.getCachedPhotoUri(nextUrl)
                        if (cachedUri != null) {
                            // We have a cached version, use it
                            displayPhoto(nextIndex, cachedUri, true)
                            foundValidPhoto = true
                        } else {
                            // No cached version, skip to next photo
                            Log.d(TAG, "Skipping uncached Google Photos URI: $nextUrl")
                            currentPhotoIndex = nextIndex
                        }
                    } else if (nextUrl.startsWith("content://")) {
                        // For local content URIs, make sure we prepare them properly
                        photoPreloader.prepareContentUri(nextUrl)
                        displayPhoto(nextIndex, nextUrl, false)
                        foundValidPhoto = true
                    } else {
                        // Regular URI, display directly
                        displayPhoto(nextIndex, nextUrl, false)
                        foundValidPhoto = true
                    }
                }

                // If no valid photo was found after checking all photos
                if (!foundValidPhoto) {
                    Log.d(TAG, "No valid cached photos available, showing default photo")
                    showNoPhotosMessage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in single photo mode", e)
                showDefaultPhoto()
            }
        }
    }


    private fun createAndDisplayTemplate(views: Views, containerWidth: Int, containerHeight: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val templateTypeStr = prefs.getString(PhotoResizeManager.TEMPLATE_TYPE_KEY,
            PhotoResizeManager.TEMPLATE_TYPE_DEFAULT.toString())

        // Map string values to template types
        val templateType = when (templateTypeStr) {
            "0" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL
            "1" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL
            // "2" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_LEFT
            // "3" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_MAIN_RIGHT
            "8", "3_smart" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_SMART
            "4" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID
            //"ghome" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_GHOME
            "collage" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE
            "masonry" -> EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY
            "random" -> -1  // Special value for random
            "2_smart" -> {  // Smart 2-photo template that adapts to orientation
                if (containerWidth > containerHeight) {
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL
                } else {
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL
                }
            }
            else -> templateTypeStr?.toIntOrNull() ?: PhotoResizeManager.TEMPLATE_TYPE_DEFAULT
        }

        // Handle random template type selection
        val finalTemplateType = if (templateType == -1) {
            // User selected random templates, pick one at random
            val isLandscape = containerWidth > containerHeight

            // Filter templates based on orientation to avoid inappropriate layouts
            val availableTemplateTypes = if (isLandscape) {
                // In landscape mode, exclude vertical layout (stacked photos)
                listOf(
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_SMART,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE,
                    //EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_GHOME,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY
                )
            } else {
                // In portrait mode, exclude horizontal layout (side by side photos)
                listOf(
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_SMART,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY
                )
            }
            availableTemplateTypes[Random.Default.nextInt(availableTemplateTypes.size)]
        } else {
            templateType
        }

        Log.d(TAG, "Creating template with type: $finalTemplateType (from setting: $templateTypeStr)")

        // Start preloading for upcoming photos
        photoPreloader.startPreloading(currentPhotoIndex, isRandomOrder)

        // Add safety check for available photos
        lifecycleScope?.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                val minPhotosNeeded = when (finalTemplateType) {
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_VERTICAL,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_2_HORIZONTAL -> 2
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_3_SMART -> 3
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_4_GRID -> 4
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_GHOME,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_COLLAGE,
                    EnhancedMultiPhotoLayoutManager.LAYOUT_TYPE_DYNAMIC_MASONRY ->
                        EnhancedMultiPhotoLayoutManager.MIN_PHOTOS_DYNAMIC
                    else -> 2
                }

                if (photoCount < minPhotosNeeded) {
                    Log.w(TAG, "Not enough photos for selected template type. Needed: $minPhotosNeeded, Available: $photoCount")
                    // Fall back to single photo display
                    loadAndDisplayPhoto(false)
                    return@launch
                }

                enhancedMultiPhotoLayoutManager.createTemplate(
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    currentPhotoIndex = currentPhotoIndex,
                    layoutType = finalTemplateType,
                    callback = this@PhotoDisplayManager
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing template display", e)
                loadAndDisplayPhoto(false) // Fall back to regular photo display
            }
        }
    }

    private fun displayPhoto(photoIndex: Int, uri: String, isCached: Boolean) {
        val views = this.views ?: return
        val currentScope = lifecycleScope ?: return

        isTransitioning = true

        // Handle local content URI resources before starting transition
        if (uri.startsWith("content://") && Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            // Release previous URI resources
            val oldUri = photoManager.getPhotoUrl(currentPhotoIndex)
            if (oldUri != null && oldUri.startsWith("content://")) {
                photoPreloader.releaseContentUri(oldUri)
            }
        }

        currentScope.launch {
            try {
                // If this is a local content URI, prepare it
                if (uri.startsWith("content://")) {
                    photoPreloader.prepareContentUri(uri)
                }

                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val transitionEffect = prefs.getString("transition_effect", "fade") ?: "fade"
                val startTime = System.currentTimeMillis()

                resetViewProperties(views)

                Log.d(TAG, "Displaying photo $photoIndex: $uri" + (if(isCached) " (cached)" else ""))

                withContext(Dispatchers.Main) {
                    // Get display mode
                    val displayMode = prefs.getString(PhotoResizeManager.PREF_KEY_PHOTO_SCALE,
                        PhotoResizeManager.DEFAULT_DISPLAY_MODE) ?: PhotoResizeManager.DEFAULT_DISPLAY_MODE

                    // Use scale type based on display mode
                    val scaleType = when(displayMode) {
                        PhotoResizeManager.DISPLAY_MODE_FILL,
                        PhotoResizeManager.DISPLAY_MODE_SMART_FILL -> ImageLoadStrategy.ScaleType.CENTER_CROP
                        else -> ImageLoadStrategy.ScaleType.FIT_CENTER
                    }

                    // Create options
                    val options = ImageLoadStrategy.ImageLoadOptions(
                        scaleType = scaleType,
                        diskCacheStrategy = ImageLoadStrategy.DiskCacheStrategy.ALL,
                        error = R.drawable.default_photo
                    )

                    // Load with Coil directly without using RequestListener
                    try {
                        imageLoadStrategy.loadImage(uri, views.overlayView, options)
                            .onSuccess { drawable ->
                                // Register local URI bitmaps with the memory manager
                                if (uri.startsWith("content://") && drawable is BitmapDrawable && drawable.bitmap != null) {
                                    bitmapMemoryManager.registerActiveBitmap("localUri:${uri.hashCode()}", drawable.bitmap)
                                    Log.d(TAG, "Registered local URI bitmap for tracking: $uri")
                                }

                                // Manually apply the transition effect without using Glide's listener
                                // This is the key change
                                views.overlayView.setImageDrawable(drawable)
                                views.overlayView.visibility = View.VISIBLE

                                // Use PhotoTransitionEffects directly
                                val transitionViews = PhotoTransitionEffects.TransitionViews(
                                    primaryView = views.primaryView,
                                    overlayView = views.overlayView,
                                    container = views.container,
                                    topLetterboxView = views.topLetterboxView,
                                    bottomLetterboxView = views.bottomLetterboxView
                                )

                                transitionEffects.performTransition(
                                    views = transitionViews,
                                    resource = drawable,
                                    nextIndex = photoIndex,
                                    transitionEffect = transitionEffect,
                                    transitionDuration = transitionDuration,
                                    callback = this@PhotoDisplayManager
                                )
                            }
                            .onFailure { error ->
                                Log.e(TAG, "Failed to load photo: $uri", error)
                                isTransitioning = false

                                // Skip to next photo
                                currentPhotoIndex = photoIndex
                                loadAndDisplayPhoto(false)
                            }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading with Coil: $uri", e)
                        isTransitioning = false

                        // Skip to next photo
                        currentPhotoIndex = photoIndex
                        loadAndDisplayPhoto(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying photo", e)
                isTransitioning = false
                showDefaultPhoto()
            }
        }
    }

    /**
     * Complete the transition between photos
     */
    private fun completeTransition(views: Views, resource: Drawable, nextIndex: Int) {
        try {
            // Reset flag
            isTransitioning = false

            // Get current URI before we update the index
            val currentUri = photoManager.getPhotoUrl(currentPhotoIndex)

            // Validate the resource is not corrupt
            if (resource is BitmapDrawable) {
                val bitmap = resource.bitmap
                if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                    Log.e(TAG, "Invalid bitmap resource detected in transition completion! Skipping to next photo.")

                    // Safety fallback - clean up and move to next photo
                    isTransitioning = false
                    loadAndDisplayPhoto(false)
                    return
                }
            }

            // Get previous drawable that's now being replaced
            val previousDrawable = views.primaryView.drawable

            // First set the primary view to display the new drawable
            views.primaryView.setImageDrawable(resource)
            views.primaryView.visibility = View.VISIBLE

            // Clean up any previous local URI bitmap
            if (currentUri != null && currentUri.startsWith("content://")) {
                bitmapMemoryManager.unregisterActiveBitmap("localUri:${currentUri.hashCode()}")
                photoPreloader.releaseContentUri(currentUri)
            }

            // Update the current photo index
            currentPhotoIndex = nextIndex

            // Reset the overlay view
            views.overlayView.setImageDrawable(null)
            views.overlayView.visibility = View.INVISIBLE

            // Reset animation properties
            views.primaryView.apply {
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                rotationX = 0f
                rotationY = 0f
                rotation = 0f
            }

            // IMPORTANT CHANGE: Release previous bitmap back to the pool instead of recycling it
            if (previousDrawable is BitmapDrawable && previousDrawable.bitmap != null && !previousDrawable.bitmap.isRecycled) {
                // Release the bitmap back to the pool
                smartPhotoLayoutManager.releaseBitmap(previousDrawable.bitmap)
                Log.d(TAG, "Released previous bitmap back to the pool")
            }

            // Hide the letterbox views if they're visible
            views.topLetterboxView?.visibility = View.GONE
            views.bottomLetterboxView?.visibility = View.GONE
            views.leftLetterboxView?.visibility = View.GONE
            views.rightLetterboxView?.visibility = View.GONE

            // Record successful transition for monitoring
            recordPhotoChange()

            // Schedule the next photo after an interval
            val interval = getIntervalMillis()

            // Cancel any existing display job
            displayJob?.cancel()

            // Create a new job to show the next photo after the interval
            displayJob = lifecycleScope?.launch {
                try {
                    delay(interval)
                    if (isActive) {
                        loadAndDisplayPhoto(false)
                    }
                } catch (e: CancellationException) {
                    // Expected during cancellation
                    Log.d(TAG, "Photo transition job cancelled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during photo transition", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error completing transition", e)
            // Safety fallback
            isTransitioning = false
            loadAndDisplayPhoto(false)
        }
    }

    fun hideLoadingOverlay() {
        views?.let { views ->
            if (!isMainThread()) {
                views.container.post { hideLoadingOverlay() }
                return
            }

            views.overlayMessageContainer?.let { container ->
                // Cancel any pending animations
                container.animate().cancel()

                // Animate fade out
                container.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        container.visibility = View.GONE
                        views.overlayMessageText?.text = ""
                    }
                    .start()

                Log.d(TAG, "Hiding loading overlay")
            }
        }
    }

    private fun showNoPhotosMessage() {
        views?.let { views ->
            try {
                // Hide any existing messages first
                hideAllMessages()

                // Load default photo without animation and show message
                views.primaryView.post {
                    views.primaryView.load(R.drawable.default_photo) {
                        diskCachePolicy(CachePolicy.ENABLED)
                        crossfade(false)
                    }

                    // Show message overlay
                    views.overlayMessageContainer?.visibility = View.VISIBLE
                    views.overlayMessageText?.text = "No photos selected"

                    // Ensure the view is visible
                    views.primaryView.visibility = View.VISIBLE
                }

                Log.d(TAG, "No photos message shown")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing no photos message", e)
            }
        }
    }

    private fun preloadDefaultPhoto() {
        try {
            // Create request for preloading default photo
            val request = ImageRequest.Builder(context.applicationContext)
                .data(R.drawable.default_photo)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()

            // Enqueue the request with the ImageLoader
            context.imageLoader.enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading default photo", e)
        }
    }

    private fun showDefaultPhoto() {
        views?.let { views ->
            try {
                // Hide any existing messages first
                hideAllMessages()

                // Load default photo without animation
                views.primaryView.post {
                    views.primaryView.load(R.drawable.default_photo) {
                        diskCachePolicy(CachePolicy.ENABLED)
                        crossfade(false) // No animation
                    }

                    // Ensure the view is visible
                    views.primaryView.visibility = View.VISIBLE
                }

                Log.d(TAG, "Default photo loaded")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading default photo", e)
            }
        }
    }

    fun startPhotoDisplay() {
        // Firebase Analytics log
        try {
            val analytics = com.google.firebase.analytics.FirebaseAnalytics.getInstance(context)
            analytics.logEvent("screensaver_playing", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error logging to Firebase Analytics", e)
        }

        // Firebase Crashlytics log
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().log("Screensaver is playing")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging to Crashlytics", e)
        }

        // Strong throttling - don't allow rapid consecutive calls
        val currentTime = System.currentTimeMillis()
        val timeSinceLastStart = currentTime - lastDisplayStartTime

        if (timeSinceLastStart < MIN_DISPLAY_INTERVAL) {
            Log.d(TAG, "Ignoring startPhotoDisplay() - called too soon after previous start (${timeSinceLastStart}ms < ${MIN_DISPLAY_INTERVAL}ms)")
            return
        }

        // Double protection against concurrent calls
        if (isStartingDisplay) {
            Log.d(TAG, "Display start already in progress, ignoring duplicate call")
            return
        }

        // Record this attempt even if it fails
        lastDisplayStartTime = currentTime
        isStartingDisplay = true

        val interval = getIntervalMillis()
        Log.d(TAG, "Starting photo display with interval: ${interval}ms")

        // Set photostreamr state
        isScreensaverActive = true

        // Notify Spotify
        if (spotifyPreferences.isEnabled()) {
            spotifyManager.onScreensaverStarted()
        }

        // Hide any existing messages immediately
        hideLoadingOverlay()
        hideAllMessages()

        // Cancel any existing display job
        displayJob?.cancel()

        // First check if there are any photos available
        val photoCount = photoManager.getPhotoCount()
        if (photoCount == 0) {
            Log.d(TAG, "No photos available (no albums selected), showing default photo")
            showDefaultPhoto()
            isStartingDisplay = false
            return
        }

        wasDisplayingPhotos = true

        // Reset any stuck transition flags
        if (isTransitioning) {
            val lastPhotoChangeTimeDiff = System.currentTimeMillis() - lastPhotoChangeTime
            if (lastPhotoChangeTimeDiff > 10000) { // 10 seconds
                Log.w(TAG, "Transition flag was stuck when starting display, resetting it")
                isTransitioning = false
            }
        }

        // Just load the first photo immediately - the rest will be scheduled by completeTransition
        loadAndDisplayPhoto(false)

        // Reset flag after initial setup is complete
        isStartingDisplay = false
    }

    private fun resetViewProperties(views: Views) {
        // Cancel any ongoing animations
        views.overlayView.animate().cancel()
        views.primaryView.animate().cancel()

        // Reset view properties
        views.overlayView.apply {
            alpha = 0f
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            rotationX = 0f
            rotationY = 0f
            rotation = 0f
            translationZ = 0f
            visibility = View.VISIBLE
        }
    }

    /**
     * Handle low memory condition
     */
    fun onLowMemory() {
        Log.d(TAG, "PhotoDisplayManager: onLowMemory called")

        // Clear the bitmap pool in SmartPhotoLayoutManager
        smartPhotoLayoutManager.clearBitmapPool()

        // Also trigger a memory cleanup in BitmapMemoryManager
        bitmapMemoryManager.clearMemoryCaches()
    }

    /**
     * Get the current interval in milliseconds from preferences
     * Always reads fresh from preferences to ensure it uses the latest user setting
     */
    private fun getIntervalMillis(): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val seconds = prefs.getInt(PREF_KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)
        val milliseconds = seconds * MILLIS_PER_SECOND
        Log.d(TAG, "Photo interval from preferences: $seconds seconds ($milliseconds ms)")
        return milliseconds
    }


    private fun Drawable.toBitmap(): Bitmap? {
        return try {
            if (this is android.graphics.drawable.BitmapDrawable) {
                this.bitmap
            } else {
                // Create bitmap with transparency
                val bitmap = Bitmap.createBitmap(
                    intrinsicWidth.coerceAtLeast(1),
                    intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                setBounds(0, 0, canvas.width, canvas.height)
                draw(canvas)
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting drawable to bitmap", e)
            null
        }
    }

    /**
     * Update display settings and apply changes
     * ENHANCED: Better handling of display mode changes
     */
    fun updateSettings(transitionDuration: Long? = null, showLocation: Boolean? = null, isRandomOrder: Boolean? = null) {
        Log.d(TAG, "Updating settings")

        // If transition duration is provided, update it, otherwise get it from preferences
        if (transitionDuration != null) {
            this.transitionDuration = transitionDuration
            Log.d(TAG, "Setting transition duration to: $transitionDuration ms")
        } else {
            // Read from preferences if not explicitly provided
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val durationSetting = prefs.getInt("transition_duration", 2)
            this.transitionDuration = durationSetting * 1000L
            Log.d(TAG, "Using transition duration from preferences: ${this.transitionDuration} ms")
        }

        showLocation?.let { this.showLocation = it }
        isRandomOrder?.let { this.isRandomOrder = it }

        // Apply current display mode (fill or fit)
        photoResizeManager.applyDisplayMode()

        // Get the current display mode
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val displayMode = prefs.getString(PhotoResizeManager.PREF_KEY_PHOTO_SCALE,
            PhotoResizeManager.DEFAULT_DISPLAY_MODE) ?:
        PhotoResizeManager.DEFAULT_DISPLAY_MODE

        // Log the current template type setting
        val templateType = prefs.getString(PhotoResizeManager.TEMPLATE_TYPE_KEY,
            PhotoResizeManager.TEMPLATE_TYPE_DEFAULT.toString())
        Log.d(TAG, "Current settings - Display mode: $displayMode, Template type: $templateType, Transition duration: ${this.transitionDuration}ms")

        // Make sure we restart photo display to apply any changes
        val currentScope = lifecycleScope ?: return
        currentScope.launch {
            stopPhotoDisplay()
            startPhotoDisplay()
        }
    }

    private fun hideAllMessages() {
        views?.let { views ->
            try {
                // Make sure we're on main thread
                if (!isMainThread()) {
                    views.container.post { hideAllMessages() }
                    return
                }

                // Cancel any running animations
                views.overlayMessageContainer?.animate()?.cancel()
                views.backgroundLoadingIndicator?.animate()?.cancel()
                views.loadingIndicator?.animate()?.cancel()

                // Hide all messages immediately
                views.loadingIndicator?.visibility = View.GONE
                views.loadingMessage?.visibility = View.GONE
                views.overlayMessageContainer?.visibility = View.GONE
                views.backgroundLoadingIndicator?.visibility = View.GONE
                views.overlayMessageText?.text = ""

            } catch (e: Exception) {
                Log.e(TAG, "Error hiding messages", e)
            }
        }
    }

    private fun trackPhotoLoadTime(isFromCache: Boolean, loadTimeMs: Long) {
        Log.d(TAG, "Photo load time (${if (isFromCache) "cached" else "fresh"}): $loadTimeMs ms")
    }

    private fun displayNativeAd() {
        val views = this.views ?: return
        val currentScope = lifecycleScope ?: return

        Log.d(TAG, "Attempting to display native ad")

        // Skip if pro version
        if (appVersionManager.isProVersion()) {
            Log.d(TAG, "Skipping native ad - Pro version")
            return
        }

        // Skip if already displaying an ad
        if (isShowingNativeAd) {
            Log.d(TAG, "Already showing a native ad, skipping")
            return
        }

        isShowingNativeAd = true

        currentScope.launch {
            try {
                // Show loading indicator
                views.loadingIndicator?.visibility = View.VISIBLE

                // Request a native ad
                Log.d(TAG, "Requesting native ad from AdManager")

                withContext(Dispatchers.Main) {
                    adManager.getNativeAdForSlideshow(views.container.context as Activity) { nativeAd ->
                        if (nativeAd == null) {
                            Log.w(TAG, "No native ad available, continuing with regular photos")
                            isShowingNativeAd = false
                            views.loadingIndicator?.visibility = View.GONE
                            loadAndDisplayPhoto()
                            return@getNativeAdForSlideshow
                        }

                        Log.d(TAG, "Native ad received successfully, displaying")

                        // --- MEMORY LEAK FIX: destroy previous NativeAd if present ---
                        if (currentNativeAd != null) {
                            Log.d(TAG, "Destroying previous NativeAd before showing a new one")
                            currentNativeAd?.destroy()
                        }
                        currentNativeAd = nativeAd

                        try {
                            val activity = views.container.context as Activity

                            // Create the native ad view
                            val adRootView = adManager.getNativeAdView(activity, nativeAd)

                            // Apply software rendering to prevent GPU allocation crashes
                            if (adManager is AdManager) {
                                try {
                                    // Use software rendering for Android 11 and below
                                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                                        adRootView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                        Log.d(TAG, "Using software rendering for native ad on Android ${Build.VERSION.SDK_INT} (≤ Android 11)")

                                        // Find and set software rendering for MediaView specifically
                                        try {
                                            val mediaView = adRootView.findViewById<View>(R.id.ad_media)
                                            if (mediaView != null) {
                                                mediaView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                                Log.d(TAG, "Set MediaView to software rendering")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error finding MediaView", e)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to set layer type on ad view", e)
                                }
                            }

                            // Create a frame to hold the ad view
                            val adContainer = FrameLayout(activity)
                            adContainer.layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            adContainer.tag = "native_ad_container"
                            adContainer.addView(adRootView)

                            // NEW CODE: Apply software rendering to the container as well
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                                adContainer.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            }

                            // Get the parent view to add our ad container to
                            val parent = views.primaryView.parent as? ViewGroup

                            if (parent != null) {
                                // --- CLEANUP: Remove any old ad containers first ---
                                for (i in parent.childCount - 1 downTo 0) {
                                    val child = parent.getChildAt(i)
                                    if (child is FrameLayout && child.tag == "native_ad_container") {
                                        parent.removeViewAt(i)
                                    }
                                }

                                // Add the ad container
                                parent.addView(adContainer)
                                adContainer.bringToFront()

                                // Hide the loading indicator
                                views.loadingIndicator?.visibility = View.GONE

                                // Schedule removal of the ad
                                mainHandler.postDelayed({
                                    try {
                                        parent.removeView(adContainer)
                                        isShowingNativeAd = false
                                        // --- MEMORY LEAK FIX: destroy NativeAd when it's no longer shown ---
                                        if (currentNativeAd != null) {
                                            Log.d(TAG, "Destroying NativeAd after use")
                                            currentNativeAd?.destroy()
                                            currentNativeAd = null
                                        }

                                        // Continue with regular photos
                                        loadAndDisplayPhoto()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error removing ad container", e)
                                        isShowingNativeAd = false
                                        loadAndDisplayPhoto()
                                    }
                                }, nativeAdDuration)
                            } else {
                                Log.e(TAG, "Cannot find parent view to add ad container")
                                isShowingNativeAd = false
                                views.loadingIndicator?.visibility = View.GONE
                                // --- MEMORY LEAK FIX: destroy NativeAd if not shown ---
                                Log.d(TAG, "Destroying NativeAd that wasn't displayed")
                                nativeAd.destroy()
                                currentNativeAd = null
                                loadAndDisplayPhoto()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error displaying native ad", e)
                            isShowingNativeAd = false
                            views.loadingIndicator?.visibility = View.GONE
                            // --- MEMORY LEAK FIX: destroy NativeAd if error ---
                            Log.d(TAG, "Destroying NativeAd due to display error")
                            nativeAd.destroy()
                            currentNativeAd = null
                            loadAndDisplayPhoto()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in displayNativeAd", e)
                isShowingNativeAd = false
                views.loadingIndicator?.visibility = View.GONE
                loadAndDisplayPhoto()
            }
        }
    }

    private fun cleanupCurrentNativeAd() {
        try {
            currentNativeAd?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying native ad", e)
        } finally {
            currentNativeAd = null
            isShowingNativeAd = false
        }
    }

    fun stopPhotoDisplay() {
        isScreensaverActive = false
        cleanupCurrentNativeAd()
        photoPreloader.stopPreloading()
        displayJob?.cancel()
        displayJob = null

        // Clear our tracking but don't force recycling
        if (lastPhotoUrl != null) {
            bitmapMemoryManager.unregisterActiveBitmap("display:$lastPhotoUrl")
            lastPhotoUrl = null
        }

        bitmapMemoryManager.clearMemoryCaches()

        Log.d(TAG, "Photo display stopped")
    }

    fun clearPhotoCache() {
        photoCache.cleanup()
    }

    fun cleanup() {
        displayJob?.cancel()
        photoPreloader.cleanup()
        bitmapMemoryManager.cleanup()
        diskCacheManager.cleanup()
        managerJob.cancel()
        if (currentNativeAd != null) {
            Log.d(TAG, "Destroying NativeAd during PhotoDisplayManager cleanup")
            currentNativeAd?.destroy()
            currentNativeAd = null
        }
    }

    fun updatePhotoSources(virtualAlbumPhotos: List<Uri> = emptyList()) {
        val currentScope = lifecycleScope ?: return

        currentScope.launch {
            try {
                val photos = mutableListOf<Uri>()

                // Get photos from PhotoManager, but only from selected virtual albums
                val repoPhotos = photoManager.loadPhotos()
                if (repoPhotos == null) {
                    Log.d(TAG, "No photos available (no albums selected), showing default photo")
                    showDefaultPhoto()
                    return@launch
                }

                repoPhotos.forEach { mediaItem ->
                    try {
                        photos.add(Uri.parse(mediaItem.baseUrl))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing URI: ${mediaItem.baseUrl}", e)
                    }
                }

                Log.d(TAG, """Updating photo sources:
                • Photos from manager: ${photos.size}
                • Virtual album photos: ${virtualAlbumPhotos.size}""".trimIndent())

                if (photos.isNotEmpty()) {
                    displayPhotos(photos)
                } else {
                    Log.d(TAG, "No photos available to display")
                    showDefaultPhoto()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating photo sources", e)
                showDefaultPhoto()
            }
        }
    }

    private fun displayPhotos(photos: List<Uri>) {
        Log.d(TAG, "Displaying ${photos.size} photos")
        lifecycleScope?.launch {
            try {
                stopPhotoDisplay()
                val photoUrls = photos.map { it.toString() }
                photoManager.addPhotoUrls(photoUrls)

                if (photoUrls.isNotEmpty()) {
                    persistPhotoState("combined", photoUrls[0])
                }

                currentPhotoIndex = 0
                hasLoadedPhotos = true
                startPhotoDisplay()

                Log.d(TAG, "Photo display started with ${photos.size} photos")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting photo display", e)
                showDefaultPhoto()
            }
        }
    }

    /**
     * Start automatic recovery monitoring to detect and fix stuck photo slideshows
     */
    fun startAutomaticRecoveryMonitoring() {
        Log.d(TAG, "Starting automatic photo slideshow recovery monitoring")

        // Cancel any existing runnable
        stopAutomaticRecoveryMonitoring()

        recoveryRunnable = object : Runnable {
            override fun run() {
                checkAndRecoverFromStuckState()

                // Schedule next check
                recoveryHandler.postDelayed(this, 60000) // Check every minute
            }
        }

        // Schedule first check
        recoveryHandler.postDelayed(recoveryRunnable!!, 60000)
    }

    /**
     * Stop the automatic recovery monitoring
     */
    fun stopAutomaticRecoveryMonitoring() {
        recoveryRunnable?.let {
            recoveryHandler.removeCallbacks(it)
            recoveryRunnable = null
        }
    }

    /**
     * Record a successful photo transition
     * This should be called whenever a photo is successfully displayed
     */
    fun recordPhotoChange() {
        lastPhotoChangeTime = System.currentTimeMillis()
    }

    /**
     * Check if the photo slideshow appears to be stuck and recover if needed
     */
    private fun checkAndRecoverFromStuckState() {
        if (!isScreensaverActive) {
            return
        }
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastPhotoChangeTime

        if (timeSinceLastChange > PHOTO_STUCK_THRESHOLD) {
            Log.w(TAG, "Photo slideshow appears stuck for ${timeSinceLastChange/1000} seconds, forcing recovery")
            isTransitioning = false

            try {
                adManager.reinitializeAdSystem() // Use correct method
            } catch (e: Exception) {
                Log.e(TAG, "Error reinitializing AdManager during recovery", e)
            }

            lifecycleScope?.launch { // Launch coroutine for suspend calls
                try {
                    val photoCount = try { photoManager.getPhotoCount() } catch(e: Exception) { 0 }
                    if (photoCount > 0) {
                        val nextIndex = (currentPhotoIndex + 1) % photoCount
                        currentPhotoIndex = nextIndex
                        loadAndDisplayPhoto(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting photo count during recovery", e)
                }
            }
        }
    }

    /**
     * Get a photo index that's likely different from the current one
     * Used for ensuring varied experience after restart
     */
    private fun getRandomDifferentPhotoIndex(): Int {
        val photoCount = photoManager.getPhotoCount()

        // If we have few photos, just add 1 and wrap around
        if (photoCount <= 3) {
            return (currentPhotoIndex + 1) % photoCount
        }

        // With more photos, pick a truly random one but avoid current
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val wasRestarting = prefs.getBoolean("is_performing_restart", false)

        // During restart, be extra sure we get a different photo
        return if (wasRestarting) {
            // Get a sequence of photos to avoid
            val excludeIndices = setOf(
                currentPhotoIndex,
                (currentPhotoIndex + 1) % photoCount,
                (currentPhotoIndex - 1 + photoCount) % photoCount
            )

            // Keep trying until we find one not in the exclude list
            var candidate = Random.nextInt(photoCount)
            while (excludeIndices.contains(candidate)) {
                candidate = Random.nextInt(photoCount)
            }

            candidate
        } else {
            // Normal operation, just avoid current
            var newIndex = Random.nextInt(photoCount)
            if (newIndex == currentPhotoIndex) {
                newIndex = (newIndex + 1) % photoCount
            }
            newIndex
        }
    }
}