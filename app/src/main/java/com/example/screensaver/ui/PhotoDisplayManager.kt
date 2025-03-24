package com.example.screensaver.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.PhotoRepository
import com.example.screensaver.data.PhotoCache
import com.example.screensaver.models.LoadingState
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
import com.example.screensaver.R
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
import android.graphics.drawable.BitmapDrawable
import com.bumptech.glide.load.HttpException
import com.example.screensaver.MainActivity
import com.example.screensaver.data.PhotoStorageCoordinator
import com.example.screensaver.glide.GlideApp
import com.example.screensaver.models.MediaItem
import com.example.screensaver.music.SpotifyManager
import com.example.screensaver.music.SpotifyPreferences
import com.example.screensaver.photos.PhotoPermissionManager
import com.example.screensaver.photos.PhotoUriManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


@Singleton
class PhotoDisplayManager @Inject constructor(
    private val photoManager: PhotoRepository,
    private val storageCoordinator: PhotoStorageCoordinator,
    private val photoCache: PhotoCache,
    private val context: Context,
    private val spotifyManager: SpotifyManager,
    private val spotifyPreferences: SpotifyPreferences,
    private val photoUriManager: PhotoUriManager,
    private val photoPermissionManager: PhotoPermissionManager
) {
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

    private var isRecovering = false

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
            return
        }

        // Check if music should be playing
        if (isScreensaverActive && spotifyPreferences.isEnabled() &&
            spotifyManager.playbackState.value is SpotifyManager.PlaybackState.Idle) {
            spotifyManager.resume()
        }

        // First try to get photos from coordinator, fall back to photoManager
        val coordinatorPhotos = storageCoordinator.getAllPhotos()
        val photoCount = if (coordinatorPhotos.isNotEmpty()) {
            coordinatorPhotos.size
        } else {
            photoManager.getPhotoCount()
        }

        if (photoCount == 0) {
            Log.d(TAG, "No photos available, showing default photo")
            showDefaultPhoto()
            return
        }

        lifecycleScope?.launch {
            try {
                val nextIndex = getNextPhotoIndex(currentPhotoIndex, photoCount)
                val nextUrl = if (coordinatorPhotos.isNotEmpty()) {
                    coordinatorPhotos.getOrNull(nextIndex)?.baseUrl
                } else {
                    photoManager.getPhotoUrl(nextIndex)
                } ?: return@launch

                // First validate permissions if needed
                if (!fromCache && !isRecovering) {
                    try {
                        val validationResult = photoPermissionManager.validatePhotos(lifecycleScope!!)
                        if (!validationResult) {
                            handlePermissionError(nextUrl)  // Now nextUrl is available
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error validating photos, continuing without validation", e)
                        // Continue without validation rather than failing
                    }
                }

                views?.let { views ->
                    isTransitioning = true

                    try {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        val transitionEffect = prefs.getString("transition_effect", "fade") ?: "fade"
                        // Record the start time for tracking load performance
                        val startTime = System.currentTimeMillis()

                        resetViewProperties(views)

                        withContext(Dispatchers.Main) {
                            GlideApp.with(context)
                                .load(nextUrl)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .error(R.drawable.default_photo)
                                .listener(createGlideListener(views, nextIndex, startTime, transitionEffect))
                                .into(views.overlayView)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in loadAndDisplayPhoto", e)
                        isTransitioning = false
                        showErrorMessage(context.getString(R.string.error_loading_photo))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadAndDisplayPhoto", e)
                isTransitioning = false
                showErrorMessage(context.getString(R.string.error_loading_photo))
            }
        }
    }


    suspend fun validateAllPhotos() {
        try {
            // Validate photos from both sources
            photoManager.validateStoredPhotos()
            storageCoordinator.validateAllPhotos()
        } catch (e: Exception) {
            Log.e(TAG, "Error validating photos", e)
        }
    }

    suspend fun getAllPhotos(): List<MediaItem> {
        return try {
            // Try coordinator first
            val coordinatorPhotos = storageCoordinator.getAllPhotos()
            if (coordinatorPhotos.isNotEmpty()) {
                coordinatorPhotos
            } else {
                // Fall back to legacy repository
                photoManager.getAllPhotos()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all photos", e)
            emptyList()
        }
    }

    suspend fun updatePhotoSourcesWithValidation() {
        try {
            val photos = getAllPhotos()
            val validPhotos = photos.filter { photo ->
                photoUriManager.hasValidPermission(Uri.parse(photo.baseUrl))
            }

            if (validPhotos.size < photos.size) {
                Log.w(TAG, "Found ${photos.size - validPhotos.size} invalid URIs, they will be skipped")
            }

            updatePhotoSources(validPhotos.map { Uri.parse(it.baseUrl) })
        } catch (e: Exception) {
            Log.e(TAG, "Error updating photo sources with validation", e)
        }
    }

    private fun handlePermissionError(uri: String) {
        if (isRecovering) return
        isRecovering = true

        lifecycleScope?.launch {
            try {
                views?.apply {
                    loadingIndicator?.visibility = View.VISIBLE
                    loadingMessage?.text = context.getString(R.string.recovering_photo_access)
                }

                // First try to refresh the tokens
                val tokenRefreshed = photoManager.refreshTokens()
                if (!tokenRefreshed) {
                    // Don't remove photos immediately on token refresh failure
                    Log.w(TAG, "Token refresh failed, will retry on next cycle")
                    isRecovering = false
                    views?.loadingIndicator?.visibility = View.GONE

                    // Skip to next photo instead of removing
                    if (photoManager.getPhotoCount() > 1) {
                        loadAndDisplayPhoto(true)
                        return@launch
                    }
                }

                // Only check access if token refresh succeeded
                if (tokenRefreshed) {
                    val canAccess = photoUriManager.hasValidPermission(Uri.parse(uri))
                    if (!canAccess) {
                        Log.d(TAG, "Could not recover access to photo, removing it")
                        photoManager.removePhoto(uri)
                    }
                }

                isRecovering = false
                views?.loadingIndicator?.visibility = View.GONE

                // Only show default if we have no photos left
                if (photoManager.getPhotoCount() == 0) {
                    showErrorMessage(context.getString(R.string.error_photo_access_lost))
                    stopPhotoDisplay()
                    showDefaultPhoto()
                } else {
                    // Try next photo
                    loadAndDisplayPhoto(true)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling permission recovery", e)
                isRecovering = false
                views?.loadingIndicator?.visibility = View.GONE

                // Don't stop on error, try next photo
                if (photoManager.getPhotoCount() > 0) {
                    loadAndDisplayPhoto(true)
                } else {
                    showErrorMessage(context.getString(R.string.error_photo_access_lost))
                    stopPhotoDisplay()
                    showDefaultPhoto()
                }
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

    fun startPhotoDisplay() {
        val currentScope = lifecycleScope ?: return
        val interval = getIntervalMillis()
        Log.d(TAG, "Starting photo display with interval: ${interval}ms")

        // Set screensaver state
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
                var errorCount = 0
                while (isActive) {
                    try {
                        loadAndDisplayPhoto(false)
                        errorCount = 0  // Reset error count on success
                        delay(getIntervalMillis())
                    } catch (e: Exception) {
                        errorCount++
                        Log.e(TAG, "Error in photo display loop (attempt $errorCount)", e)
                        if (errorCount >= 3) {  // Stop after 3 consecutive errors
                            Log.e(TAG, "Too many errors, stopping photo display")
                            showDefaultPhoto()
                            break
                        }
                        delay(1000)  // Wait a bit before retrying
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Fatal error in photo display loop", e)
                    showDefaultPhoto()
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
        nextIndex: Int,
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

            // Check for permission denial or 403
            if (e?.rootCauses?.any {
                    it is SecurityException && it.message?.contains("Permission Denial") == true ||
                            it is HttpException && it.statusCode == 403
                } == true) {
                model?.toString()?.let { uri -> handlePermissionError(uri) }
            } else {
                showErrorMessage(context.getString(R.string.error_loading_photo))
            }

            return false
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            Log.d(TAG, "Photo loaded, starting transition: $transitionEffect")
            views.overlayView.cameraDistance = views.overlayView.width * 3f

            when (transitionEffect) {
                "slide" -> performSlideTransition(views, resource, nextIndex)
                "zoom" -> performZoomTransition(views, resource, nextIndex)
                "flip" -> performFlipTransition(views, resource, nextIndex)
                "rotate" -> performRotateTransition(views, resource, nextIndex)
                "depth" -> performDepthTransition(views, resource, nextIndex)
                "cube" -> performCubeTransition(views, resource, nextIndex)
                else -> performFadeTransition(views, resource, nextIndex)
            }

            trackPhotoLoadTime(dataSource == DataSource.MEMORY_CACHE, System.currentTimeMillis() - startTime)
            return false
        }
    }

    private fun performFadeTransition(views: Views, resource: Drawable, nextIndex: Int) {
        views.overlayView.apply {
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(transitionDuration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { completeTransition(views, resource, nextIndex) }
                .start()
        }
    }

    private fun performSlideTransition(views: Views, resource: Drawable, nextIndex: Int) {
        views.overlayView.apply {
            alpha = 1f
            translationX = width.toFloat()
            animate()
                .translationX(0f)
                .setDuration(transitionDuration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { completeTransition(views, resource, nextIndex) }
                .start()
        }
    }

    private fun performZoomTransition(views: Views, resource: Drawable, nextIndex: Int) {
        views.overlayView.apply {
            alpha = 0f
            scaleX = 1.2f
            scaleY = 1.2f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(transitionDuration)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { completeTransition(views, resource, nextIndex) }
                .start()
        }
    }

    private fun performFlipTransition(views: Views, resource: Drawable, nextIndex: Int) {
        // Ensure both views are visible and have correct initial state
        views.primaryView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 0f
        }

        views.overlayView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 90f
            setImageDrawable(resource)
        }

        // Set camera distance to prevent clipping
        val distance = views.overlayView.width * 3f
        views.primaryView.cameraDistance = distance
        views.overlayView.cameraDistance = distance

        // Create AnimatorSet for synchronized animations
        val animatorSet = AnimatorSet()

        // Create overlay view animation (new image)
        val overlayFlip = ObjectAnimator.ofFloat(views.overlayView, View.ROTATION_Y, 90f, 0f)

        // Create primary view animation (old image)
        val primaryFlip = ObjectAnimator.ofFloat(views.primaryView, View.ROTATION_Y, 0f, -90f)

        // Configure animations
        animatorSet.apply {
            playTogether(overlayFlip, primaryFlip)
            duration = transitionDuration
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completeTransition(views, resource, nextIndex)
                }
            })
        }

        // Start the animation
        animatorSet.start()
    }

    private fun performRotateTransition(views: Views, resource: Drawable, nextIndex: Int) {
        views.overlayView.apply {
            alpha = 0f
            rotation = -180f
            scaleX = 0.5f
            scaleY = 0.5f
            animate()
                .alpha(1f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(transitionDuration)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { completeTransition(views, resource, nextIndex) }
                .start()
        }
    }

    private fun performDepthTransition(views: Views, resource: Drawable, nextIndex: Int) {
        // Set initial states
        views.primaryView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }

        views.overlayView.apply {
            visibility = View.VISIBLE
            setImageDrawable(resource)
            alpha = 0f
            scaleX = 1.5f
            scaleY = 1.5f
            translationZ = -1000f
        }

        // Create AnimatorSet for synchronized animations
        val animatorSet = AnimatorSet()

        // Animations for the new image (overlay)
        val overlayAlpha = ObjectAnimator.ofFloat(views.overlayView, View.ALPHA, 0f, 1f)
        val overlayScaleX = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_X, 1.5f, 1f)
        val overlayScaleY = ObjectAnimator.ofFloat(views.overlayView, View.SCALE_Y, 1.5f, 1f)
        val overlayZ = ObjectAnimator.ofFloat(views.overlayView, View.TRANSLATION_Z, -1000f, 0f)

        // Animations for the old image (primary)
        val primaryAlpha = ObjectAnimator.ofFloat(views.primaryView, View.ALPHA, 1f, 0f)
        val primaryScaleX = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_X, 1f, 0.5f)
        val primaryScaleY = ObjectAnimator.ofFloat(views.primaryView, View.SCALE_Y, 1f, 0.5f)
        val primaryZ = ObjectAnimator.ofFloat(views.primaryView, View.TRANSLATION_Z, 0f, -500f)

        // Configure animations
        animatorSet.apply {
            playTogether(
                overlayAlpha, overlayScaleX, overlayScaleY, overlayZ,
                primaryAlpha, primaryScaleX, primaryScaleY, primaryZ
            )
            duration = transitionDuration
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completeTransition(views, resource, nextIndex)
                }
            })
        }

        // Start the animation
        animatorSet.start()
    }

    private fun performCubeTransition(views: Views, resource: Drawable, nextIndex: Int) {
        // Ensure both views are visible and have correct initial state
        views.primaryView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 0f
            translationX = 0f
        }

        views.overlayView.apply {
            visibility = View.VISIBLE
            alpha = 1f
            rotationY = 90f
            translationX = width.toFloat()
            setImageDrawable(resource)
        }

        // Create AnimatorSet for synchronized animations
        val animatorSet = AnimatorSet()

        // Create overlay view animations
        val overlayAnim = ObjectAnimator.ofFloat(views.overlayView, View.ROTATION_Y, 90f, 0f)
        val overlayTranslation = ObjectAnimator.ofFloat(views.overlayView, View.TRANSLATION_X,
            views.overlayView.width.toFloat(), 0f)

        // Create primary view animations
        val primaryAnim = ObjectAnimator.ofFloat(views.primaryView, View.ROTATION_Y, 0f, -90f)
        val primaryTranslation = ObjectAnimator.ofFloat(views.primaryView, View.TRANSLATION_X,
            0f, -views.primaryView.width.toFloat())

        // Configure animations
        animatorSet.apply {
            playTogether(overlayAnim, overlayTranslation, primaryAnim, primaryTranslation)
            duration = transitionDuration
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completeTransition(views, resource, nextIndex)
                }
            })
        }

        // Start the animation
        animatorSet.start()
    }

    private fun completeTransition(views: Views, resource: Drawable, nextIndex: Int) {
        if (!isMainThread()) {
            views.container.post { completeTransition(views, resource, nextIndex) }
            return
        }

        try {
            // Clear the old drawable from the overlay view first
            views.overlayView.setImageDrawable(null)

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

            // Reset overlay view properties
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
                visibility = View.INVISIBLE
            }

            // Hide any remaining messages
            hideAllMessages()

            isTransitioning = false
            currentPhotoIndex = nextIndex
            Log.d(TAG, "Transition completed to photo $nextIndex")

        } catch (e: Exception) {
            Log.e(TAG, "Error in completeTransition", e)
            isTransitioning = false
        }
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

    fun updateSettings(transitionDuration: Long? = null, showLocation: Boolean? = null, isRandomOrder: Boolean? = null) {
        Log.d(TAG, "Updating settings")

        transitionDuration?.let { this.transitionDuration = it }
        showLocation?.let { this.showLocation = it }
        isRandomOrder?.let { this.isRandomOrder = it }
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

    fun stopPhotoDisplay() {
        Log.d(TAG, "Stopping photo display")
        displayJob?.cancel()
        displayJob = null

        // Handle screensaver state
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
    }

    fun updatePhotoSources(virtualAlbumPhotos: List<Uri> = emptyList()) {
        val currentScope = lifecycleScope ?: return

        currentScope.launch {
            try {
                val photos = mutableListOf<Uri>()

                // Get photos from both sources
                val coordinatorPhotos = storageCoordinator.getAllPhotos()
                val repoPhotos = photoManager.loadPhotos()

                // Add coordinator photos
                coordinatorPhotos.forEach { mediaItem ->
                    try {
                        photos.add(Uri.parse(mediaItem.baseUrl))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing URI: ${mediaItem.baseUrl}", e)
                    }
                }

                // Add repo photos if not already added
                repoPhotos?.forEach { mediaItem ->
                    try {
                        val uri = Uri.parse(mediaItem.baseUrl)
                        if (!photos.contains(uri)) {
                            photos.add(uri)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing URI: ${mediaItem.baseUrl}", e)
                    }
                }

                Log.d(TAG, """Updating photo sources:
            • Photos from coordinator: ${coordinatorPhotos.size}
            • Photos from manager: ${repoPhotos?.size ?: 0}
            • Combined unique photos: ${photos.size}
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
        lifecycleScope?.launch {
            try {
                stopPhotoDisplay()

                // First validate the URIs
                val validPhotoUrls = photos.filter { uri ->
                    photoUriManager.validateUri(uri) || photoUriManager.hasValidPermission(uri)
                }.map { it.toString() }

                // Log validation results
                if (validPhotoUrls.size < photos.size) {
                    Log.w(TAG, "Some URIs failed validation - Using ${validPhotoUrls.size}/${photos.size}")
                }

                // Add to both systems during transition
                photoManager.addPhotoUrls(validPhotoUrls)
                storageCoordinator.addPhotos(validPhotoUrls.map { url ->
                    MediaItem(
                        id = url,
                        albumId = "display_photos",
                        baseUrl = url,
                        mimeType = "image/*",
                        width = 0,
                        height = 0,
                        description = null,
                        createdAt = System.currentTimeMillis(),
                        loadState = MediaItem.LoadState.IDLE
                    )
                })

                if (validPhotoUrls.isNotEmpty()) {
                    persistPhotoState("combined", validPhotoUrls[0])
                    currentPhotoIndex = 0
                    hasLoadedPhotos = true
                    startPhotoDisplay()

                    Log.d(TAG, "Photo display started with ${photos.size} photos")
                } else {
                    Log.d(TAG, "No valid photos to display")
                    showDefaultPhoto()
                }
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