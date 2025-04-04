package com.photostreamr.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.photostreamr.PhotoRepository
import com.photostreamr.data.PhotoCache
import com.photostreamr.models.LoadingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
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
import com.bumptech.glide.load.HttpException
import com.google.android.gms.ads.nativead.NativeAd
import com.photostreamr.glide.GlideApp
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


@Singleton
class PhotoDisplayManager @Inject constructor(
    private val photoManager: PhotoRepository,
    private val photoCache: PhotoCache,
    private val context: Context,
    private val spotifyManager: SpotifyManager,
    private val spotifyPreferences: SpotifyPreferences,
    private val adManager: AdManager,
    private val appVersionManager: AppVersionManager
) : PhotoTransitionEffects.TransitionCompletionCallback {

    private val photoScalingEffects = PhotoScalingEffects(context)
    private var photoScaleMode: String = PreferenceManager.getDefaultSharedPreferences(context)
        .getString("photo_scale", "fill") ?: "fill"
    private var photoEnhancementEffect: String = PreferenceManager.getDefaultSharedPreferences(context)
        .getString("photo_enhancement", "bokeh") ?: "bokeh"
    private var transitionStartTime: Long = 0L
    private val simplePhotoEffects = SimplePhotoEffects(context)



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

    private var currentNativeAd: NativeAd? = null
    private var isShowingNativeAd = false
    private var nativeAdDuration = 10000L // Display native ads for 10 seconds
    private val mainHandler = Handler(Looper.getMainLooper())


    data class Views(
        val primaryView: ImageView,
        val overlayView: ImageView,
        val locationView: TextView?,
        val loadingIndicator: View?,
        val loadingMessage: TextView?,
        val container: View,
        val overlayMessageContainer: View?,
        val overlayMessageText: TextView?,
        val backgroundLoadingIndicator: View?
    )

    private var views: Views? = null
    private var lifecycleScope: LifecycleCoroutineScope? = null
    private var currentPhotoIndex = 0
    private var isTransitioning = false
    private var displayJob: Job? = null

    // Settings
    private var transitionDuration: Long = 1000
    private var showLocation: Boolean = false
    private var isRandomOrder: Boolean = false

    companion object {
        private const val TAG = "PhotoDisplayManager"
        const val PREF_KEY_INTERVAL = "photo_interval"
        const val DEFAULT_INTERVAL_SECONDS = 5
        private const val MILLIS_PER_SECOND = 1000L
        private const val SPOTIFY_RECONNECT_DELAY = 5000L
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

    override fun onTransitionCompleted(resource: Drawable, nextIndex: Int) {
        val currentViews = views ?: return
        completeTransition(currentViews, resource, nextIndex)
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

    private fun getIntervalMillis(): Long {
        val seconds = prefs.getInt(PREF_KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)
        return seconds * MILLIS_PER_SECOND
    }

    private fun loadPhotoFromUri(uri: Uri, imageView: ImageView) {
        Glide.with(context)
            .load(uri)
            .into(imageView)
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

    private fun loadLocalPhotos(): List<Uri> {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet("selected_local_photos", emptySet())
            ?.mapNotNull { uriString ->
                try {
                    Uri.parse(uriString)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing URI: $uriString", e)
                    null
                }
            } ?: emptyList()
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

    private fun loadAndDisplayPhoto(fromCache: Boolean = false) {
        if (isTransitioning) {
            Log.d(TAG, "Skipping photo load - transition in progress")

            // Failsafe: If we've been transitioning for too long, reset the state
            if (transitionStartTime != 0L && System.currentTimeMillis() - transitionStartTime > 10000) {
                Log.w(TAG, "Transition has been in progress for too long, resetting state")
                isTransitioning = false
                transitionStartTime = 0L
            } else if (transitionStartTime == 0L) {
                // Set the transition start time if it's not set
                transitionStartTime = System.currentTimeMillis()
            }
            return
        }

        // Reset transition start time
        transitionStartTime = 0L

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

        // Get available photos and filter out Google Photos URIs that aren't cached
        currentScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                if (photoCount == 0) {
                    Log.d(TAG, "No photos available, showing default photo with message")
                    showNoPhotosMessage()
                    return@launch
                }

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
                            // We have a cached version, use it
                            displayPhoto(nextIndex, cachedUri, true)
                            foundValidPhoto = true
                        } else {
                            // No cached version, skip to next photo
                            Log.d(TAG, "Skipping uncached Google Photos URI: $nextUrl")
                            currentPhotoIndex = nextIndex
                        }
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
                Log.e(TAG, "Error finding valid photo", e)
                showDefaultPhoto()
            }
        }
    }


    private fun displayPhoto(photoIndex: Int, uri: String, isCached: Boolean) {
        val views = this.views ?: return
        val currentScope = lifecycleScope ?: return

        photoScalingEffects.cleanupAnimations()
        isTransitioning = true

        currentScope.launch {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val transitionEffect = prefs.getString("transition_effect", "fade") ?: "fade"
                val startTime = System.currentTimeMillis()

                resetViewProperties(views)

                Log.d(TAG, "Displaying photo $photoIndex: $uri" + (if(isCached) " (cached)" else ""))

                withContext(Dispatchers.Main) {
                    GlideApp.with(context)
                        .load(uri)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .error(R.drawable.default_photo)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                Log.e(TAG, "Failed to load photo: $model", e)
                                isTransitioning = false

                                // Skip to next photo
                                currentPhotoIndex = photoIndex
                                loadAndDisplayPhoto(false)
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                Log.d(TAG, "Photo loaded, starting fade transition")

                                try {
                                    // Set the resource on the overlay view
                                    views.overlayView.setImageDrawable(resource)
                                    views.overlayView.visibility = View.VISIBLE
                                    views.overlayView.alpha = 0f

                                    // Simple fade animation
                                    views.overlayView.animate()
                                        .alpha(1f)
                                        .setDuration(transitionDuration)
                                        .withEndAction {
                                            // When animation completes, update the primary view
                                            completeTransition(views, resource, photoIndex)
                                        }
                                        .start()

                                    trackPhotoLoadTime(dataSource == DataSource.MEMORY_CACHE, System.currentTimeMillis() - startTime)
                                    return true // Return true to indicate we've handled setting the resource
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error during resource ready handling", e)
                                    isTransitioning = false
                                    return false
                                }
                            }
                        })
                        .into(views.overlayView)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying photo", e)
                isTransitioning = false
                showDefaultPhoto()
            }
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
                    Glide.with(context)
                        .load(R.drawable.default_photo)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .into(views.primaryView)

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

    fun startPhotoDisplay() {
        val currentScope = lifecycleScope ?: return
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
            return
        }

        wasDisplayingPhotos = true
        displayJob = currentScope.launch {
            try {
                // Start with first photo immediately
                loadAndDisplayPhoto(false)

                var lastPhotoIndex = currentPhotoIndex
                var stuckCounter = 0

                // Then continue with regular interval
                while (isActive) {
                    delay(getIntervalMillis()) // Always get fresh value

                    // Debug check to see if photos are actually changing
                    if (lastPhotoIndex == currentPhotoIndex) {
                        stuckCounter++
                        if (stuckCounter >= 3) {
                            Log.w(TAG, "Photo display appears to be stuck on photo $currentPhotoIndex, forcing next photo")
                            isTransitioning = false
                            stuckCounter = 0
                        }
                    } else {
                        stuckCounter = 0
                        lastPhotoIndex = currentPhotoIndex
                    }

                    loadAndDisplayPhoto()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in photo display loop", e)
                }
            }
        }
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

    private fun createGlideListener(
        views: Views,
        nextIndex: Int, // This parameter is available in this scope
        startTime: Long,
        transitionEffect: String
    ) = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            Log.e(TAG, "Failed to load photo: $model", e)
            isTransitioning = false

            // Skip to next photo
            currentPhotoIndex = nextIndex
            loadAndDisplayPhoto(false)
            return false
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            Log.d(TAG, "Photo loaded, starting fade transition")

            try {
                // Set the resource on the overlay view
                views.overlayView.setImageDrawable(resource)
                views.overlayView.visibility = android.view.View.VISIBLE
                views.overlayView.alpha = 0f

                // Simple fade animation - nextIndex comes from the outer method
                views.overlayView.animate()
                    .alpha(1f)
                    .setDuration(transitionDuration)
                    .withEndAction {
                        // When animation completes, update the primary view
                        completeTransition(views, resource, nextIndex)
                    }
                    .start()

                trackPhotoLoadTime(dataSource == DataSource.MEMORY_CACHE, System.currentTimeMillis() - startTime)
                return true // Return true to indicate we've handled setting the resource
            } catch (e: Exception) {
                Log.e(TAG, "Error during resource ready handling", e)
                isTransitioning = false
                return false
            }
        }
    }

    private fun completeTransition(views: Views, resource: Drawable, nextIndex: Int) {
        if (!isMainThread()) {
            views.container.post { completeTransition(views, resource, nextIndex) }
            return
        }

        try {
            Log.d(TAG, "Starting transition completion to photo $nextIndex")

            // Update primary view with the new drawable
            views.primaryView.apply {
                setImageDrawable(resource)
                alpha = 1f
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

            // Reset overlay view properties but keep it VISIBLE
            views.overlayView.apply {
                // Don't set alpha to 0 or INVISIBLE if we're going to use it for bokeh
                setImageDrawable(null) // Clear any old image
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                rotationX = 0f
                rotationY = 0f
                rotation = 0f
                translationZ = 0f
                visibility = View.VISIBLE // Keep visible
            }

            // Apply scaling effects to the primary view if needed
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val photoScaleMode = prefs.getString("photo_scale", "fill") ?: "fill"
            val photoEnhancementEffect = prefs.getString("photo_enhancement", "bokeh") ?: "bokeh"

            // First, apply ONLY the scaling mode without any enhancement
            if (photoScaleMode != "fill") {
                val scalingViews = PhotoScalingEffects.ScalingViews(
                    imageView = views.primaryView,
                    container = views.container as ViewGroup,
                    backgroundView = null // No background for scaling
                )

                photoScalingEffects.applyScalingMode(
                    views = scalingViews,
                    drawable = resource,
                    mode = photoScaleMode,
                    enhancementEffect = PhotoScalingEffects.EFFECT_NONE // No effect here
                )

                Log.d(TAG, "Applied scaling mode: $photoScaleMode")
            }

            // AFTER applying scaling, now apply the bokeh effect if needed
            if (photoEnhancementEffect == PhotoScalingEffects.EFFECT_BOKEH) {
                val bokehViews = PhotoScalingEffects.ScalingViews(
                    imageView = views.primaryView,
                    container = views.container as ViewGroup,
                    backgroundView = views.overlayView
                )

                // Apply just the bokeh effect without changing scaling
                photoScalingEffects.applyEnhancementEffect(
                    bokehViews,
                    resource,
                    PhotoScalingEffects.EFFECT_BOKEH
                )

                Log.d(TAG, "Applied bokeh effect separately after scaling")
            } else {
                // If no bokeh, make sure overlay is invisible
                views.overlayView.visibility = View.INVISIBLE
            }

            // Hide any remaining messages
            hideAllMessages()

            // IMPORTANT: Reset the transition state to allow new photos to load
            isTransitioning = false
            currentPhotoIndex = nextIndex

            // Notify AdManager of the photo count for ad frequency tracking
            adManager.updatePhotoCount(currentPhotoIndex)
            Log.d(TAG, "Transition completed to photo $nextIndex")

        } catch (e: Exception) {
            Log.e(TAG, "Error in completeTransition", e)
            isTransitioning = false
        }
    }

    private fun applyScalingAndEffects(
        imageView: ImageView,
        backgroundView: ImageView?,
        container: ViewGroup,
        drawable: Drawable,
        mode: String,
        enhancementEffect: String
    ) {
        Log.d(TAG, "Applying scaling mode: $mode and effect: $enhancementEffect separately")

        // First apply the scaling mode without any enhancement
        val scalingViews = PhotoScalingEffects.ScalingViews(
            imageView = imageView,
            container = container,
            backgroundView = null // No background for scaling
        )

        photoScalingEffects.applyScalingMode(
            views = scalingViews,
            drawable = drawable,
            mode = mode,
            enhancementEffect = PhotoScalingEffects.EFFECT_NONE // No effect here
        )

        // Then apply just the enhancement effect if needed
        if (enhancementEffect != PhotoScalingEffects.EFFECT_NONE && backgroundView != null) {
            val effectViews = PhotoScalingEffects.ScalingViews(
                imageView = imageView,
                container = container,
                backgroundView = backgroundView
            )

            // Apply only the enhancement effect
            photoScalingEffects.applyScalingMode(
                views = effectViews,
                drawable = drawable,
                mode = mode, // Pass the mode for logging
                enhancementEffect = enhancementEffect
            )
        }

        Log.d(TAG, "Applied scaling and effects separately")
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

    private fun isBitmapInUse(bitmap: Bitmap): Boolean {
        return views?.let { views ->
            val primaryBitmap = (views.primaryView.drawable as? BitmapDrawable)?.bitmap
            val overlayBitmap = (views.overlayView.drawable as? BitmapDrawable)?.bitmap
            bitmap === primaryBitmap || bitmap === overlayBitmap
        } ?: false
    }

    fun updateSettings(
        transitionDuration: Long? = null,
        showLocation: Boolean? = null,
        isRandomOrder: Boolean? = null,
        photoScaleMode: String? = null,
        photoEnhancementEffect: String? = null
    ) {
        Log.d(TAG, "Updating settings")

        transitionDuration?.let { this.transitionDuration = it }
        showLocation?.let { this.showLocation = it }
        isRandomOrder?.let { this.isRandomOrder = it }
        photoScaleMode?.let { this.photoScaleMode = it }
        photoEnhancementEffect?.let { this.photoEnhancementEffect = it }
    }

    private fun preloadDefaultPhoto() {
        try {
            Glide.with(context.applicationContext)
                .load(R.drawable.default_photo)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .submit()
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
                    Glide.with(context)
                        .load(R.drawable.default_photo)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .into(views.primaryView)

                    // Ensure the view is visible
                    views.primaryView.visibility = View.VISIBLE
                }

                Log.d(TAG, "Default photo loaded")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading default photo", e)
            }
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
                        currentNativeAd = nativeAd

                        try {
                            val activity = views.container.context as Activity

                            // Create the native ad view
                            val adRootView = adManager.getNativeAdView(activity, nativeAd)

                            // Create a frame to hold the ad view
                            val adContainer = FrameLayout(activity)
                            adContainer.layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            adContainer.tag = "native_ad_container"
                            adContainer.addView(adRootView)

                            // Get the parent view to add our ad container to
                            val parent = views.primaryView.parent as? ViewGroup

                            if (parent != null) {
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
                                        currentNativeAd?.destroy()
                                        currentNativeAd = null

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
                                loadAndDisplayPhoto()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error displaying native ad", e)
                            isShowingNativeAd = false
                            views.loadingIndicator?.visibility = View.GONE
                            nativeAd.destroy()
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

    fun stopPhotoDisplay() {
        Log.d(TAG, "Stopping photo display")
        displayJob?.cancel()
        displayJob = null

        // Handle photostreamr state
        isScreensaverActive = false

        // Notify Spotify if we were actually displaying photos
        if (wasDisplayingPhotos && spotifyPreferences.isEnabled()) {
            spotifyManager.onScreensaverStopped()
        }
        wasDisplayingPhotos = false
    }

    fun clearPhotoCache() {
        photoCache.cleanup()
    }

    fun cleanup(clearCache: Boolean = false) {
        Log.d(TAG, "Cleaning up PhotoDisplayManager, clearCache: $clearCache")
        managerScope.launch {
            stopPhotoDisplay()  // This will handle Spotify cleanup
            views = null
            lifecycleScope = null
            _photoLoadingState.value = LoadingState.IDLE

            if (clearCache) {
                withContext(Dispatchers.IO) {
                    photoCache.cleanup()
                }
            }
        }
        managerJob.cancel()
        photoScalingEffects.release()
        simplePhotoEffects.release()


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

    fun handleLowMemory() {
        Log.w(TAG, "Low memory condition detected")
        lifecycleScope?.launch {
            try {
                // Pause Spotify playback if necessary
                if (isScreensaverActive && spotifyPreferences.isEnabled()) {
                    spotifyManager.pause()
                }

                photoCache.performSmartCleanup()
                displayJob?.cancel()

                val currentPhoto = views?.primaryView?.drawable
                if (currentPhoto != null) {
                    currentPhoto.toBitmap()?.let { bitmap ->
                        photoCache.cacheLastPhotoBitmap(bitmap)
                    }
                }

                Glide.get(context).clearMemory()
                startPhotoDisplay()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling low memory", e)
                showDefaultPhoto()
            }
        }
    }
}