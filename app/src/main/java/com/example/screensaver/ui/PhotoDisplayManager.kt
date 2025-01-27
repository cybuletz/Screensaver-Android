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

@Singleton
class PhotoDisplayManager @Inject constructor(
    private val photoManager: LockScreenPhotoManager,
    private val context: Context
) {
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    private val _photoLoadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val photoLoadingState: StateFlow<LoadingState> = _photoLoadingState

    data class Views(
        val primaryView: ImageView,
        val overlayView: ImageView,
        val clockView: TextView?,
        val dateView: TextView?,
        val locationView: TextView?,
        val loadingIndicator: View?,
        val loadingMessage: TextView?,
        val container: View,
        val overlayMessageContainer: View?,  // New field
        val overlayMessageText: TextView?    // New field
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
    }

    fun initialize(views: Views, scope: LifecycleCoroutineScope) {
        Log.d(TAG, "Initializing PhotoDisplayManager")
        this.views = views
        this.lifecycleScope = scope

        views.overlayView.alpha = 0f
        _photoLoadingState.value = LoadingState.LOADING

        val currentScope = scope
        currentScope.launch {
            try {
                if (photoManager.getPhotoCount() > 0) {
                    // If photos are already available, start display immediately
                    startPhotoDisplay()
                } else {
                    // Only show default photo and message if no photos are available
                    showDefaultPhoto()
                    checkForPhotosInBackground()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during initialization", e)
                _photoLoadingState.value = LoadingState.ERROR(e.message ?: "Unknown error")
            }
        }

        setupPhotoManagerObserver()
        updateTimeDisplayVisibility()
    }

    fun showLoadingOverlay(message: String) {
        views?.let { views ->
            views.overlayMessageText?.text = message
            views.overlayMessageContainer?.apply {
                visibility = View.VISIBLE
                alpha = 0f
                animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
        }
    }

    fun hideLoadingOverlay() {
        views?.let { views ->
            views.overlayMessageContainer?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.withEndAction {
                    views.overlayMessageContainer?.visibility = View.GONE
                }
                ?.start()
        }
    }

    private fun showDefaultPhotoWithLoadingMessage() {
        views?.let { views ->
            try {
                // Show default photo
                views.primaryView.post {
                    views.primaryView.setImageResource(R.drawable.default_photo)
                }

                // Show overlay message with animation
                views.overlayMessageContainer?.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    animate()
                        .alpha(1f)
                        .setDuration(300)
                        .start()
                }

                views.overlayMessageText?.text = context.getString(R.string.loading_photos_friendly)

                updateLoadingState(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing default photo with message", e)
                updateLoadingState(false, context.getString(R.string.no_photos_message))
            }
        }
    }

    private fun checkForPhotosInBackground() {
        lifecycleScope?.launch(Dispatchers.IO) {
            try {
                photoManager.loadPhotos() // Assuming this is your method to load photos
                if (photoManager.getPhotoCount() > 0) {
                    withContext(Dispatchers.Main) {
                        startPhotoDisplay()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos in background", e)
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

    private suspend fun ensurePhotosLoaded(): Boolean {
        var retryCount = 0
        val maxRetries = 10

        while (photoManager.getPhotoCount() == 0 && retryCount < maxRetries) {
            Log.d(TAG, "Waiting for photos to load... attempt ${retryCount + 1}")
            delay(500)
            retryCount++
        }

        val photoCount = photoManager.getPhotoCount()
        Log.d(TAG, "Photos available after waiting: $photoCount")

        if (photoCount == 0) {
            showDefaultPhoto()
            return false
        }
        return true
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
        displayJob?.cancel()

        displayJob = currentScope.launch {
            try {
                val photoCount = photoManager.getPhotoCount()
                Log.d(TAG, "Starting photo display with $photoCount photos")

                if (photoCount > 0) {
                    // Hide all messages before starting photo display
                    hideAllMessages()

                    // Initial photo display
                    loadAndDisplayPhoto()

                    while (isActive) {
                        delay(photoInterval)
                        val currentCount = photoManager.getPhotoCount()
                        if (currentCount > 0) {
                            loadAndDisplayPhoto()
                        } else {
                            Log.w(TAG, "No photos available during display loop")
                            break
                        }
                    }
                } else {
                    showDefaultPhoto()
                    Log.w(TAG, "No photos available to display")
                    _photoLoadingState.value = LoadingState.ERROR("No photos available")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in photo display loop", e)
                    showDefaultPhoto()
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

    private suspend fun loadAndDisplayPhoto() {
        if (isTransitioning) return

        val photoCount = photoManager.getPhotoCount()
        if (photoCount == 0) return

        val nextIndex = if (isRandomOrder) {
            Random.nextInt(photoCount)
        } else {
            (currentPhotoIndex + 1) % photoCount
        }

        val nextUrl = photoManager.getPhotoUrl(nextIndex) ?: return

        views?.let { views ->
            isTransitioning = true
            try {
                withContext(NonCancellable) {
                    // Load into overlay view first
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
                                    // Successfully loaded, start transition
                                    views.overlayView.alpha = 0f
                                    views.overlayView.visibility = View.VISIBLE

                                    views.overlayView.animate()
                                        .alpha(1f)
                                        .setDuration(transitionDuration)
                                        .withEndAction {
                                            // Copy to primary view and reset overlay
                                            views.primaryView.setImageDrawable(resource)
                                            views.overlayView.alpha = 0f
                                            views.overlayView.visibility = View.INVISIBLE
                                            isTransitioning = false
                                            currentPhotoIndex = nextIndex
                                        }
                                        .start()
                                    return false
                                }
                            })
                            .into(views.overlayView)

                        // Hide loading state if still showing
                        hideLoadingOverlay()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photo", e)
                isTransitioning = false
            }
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
                // Use Glide to load the default photo
                Glide.with(context)
                    .load(R.drawable.default_photo)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .into(views.primaryView)

                if (photoManager.getPhotoCount() == 0) {
                    // Only show the message if there are no photos
                    views.loadingMessage?.apply {
                        text = context.getString(R.string.no_photo_source)
                        visibility = View.VISIBLE
                    }
                } else {
                    // Hide all messages if photos are available
                    views.loadingMessage?.visibility = View.GONE
                    views.overlayMessageContainer?.visibility = View.GONE
                }

                // Always hide the loading indicator
                views.loadingIndicator?.visibility = View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Error loading default photo", e)
                views.loadingMessage?.apply {
                    text = context.getString(R.string.no_photos_message)
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun hideAllMessages() {
        views?.apply {
            loadingMessage?.visibility = View.GONE
            loadingIndicator?.visibility = View.GONE
            overlayMessageContainer?.visibility = View.GONE
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

    fun cleanup() {
        Log.d(TAG, "Cleaning up PhotoDisplayManager")
        managerJob.cancel()
        stopPhotoDisplay()
        timeUpdateJob?.cancel()
        timeUpdateJob = null
        views = null
        lifecycleScope = null
        _photoLoadingState.value = LoadingState.IDLE
    }
}