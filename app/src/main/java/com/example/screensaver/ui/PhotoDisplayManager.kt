package com.example.screensaver.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import com.example.screensaver.lock.PhotoManager
import com.example.screensaver.models.LoadingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import android.util.Log
import android.graphics.drawable.Drawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class PhotoDisplayManager @Inject constructor(
    private val photoManager: PhotoManager,
    private val context: Context
) {
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.Main + managerJob)

    private val _photoLoadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val photoLoadingState: StateFlow<LoadingState> = _photoLoadingState

    companion object {
        private const val TAG = "PhotoDisplayManager"
    }

    data class Views(
        val primaryView: ImageView,
        val overlayView: ImageView,
        val clockView: TextView?,
        val dateView: TextView?,
        val locationView: TextView?,
        val loadingIndicator: View?,
        val container: View
    )

    private var views: Views? = null
    private var lifecycleScope: LifecycleCoroutineScope? = null
    private var currentPhotoIndex = 0
    private var isTransitioning = false
    private var displayJob: Job? = null
    private var timeUpdateJob: Job? = null

    // Settings
    private var transitionDuration: Long = 1000 // 1 second
    private var photoInterval: Long = 10000 // 10 seconds
    private var showClock: Boolean = false
    private var showDate: Boolean = false
    private var showLocation: Boolean = false
    private var isRandomOrder: Boolean = false

    fun initialize(views: Views, scope: LifecycleCoroutineScope) {
        Log.d(TAG, "Initializing PhotoDisplayManager")
        this.views = views
        this.lifecycleScope = scope
        views.overlayView.alpha = 0f

        val photoCount = photoManager.getPhotoCount()
        Log.d(TAG, "Initial photo count: $photoCount")

        managerScope.launch {
            try {
                _photoLoadingState.value = LoadingState.IDLE
                photoManager.loadingState.collect { state ->
                    Log.d(TAG, "Photo manager loading state changed to: $state")
                    views.loadingIndicator?.isVisible = state == PhotoManager.LoadingState.LOADING
                    when (state) {
                        PhotoManager.LoadingState.LOADING -> {
                            _photoLoadingState.value = LoadingState.LOADING
                        }
                        PhotoManager.LoadingState.SUCCESS -> {
                            _photoLoadingState.value = LoadingState.SUCCESS
                            if (photoManager.getPhotoCount() > 0 && displayJob == null) {
                                withContext(NonCancellable) {
                                    startPhotoDisplay()
                                }
                            }
                        }
                        PhotoManager.LoadingState.ERROR -> {
                            _photoLoadingState.value = LoadingState.ERROR("Failed to load photos")
                        }
                        PhotoManager.LoadingState.IDLE -> {
                            _photoLoadingState.value = LoadingState.IDLE
                            if (photoManager.getPhotoCount() == 0) {
                                photoManager.loadPhotos()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.d(TAG, "Photo manager state collection cancelled")
                    return@launch
                }
                Log.e(TAG, "Error in photo manager state collection", e)
            }
        }
        updateTimeDisplayVisibility()
        Log.d(TAG, "PhotoDisplayManager initialized")
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
        Log.d(TAG, "Settings updated - Interval: $photoInterval, Clock: $showClock, Date: $showDate")
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

    fun startPhotoDisplay() {
        Log.d(TAG, "Starting photo display with ${photoManager.getPhotoCount()} photos")
        if (photoManager.getPhotoCount() == 0) {
            Log.w(TAG, "No photos available to display, attempting to load")
            lifecycleScope?.launch {
                _photoLoadingState.value = LoadingState.LOADING
                photoManager.loadPhotos()
                delay(1000) // Wait a bit for photos to load
                if (photoManager.getPhotoCount() > 0) {
                    startPhotoDisplay() // Retry after loading
                } else {
                    _photoLoadingState.value = LoadingState.ERROR("No photos available")
                }
            }
            return
        }

        try {
            _photoLoadingState.value = LoadingState.LOADING
            displayJob?.cancel()
            displayJob = lifecycleScope?.launch {
                currentPhotoIndex = -1  // This ensures first photo is index 0
                loadAndDisplayPhoto()

                while (true) {
                    delay(photoInterval)
                    val currentCount = photoManager.getPhotoCount()
                    if (currentCount > 0) {
                        loadAndDisplayPhoto()
                    } else {
                        Log.w(TAG, "No photos available during display cycle, attempting reload")
                        photoManager.loadPhotos()
                        if (photoManager.getPhotoCount() > 0) {
                            loadAndDisplayPhoto()
                        }
                    }
                }
            }
            Log.d(TAG, "Photo display started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting photo display", e)
            _photoLoadingState.value = LoadingState.ERROR("Failed to start display: ${e.message}")
        }
    }

    fun stopPhotoDisplay() {
        Log.d(TAG, "Stopping photo display")
        displayJob?.cancel()
        displayJob = null
        _photoLoadingState.value = LoadingState.IDLE
    }

    private suspend fun loadAndDisplayPhoto() {
        if (isTransitioning) {
            Log.d(TAG, "Photo transition in progress, skipping")
            return
        }

        val photoCount = photoManager.getPhotoCount()
        Log.d(TAG, "Attempting to load photo. Total photos available: $photoCount")

        if (photoCount == 0) {
            Log.e(TAG, "No photos available")
            _photoLoadingState.value = LoadingState.ERROR("No photos available")
            photoManager.loadPhotos()
            delay(1000)
            if (photoManager.getPhotoCount() > 0) {
                Log.d(TAG, "Photos loaded successfully after retry")
                loadAndDisplayPhoto()
            }
            return
        }

        val nextIndex = if (isRandomOrder) {
            (0 until photoCount).random()
        } else {
            (currentPhotoIndex + 1) % photoCount
        }

        Log.d(TAG, "Loading photo $nextIndex of $photoCount")
        val nextUrl = photoManager.getPhotoUrl(nextIndex)
        if (nextUrl == null) {
            Log.e(TAG, "Failed to get photo URL for index: $nextIndex")
            _photoLoadingState.value = LoadingState.ERROR("Failed to get photo URL")
            return
        }

        Log.d(TAG, "Got photo URL: $nextUrl")

        views?.let { views ->
            isTransitioning = true
            try {
                withContext(NonCancellable) {
                    Glide.with(context)
                        .load(nextUrl)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                Log.e(TAG, "Failed to load photo from URL: $nextUrl", e)
                                isTransitioning = false
                                _photoLoadingState.value = LoadingState.ERROR("Failed to load photo")
                                return false
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                model: Any,
                                target: Target<Drawable>,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                Log.d(TAG, "Photo loaded successfully from URL: $nextUrl")
                                return false
                            }
                        })
                        .error(android.R.drawable.ic_dialog_alert)
                        .into(views.overlayView)

                    views.overlayView.animate()
                        .alpha(1f)
                        .setDuration(transitionDuration)
                        .withEndAction {
                            Glide.with(context)
                                .load(nextUrl)
                                .error(android.R.drawable.ic_dialog_alert)
                                .into(views.primaryView)
                            views.overlayView.alpha = 0f
                            currentPhotoIndex = nextIndex
                            isTransitioning = false
                            _photoLoadingState.value = LoadingState.SUCCESS

                            lifecycleScope?.launch {
                                val nextPreloadIndex = (nextIndex + 1) % photoCount
                                photoManager.preloadNextPhoto(nextPreloadIndex)
                            }
                        }
                        .start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during photo transition", e)
                isTransitioning = false
                _photoLoadingState.value = LoadingState.ERROR("Transition failed: ${e.message}")
            }
        }
    }

    private fun startTimeUpdates() {
        timeUpdateJob?.cancel()
        timeUpdateJob = lifecycleScope?.launch {
            while (true) {
                updateTimeDisplay()
                delay(1000)
            }
        }
    }

    private fun updateTimeDisplay() {
        val now = System.currentTimeMillis()
        views?.let { views ->
            if (showClock) {
                views.clockView?.text = SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(Date(now))
            }
            if (showDate) {
                views.dateView?.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
                    .format(Date(now))
            }
        }
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