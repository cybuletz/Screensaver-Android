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
import java.util.Date
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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.Priority
import android.graphics.Bitmap
import android.graphics.Canvas
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import android.net.Uri
import androidx.preference.PreferenceManager


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

    private fun startBackgroundPhotoLoad() {
        lifecycleScope?.launch(Dispatchers.IO) {
            try {
                if (!hasVerifiedPhotos) {
                    photoManager.loadPhotos()
                    hasVerifiedPhotos = true
                }

                val photoCount = photoManager.getPhotoCount()

                withContext(Dispatchers.Main) {
                    if (photoCount > 0) {
                        startPhotoDisplay()
                    } else {
                        showSettingsMessage()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in background photo load", e)
            }
        }
    }

    fun initialize(views: Views, scope: LifecycleCoroutineScope) {
        Log.d(TAG, "Initializing PhotoDisplayManager")
        this.views = views
        this.lifecycleScope = scope
        views.overlayView.alpha = 0f

        // Show default photo first
        showDefaultPhoto()

        scope.launch(Dispatchers.Main) {
            try {
                // First check if we've ever had photos
                if (!photoManager.hadPhotos()) {
                    Log.d(TAG, "First time user - showing welcome message")
                    showWelcomeMessage()
                    return@launch
                }

                // Check for cached photos
                val photoCount = photoManager.getPhotoCount()
                if (photoCount > 0) {
                    Log.d(TAG, "Welcome back - found ${photoCount} photos")
                    showWelcomeBackMessage(photoCount)
                    startPhotoDisplay()
                } else {
                    Log.d(TAG, "Welcome back - waiting for photos to load")
                    showWelcomeBackMessage(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization", e)
                showErrorMessage(e.message ?: "Unknown error occurred")
            }
        }
    }

    private fun showWelcomeMessage() {
        views?.let { views ->
            if (!isMainThread()) {
                views.container.post { showWelcomeMessage() }
                return
            }

            views.overlayMessageContainer?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                views.overlayMessageText?.text = context.getString(R.string.welcome_message)
                Log.d(TAG, "Welcome message displayed")
            } ?: Log.e(TAG, "Message container is null")
        }
    }

    private fun showWelcomeBackMessage(photoCount: Int) {
        views?.let { views ->
            if (!isMainThread()) {
                views.container.post { showWelcomeBackMessage(photoCount) }
                return
            }

            views.overlayMessageContainer?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                val message = if (photoCount > 0) {
                    context.getString(R.string.welcome_back_with_photos, photoCount)
                } else {
                    context.getString(R.string.welcome_back_no_photos)
                }
                views.overlayMessageText?.text = message
                Log.d(TAG, "Welcome back message displayed: $message")

                // Auto-hide message after 3 seconds if we have photos
                if (photoCount > 0) {
                    postDelayed({
                        hideLoadingOverlay()
                    }, 3000)
                }
            } ?: Log.e(TAG, "Message container is null")
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

    private fun showSettingsMessage() {
        views?.let { views ->
            if (!isMainThread()) {
                views.container.post { showSettingsMessage() }
                return
            }

            // Always show message container
            views.overlayMessageContainer?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                views.overlayMessageText?.text = context.getString(R.string.no_photo_source)
                Log.d(TAG, "Settings message displayed")
            } ?: Log.e(TAG, "Message container is null")
        }
    }

    fun hideLoadingOverlay() {
        views?.let { views ->
            views.overlayMessageContainer?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.withEndAction {
                    views.overlayMessageContainer.visibility = View.GONE
                }
                ?.start()
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

    private fun setupPhotoManagerObserver() {
        managerScope.launch {
            photoManager.loadingState.collect { state ->
                when (state) {
                    LockScreenPhotoManager.LoadingState.SUCCESS -> {
                        if (photoManager.getPhotoCount() > 0 && views != null) {
                            updateLoadingState(false)
                            startPhotoDisplay()
                        } else {
                            updateLoadingState(false, context.getString(R.string.no_photos_message))
                        }
                    }
                    LockScreenPhotoManager.LoadingState.LOADING -> {
                        updateLoadingState(true, context.getString(R.string.loading_photos_message))
                        _photoLoadingState.value = LoadingState.LOADING
                    }
                    LockScreenPhotoManager.LoadingState.ERROR -> {
                        updateLoadingState(false, context.getString(R.string.photos_load_error))
                        _photoLoadingState.value = LoadingState.ERROR("Failed to load photos")
                    }
                    else -> { /* Handle other states */ }
                }
            }
        }
    }

    private suspend fun handlePhotoManagerState(state: LockScreenPhotoManager.LoadingState) {
        views?.loadingIndicator?.isVisible = state == LockScreenPhotoManager.LoadingState.LOADING

        when (state) {
            LockScreenPhotoManager.LoadingState.LOADING -> {
                _photoLoadingState.value = LoadingState.LOADING
            }
            LockScreenPhotoManager.LoadingState.SUCCESS -> {
                val count = photoManager.getPhotoCount()
                Log.d(TAG, "Success state with $count photos")
                if (count > 0) {
                    _photoLoadingState.value = LoadingState.SUCCESS
                    if (displayJob == null) {
                        withContext(NonCancellable) {
                            startPhotoDisplay()
                        }
                    }
                }
            }
            LockScreenPhotoManager.LoadingState.ERROR -> {
                _photoLoadingState.value = LoadingState.ERROR("Failed to load photos")
            }
            LockScreenPhotoManager.LoadingState.IDLE -> {
                _photoLoadingState.value = LoadingState.IDLE
                if (photoManager.getPhotoCount() == 0) {
                    photoManager.loadPhotos()
                }
            }
        }
    }

    fun startPhotoDisplay() {
        val currentScope = lifecycleScope ?: return
        Log.d(TAG, "Starting photo display with ${photoManager.getPhotoCount()} photos")

        // Cancel any existing display job
        displayJob?.cancel()

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

    private suspend fun loadAndDisplayPhoto(fromCache: Boolean = false) {
        if (isTransitioning) return

        val photoCount = photoManager.getPhotoCount()
        if (photoCount == 0) return

        val nextIndex = if (isRandomOrder) {
            Random.nextInt(photoCount)
        } else {
            (currentPhotoIndex + 1) % photoCount
        }

        val nextUrl = photoManager.getPhotoUrl(nextIndex) ?: return
        val startTime = System.currentTimeMillis()

        views?.let { views ->
            isTransitioning = true
            try {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        Glide.with(context)
                            .load(nextUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .priority(Priority.IMMEDIATE)
                            .listener(object : RequestListener<Drawable> {
                                override fun onLoadFailed(
                                    e: GlideException?,
                                    model: Any?,
                                    target: Target<Drawable>,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    Log.e(TAG, "Failed to load photo: $nextUrl", e)
                                    isTransitioning = false
                                    return false
                                }

                                override fun onResourceReady(
                                    resource: Drawable,
                                    model: Any,
                                    target: Target<Drawable>,
                                    dataSource: DataSource,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    views.overlayView.alpha = 0f
                                    views.overlayView.visibility = View.VISIBLE

                                    views.overlayView.animate()
                                        .alpha(1f)
                                        .setDuration(transitionDuration)
                                        .withEndAction {
                                            views.primaryView.setImageDrawable(resource)
                                            views.overlayView.alpha = 0f
                                            views.overlayView.visibility = View.INVISIBLE
                                            isTransitioning = false
                                            currentPhotoIndex = nextIndex

                                            // Track load time
                                            trackPhotoLoadTime(fromCache, System.currentTimeMillis() - startTime)

                                            // Preload next batch
                                            preloadNextBatch(nextIndex, photoCount)
                                        }
                                        .start()
                                    return false
                                }
                            })
                            .into(views.overlayView)

                        hideLoadingOverlay()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photo", e)
                isTransitioning = false
            }
        }
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

    private suspend fun loadPhotoWithGlide(url: String, views: Views) {
        withContext(Dispatchers.Main) {
            Glide.with(context)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .listener(createGlideListener(url))
                .into(views.overlayView)

            views.overlayView.animate()
                .alpha(1f)
                .setDuration(transitionDuration)
                .withEndAction {
                    views.primaryView.setImageDrawable(views.overlayView.drawable)
                    views.overlayView.alpha = 0f
                    isTransitioning = false
                    currentPhotoIndex = (currentPhotoIndex + 1) % photoManager.getPhotoCount()
                }
                .start()
        }
    }

    private fun createGlideListener(url: String) = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            Log.e(TAG, "Failed to load photo: $url", e)
            isTransitioning = false
            return false
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            return false
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
        Log.d(TAG, "Updating settings")
        transitionDuration?.let { this.transitionDuration = it }
        photoInterval?.let { this.photoInterval = it }
        showClock?.let { this.showClock = it }
        showDate?.let { this.showDate = it }
        showLocation?.let { this.showLocation = it }
        isRandomOrder?.let { this.isRandomOrder = it }

        updateTimeDisplayVisibility()
    }

    private fun updateTimeDisplayVisibility() {
        views?.apply {
            clockView?.isVisible = showClock
            dateView?.isVisible = showDate
            locationView?.isVisible = showLocation
        }

        if ((showClock || showDate) && timeUpdateJob == null) {
            startTimeUpdates()
        } else if (!showClock && !showDate) {
            timeUpdateJob?.cancel()
            timeUpdateJob = null
        }
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
                Glide.with(context)
                    .load(R.drawable.default_photo)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .into(views.primaryView)
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

    private fun handleCacheLoadFailure(exception: Throwable? = null) {
        Log.w(TAG, "Cache load failed", exception)

        lifecycleScope?.launch {
            try {
                // Keep showing default photo
                showDefaultPhoto()

                // Try to load photos
                withContext(Dispatchers.IO) {
                    if (!hasVerifiedPhotos) {
                        photoManager.loadPhotos()
                        hasVerifiedPhotos = true
                    }
                }

                // Check if we have photos after loading
                val photoCount = photoManager.getPhotoCount()
                if (photoCount > 0) {
                    startPhotoDisplay()
                } else {
                    showSettingsMessage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling cache failure", e)
                showDefaultPhoto()
                if (!hasVerifiedPhotos) {
                    showSettingsMessage()
                }
            }
        }
    }

    private fun handleNetworkError(exception: Throwable? = null) {
        Log.w(TAG, "Network error while loading photos", exception)

        lifecycleScope?.launch {
            try {
                // Try to use cached photo if available
                val cachedUrl = photoCache.getLastCachedPhotoUrl()
                if (cachedUrl != null) {
                    loadAndDisplayPhoto(true) // Pass true for fromCache
                } else {
                    val cachedBitmap = photoCache.getLastCachedPhotoBitmap()
                    if (cachedBitmap != null) {
                        views?.primaryView?.setImageBitmap(cachedBitmap)
                    } else {
                        showDefaultPhoto()
                        if (!hasVerifiedPhotos) {
                            showSettingsMessage()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling network error", e)
                showDefaultPhoto()
                if (!hasVerifiedPhotos) {
                    showSettingsMessage()
                }
            }
        }
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