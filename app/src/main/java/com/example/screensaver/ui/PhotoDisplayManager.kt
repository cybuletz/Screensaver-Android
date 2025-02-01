package com.example.screensaver.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.data.PhotoCache
import com.example.screensaver.models.LoadingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Locale
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
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.HttpException
import com.example.screensaver.glide.GlideApp
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Request
import java.util.concurrent.TimeUnit


@Singleton
class PhotoDisplayManager @Inject constructor(
    private val photoManager: LockScreenPhotoManager,
    private val photoCache: PhotoCache,
    private val context: Context
) {
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    private val _photoLoadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val photoLoadingState: StateFlow<LoadingState> = _photoLoadingState

    private val _cacheStatusMessage = MutableStateFlow<String?>(null)
    val cacheStatusMessage: StateFlow<String?> = _cacheStatusMessage

    private val preloadLimit = 3
    private var hasVerifiedPhotos = false
    private var photoCount: Int = 0

    private var hasLoadedPhotos = false

    data class Views(
        val primaryView: ImageView,
        val overlayView: ImageView,
        val clockView: TextView?,
        val dateView: TextView?,
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
    private var timeUpdateJob: Job? = null

    // Settings
    private var transitionDuration: Long = 1000
    private var photoInterval: Long = 10000
    private var showClock: Boolean = true
    private var showDate: Boolean = true
    private var showLocation: Boolean = false
    private var isRandomOrder: Boolean = false

    // Date formatters
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val TAG = "PhotoDisplayManager"
        private const val DEFAULT_PHOTO_FADE_DURATION = 300
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

    // Add this method to check if screensaver is running
    fun isScreensaverRunning(): Boolean {
        return displayJob?.isActive == true
    }

    // Add this method to handle auto-start
    fun handleAutoStart() {
        if (!isScreensaverRunning()) {
            startPhotoDisplay()
        }
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

    fun dumpCacheStatus(): String {
        return photoCache.getCacheDebugInfo()
    }

    fun preloadNextPhotos(urls: List<String>, limit: Int = preloadLimit) {
        managerScope.launch {
            try {
                val startIndex = (currentPhotoIndex + 1).coerceAtMost(urls.size - 1)
                val endIndex = (startIndex + limit).coerceAtMost(urls.size)

                for (i in startIndex until endIndex) {
                    photoCache.preloadPhoto(urls[i])
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preload photos", e)
            }
        }
    }

    private data class InitialState(
        val cachedBitmap: Bitmap?,
        val photoCount: Int
    )

    private fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    fun initialize(views: Views, scope: LifecycleCoroutineScope) {
        Log.d(TAG, "Initializing PhotoDisplayManager")
        this.views = views
        this.lifecycleScope = scope

        // Verify views are properly set
        views.clockView?.let {
            Log.d(TAG, "Clock view initialized with visibility: ${it.visibility}")
        } ?: Log.e(TAG, "Clock view is null during initialization")

        views.dateView?.let {
            Log.d(TAG, "Date view initialized with visibility: ${it.visibility}")
        } ?: Log.e(TAG, "Date view is null during initialization")

        // Update visibility immediately after initialization
        updateTimeDisplayVisibility()
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

    fun updatePhotoSources() {
        val currentSources = PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")

        val photos = mutableListOf<Uri>()

        if (currentSources.contains("local")) {
            photos.addAll(loadLocalPhotos())
        }

        if (photos.isNotEmpty()) {
            displayPhotos(photos)
        } else {
            Log.w(TAG, "No photos available to display")
        }
    }

    private fun displayPhotos(photos: List<Uri>) {
        Log.d(TAG, "Displaying ${photos.size} photos")

        lifecycleScope?.launch {
            try {
                // Stop any existing display
                stopPhotoDisplay()

                // Convert URIs to strings and add as photo URLs
                val photoUrls = photos.map { it.toString() }
                photoManager.addPhotoUrls(photoUrls)  // Use the new method

                currentPhotoIndex = 0
                hasLoadedPhotos = true

                // Start displaying photos
                startPhotoDisplay()

                Log.d(TAG, "Photo display started with ${photos.size} photos")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting photo display", e)
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

    private fun updateLoadingState(isLoading: Boolean, message: String? = null) {
        views?.apply {
            loadingIndicator?.visibility = if (isLoading) View.VISIBLE else View.GONE
            loadingMessage?.apply {
                text = message
                visibility = if (message != null) View.VISIBLE else View.GONE
            }
        }
    }

    fun isInitialized(): Boolean {
        return views != null && lifecycleScope != null
    }

    fun startPhotoDisplay() {
        val currentScope = lifecycleScope ?: return
        Log.d(TAG, "Starting photo display with interval: ${photoInterval}ms")

        // Hide any existing messages immediately
        hideLoadingOverlay()
        hideAllMessages()

        // Cancel any existing display job
        displayJob?.cancel()

        // Make sure to update time display visibility before starting
        updateTimeDisplayVisibility()

        displayJob = currentScope.launch {
            try {
                // Start with first photo immediately
                loadAndDisplayPhoto(false)

                // Then continue with regular interval
                while (isActive) {
                    delay(photoInterval)
                    loadAndDisplayPhoto()
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in photo display loop", e)
                }
            }
        }

        // Start time updates if needed
        if (showClock || showDate) {
            startTimeUpdates()
        }
    }

    private fun hideLoadingMessage() {
        views?.overlayMessageContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(300)
            ?.withEndAction {
                views?.overlayMessageContainer?.visibility = View.GONE
            }
            ?.start()
    }

    private fun loadAndDisplayPhoto(fromCache: Boolean = false) {
        if (isTransitioning) {
            Log.d(TAG, "Skipping photo load - transition in progress")
            return
        }

        val photoCount = photoManager.getPhotoCount()
        if (photoCount == 0) {
            Log.d(TAG, "No photos available, showing default photo")
            showDefaultPhoto()
            return
        }

        val nextIndex = if (isRandomOrder) {
            Random.nextInt(photoCount)
        } else {
            (currentPhotoIndex + 1) % photoCount
        }

        val nextUrl = photoManager.getPhotoUrl(nextIndex) ?: return
        Log.d(TAG, "Loading photo $nextIndex: $nextUrl")
        val startTime = System.currentTimeMillis()

        views?.let { views ->
            isTransitioning = true

            try {
                // Get the current transition effect from preferences
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val transitionEffect = prefs.getString("transition_effect", "fade") ?: "fade"

                // Reset view properties
                resetViewProperties(views)

                // Load the image using GlideApp
                GlideApp.with(context)
                    .load(nextUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .error(R.drawable.default_photo)
                    .listener(createGlideListener(views, nextIndex, startTime, transitionEffect))
                    .into(views.overlayView)

            } catch (e: Exception) {
                Log.e(TAG, "Error in loadAndDisplayPhoto", e)
                isTransitioning = false
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

            // If we get a 403, try refreshing the token and retrying
            if (e?.rootCauses?.any { it is HttpException && it.statusCode == 403 } == true) {
                lifecycleScope?.launch {
                    try {
                        if (photoManager.refreshTokens()) {
                            // Add a small delay before retrying
                            delay(500)
                            // Clear Glide's memory cache for this URL to force a new request
                            model?.toString()?.let { url ->
                                Glide.get(context).clearMemory()
                                GlideApp.with(context).clear(views.overlayView)
                            }
                            // Retry the load after token refresh
                            loadAndDisplayPhoto(true)
                        } else {
                            Log.e(TAG, "Failed to refresh tokens")
                            showErrorMessage("Failed to refresh authentication")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during token refresh", e)
                        showErrorMessage("Authentication error")
                    }
                }
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

        // Update primary view
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

        // Reset overlay view
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

        // Update time display visibility after transition
        updateTimeDisplayVisibility()

        // Hide any remaining messages
        hideAllMessages()

        isTransitioning = false
        currentPhotoIndex = nextIndex
        Log.d(TAG, "Transition completed to photo $nextIndex")
    }

    private fun finishTransition(views: Views, resource: Drawable, nextIndex: Int) {
        views.primaryView.setImageDrawable(resource)
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
        }
        views.primaryView.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            rotationX = 0f
            rotationY = 0f
            rotation = 0f
            translationZ = 0f
        }
        isTransitioning = false
        currentPhotoIndex = nextIndex
    }

    private fun preloadNextBatch(currentIndex: Int, totalPhotos: Int) {
        managerScope.launch {
            try {
                for (i in 1..preloadLimit) {
                    val nextIndex = (currentIndex + i) % totalPhotos
                    photoManager.getPhotoUrl(nextIndex)?.let { url ->
                        photoCache.preloadPhoto(url)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading next batch", e)
            }
        }
    }

    // Helper extension function to convert Drawable to Bitmap
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

    fun updateSettings(
        transitionDuration: Long? = null,
        photoInterval: Long? = null,
        showClock: Boolean? = null,
        showDate: Boolean? = null,
        showLocation: Boolean? = null,
        isRandomOrder: Boolean? = null
    ) {
        Log.d(TAG, "Updating settings - showClock: $showClock, showDate: $showDate")
        var shouldRestartDisplay = false

        // Store previous values for logging
        val previousShowClock = this.showClock
        val previousShowDate = this.showDate

        transitionDuration?.let {
            this.transitionDuration = it
        }
        photoInterval?.let {
            this.photoInterval = it
            shouldRestartDisplay = true
        }
        showClock?.let {
            this.showClock = it
            Log.d(TAG, "Clock visibility setting updated from $previousShowClock to: $it")
        }
        showDate?.let {
            this.showDate = it
            Log.d(TAG, "Date visibility setting updated from $previousShowDate to: $it")
        }
        showLocation?.let { this.showLocation = it }
        isRandomOrder?.let { this.isRandomOrder = it }

        // Update visibility immediately
        updateTimeDisplayVisibility()

        // Verify the changes took effect
        Log.d(TAG, "After settings update - showClock: ${this.showClock}, showDate: ${this.showDate}")

        // Restart photo display if interval changed
        if (shouldRestartDisplay) {
            Log.d(TAG, "Restarting photo display due to interval change")
            stopPhotoDisplay()
            startPhotoDisplay()
        }
    }

    private fun updateTimeDisplayVisibility() {
        Log.d(TAG, "Updating time display visibility - showClock: $showClock, showDate: $showDate")
        if (views == null) {
            Log.e(TAG, "Views are null when trying to update visibility")
            return
        }

        val clockView = views?.clockView
        if (clockView == null) {
            Log.e(TAG, "Clock view is null when trying to update visibility")
            return
        }

        val dateView = views?.dateView
        if (dateView == null) {
            Log.e(TAG, "Date view is null when trying to update visibility")
            return
        }

        clockView.apply {
            visibility = if (showClock) View.VISIBLE else View.GONE
            alpha = if (showClock) 1f else 0f
            bringToFront()  // Add this to ensure it's on top
            Log.d(TAG, "Clock visibility updated to: ${if (showClock) "VISIBLE" else "GONE"}, actual visibility: ${visibility == View.VISIBLE}")
        }

        dateView.apply {
            visibility = if (showDate) View.VISIBLE else View.GONE
            alpha = if (showDate) 1f else 0f
            bringToFront()  // Add this to ensure it's on top
            Log.d(TAG, "Date visibility updated to: ${if (showDate) "VISIBLE" else "GONE"}, actual visibility: ${visibility == View.VISIBLE}")
        }

        // Ensure the container is visible and on top
        views?.clockView?.parent?.let { parent ->
            if (parent is View) {
                parent.apply {
                    visibility = View.VISIBLE
                    alpha = 1f
                    bringToFront()
                }
                Log.d(TAG, "Brought clock/date container to front")
            }
        }

        // Ensure time updates are running if needed
        if ((showClock || showDate) && timeUpdateJob?.isActive != true) {
            Log.d(TAG, "Starting time updates")
            startTimeUpdates()
        } else if (!showClock && !showDate) {
            Log.d(TAG, "Cancelling time updates")
            timeUpdateJob?.cancel()
            timeUpdateJob = null
        }
    }

    fun getTransitionDuration(): Long = transitionDuration

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

                // Ensure clock and date are visible if enabled
                updateTimeDisplayVisibility()

            } catch (e: Exception) {
                Log.e(TAG, "Error hiding messages", e)
            }
        }
    }

    private fun startTimeUpdates() {
        val currentScope = lifecycleScope ?: return
        timeUpdateJob?.cancel()
        timeUpdateJob = currentScope.launch {
            while (isActive) {
                updateTimeDisplay()
                delay(1000)
            }
        }
    }

    private fun trackPhotoLoadTime(isFromCache: Boolean, loadTimeMs: Long) {
        Log.d(TAG, "Photo load time (${if (isFromCache) "cached" else "fresh"}): $loadTimeMs ms")
    }

    private fun updateTimeDisplay() {
        val now = System.currentTimeMillis()
        views?.apply {
            if (showClock) {
                clockView?.text = timeFormat.format(now)
            }
            if (showDate) {
                dateView?.text = dateFormat.format(now)
            }
        }
    }

    fun stopPhotoDisplay() {
        Log.d(TAG, "Stopping photo display")
        displayJob?.cancel()
        displayJob = null
    }

    fun handleLowMemory() { // Change from private to public
        Log.w(TAG, "Low memory condition detected")

        lifecycleScope?.launch {
            try {
                // Perform smart cleanup of cache
                photoCache.performSmartCleanup()

                // Cancel any pending photo loads
                displayJob?.cancel()

                // Try to keep current photo if possible
                val currentPhoto = views?.primaryView?.drawable
                if (currentPhoto != null) {
                    // Cache current photo before clearing
                    currentPhoto.toBitmap()?.let { bitmap ->
                        photoCache.cacheLastPhotoBitmap(bitmap)
                    }
                }

                // Clear Glide memory cache
                Glide.get(context).clearMemory()

                // Restart photo display with minimal memory usage
                startPhotoDisplay()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling low memory", e)
            }
        }
    }

    fun clearPhotoCache() {
        photoCache.cleanup()
    }

    fun cleanup(clearCache: Boolean = false) {
        Log.d(TAG, "Cleaning up PhotoDisplayManager, clearCache: $clearCache")
        managerScope.launch {
            stopPhotoDisplay()
            timeUpdateJob?.cancel()
            timeUpdateJob = null
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
}