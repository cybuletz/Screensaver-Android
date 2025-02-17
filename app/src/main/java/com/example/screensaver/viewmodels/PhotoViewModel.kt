package com.example.screensaver.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.screensaver.shared.GooglePhotosManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import com.example.screensaver.utils.RetryActionListener
import com.example.screensaver.utils.OnPhotoLoadListener
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem
import com.example.screensaver.models.MediaItemModel
import com.example.screensaver.utils.AppPreferences

@HiltViewModel
class PhotoViewModel @Inject constructor(
    application: Application,
    private val photosManager: GooglePhotosManager,
    private val preferences: AppPreferences
) : AndroidViewModel(application), RetryActionListener, OnPhotoLoadListener {

    private val _currentPhoto = MutableLiveData<MediaItem>()
    val currentPhoto: LiveData<MediaItem> = _currentPhoto

    private val _nextPhoto = MutableLiveData<MediaItem>()
    val nextPhoto: LiveData<MediaItem> = _nextPhoto

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState

    private val _hasError = MutableLiveData<Boolean>()
    val hasError: LiveData<Boolean> = _hasError

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _selectedAlbums = MutableStateFlow<List<Album>>(emptyList())
    val selectedAlbums: StateFlow<List<Album>> = _selectedAlbums

    // UI State
    private val _showOverlay = MutableLiveData(true)
    val showOverlay: LiveData<Boolean> = _showOverlay

    private val _showClock = MutableLiveData(true)
    val showClock: LiveData<Boolean> = _showClock

    private val _showDate = MutableLiveData(true)
    val showDate: LiveData<Boolean> = _showDate

    private val _showLocation = MutableLiveData(false)
    val showLocation: LiveData<Boolean> = _showLocation

    private val _photoQuality = MutableLiveData(QUALITY_HIGH)
    val photoQuality: LiveData<Int> = _photoQuality

    private val _currentDateTime = MutableLiveData<LocalDateTime>()

    private val _currentTime = MutableLiveData<String>()
    val currentTime: LiveData<String> = _currentTime

    private val _currentDate = MutableLiveData<String>()
    val currentDate: LiveData<String> = _currentDate

    private val _transitionDuration = MutableStateFlow(preferences.getTransitionDuration())

    private val _enableTransitions = MutableStateFlow(preferences.getEnableTransitions())

    private val _darkMode = MutableStateFlow(preferences.getDarkMode())

    private var timeUpdateJob: Job? = null
    private var photoChangeJob: Job? = null
    private val mediaItems = mutableListOf<MediaItem>()
    private var currentIndex = -1
    private val isActive = AtomicBoolean(false)
    private var retryCount = 0
    private var isPreviewMode = false

    enum class LoadingState {
        IDLE, LOADING, SUCCESS, ERROR
    }

    data class Settings(
        val transitionDuration: Int,
        val photoQuality: Int,
        val randomOrder: Boolean,
        val showClock: Boolean,
        val showLocation: Boolean,
        val showDate: Boolean,
        val enableTransitions: Boolean,
        val darkMode: Boolean
    )

    companion object {
        const val QUALITY_LOW = 1
        const val QUALITY_MEDIUM = 2
        const val QUALITY_HIGH = 3

        private const val PHOTO_CHANGE_INTERVAL = 30_000L
        private const val RETRY_DELAY = 5_000L
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    init {
        updateDateTime()
        startTimeUpdates()
    }

    fun getCurrentSettings() = Settings(
        transitionDuration = preferences.getTransitionDuration(),
        photoQuality = preferences.getPhotoQuality(),
        randomOrder = preferences.getRandomOrder(),
        showClock = preferences.isShowClock(),
        showLocation = preferences.getShowLocation(),
        showDate = preferences.getShowDate(),
        enableTransitions = preferences.getEnableTransitions(),
        darkMode = preferences.getDarkMode()
    )

    fun updatePreviewSettings(
        transitionDuration: Int,
        photoQuality: Int,
        randomOrder: Boolean,
        showClock: Boolean,
        showLocation: Boolean,
        showDate: Boolean,
        enableTransitions: Boolean,
        darkMode: Boolean
    ) {
        preferences.apply {
            setTransitionDuration(transitionDuration)
            setPhotoQuality(photoQuality)
            setRandomOrder(randomOrder)
            setShowClock(showClock)
            setShowLocation(showLocation)
            setShowDate(showDate)
            setEnableTransitions(enableTransitions)
            setDarkMode(darkMode)
        }

        _transitionDuration.value = transitionDuration
        _photoQuality.value = photoQuality
        _showClock.value = showClock
        _showLocation.value = showLocation
        _showDate.value = showDate
        _enableTransitions.value = enableTransitions
        _darkMode.value = darkMode

        if (isActive.get() && !isPreviewMode) {
            photoChangeJob?.cancel()
            startPhotoChanging()
        }
    }

    override fun onPhotoLoadComplete(success: Boolean) {
        _isLoading.value = false
        if (!success) {
            _hasError.value = true
            _errorMessage.value = "Failed to load photo"
        }
    }

    private fun updateDateTime() {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        _currentDateTime.value = now
        _currentTime.value = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        _currentDate.value = now.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
    }

    private fun startTimeUpdates() {
        timeUpdateJob = viewModelScope.launch {
            while (isActive.get()) {
                updateDateTime()
                delay(1000) // Update every second
            }
        }
    }

    fun initialize(albums: List<Album>, isPreview: Boolean = false) {
        viewModelScope.launch {
            try {
                isPreviewMode = isPreview
                _isLoading.value = true
                _hasError.value = false
                _loadingState.value = LoadingState.LOADING
                _selectedAlbums.value = albums

                if (photosManager.initialize()) {
                    loadMediaItems()
                    if (!isPreviewMode) {
                        startPhotoChanging()
                    }
                    _loadingState.value = LoadingState.SUCCESS
                } else {
                    handleError("Failed to initialize photo manager", null)
                }
            } catch (e: Exception) {
                handleError("Failed to initialize photos: ${e.message}", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadMediaItems() {
        try {
            _isLoading.value = true
            mediaItems.clear()

            // Load photos once and then filter for all albums
            val allPhotos = photosManager.loadPhotos() ?: return
            val selectedAlbumIds = _selectedAlbums.value.map { it.id }.toSet()

            mediaItems.addAll(allPhotos.filter { it.albumId in selectedAlbumIds })

            if (mediaItems.isNotEmpty()) {
                mediaItems.shuffle()
                _hasError.value = false
            } else {
                handleError("No photos found in selected albums", null)
            }
        } catch (e: Exception) {
            handleError("Failed to load media items: ${e.message}", e)
        } finally {
            _isLoading.value = false
        }
    }

    private fun startPhotoChanging() {
        if (isPreviewMode) return // Don't auto-change photos in preview mode

        if (isActive.compareAndSet(false, true)) {
            photoChangeJob = viewModelScope.launch {
                try {
                    while (isActive.get()) {
                        showNextPhoto()
                        delay(preferences.getTransitionDuration() * 1000L)
                    }
                } catch (e: CancellationException) {
                    // Normal cancellation, ignore
                } catch (e: Exception) {
                    handleError("Error during photo changing", e)
                }
            }
        }
    }

    fun showNextPhoto() {
        viewModelScope.launch {
            try {
                if (mediaItems.isEmpty()) {
                    loadMediaItems()
                }

                if (mediaItems.isNotEmpty()) {
                    currentIndex = (currentIndex + 1) % mediaItems.size
                    _currentPhoto.value = mediaItems[currentIndex].apply {
                        updateLoadState(MediaItem.LoadState.LOADING)
                    }

                    // Preload next photo
                    val nextIndex = (currentIndex + 1) % mediaItems.size
                    _nextPhoto.value = mediaItems[nextIndex]
                }
                _hasError.value = false
            } catch (e: Exception) {
                handleError("Failed to show next photo", e)
            }
        }
    }

    private fun handleError(message: String, error: Exception?) {
        _loadingState.value = LoadingState.ERROR
        _hasError.value = true
        _errorMessage.value = message
        _isLoading.value = false
        error?.printStackTrace()
    }

    fun stop() {
        isActive.set(false)
        photoChangeJob?.cancel()
        if (!isPreviewMode) {
            timeUpdateJob?.cancel()
        }
        photoChangeJob = null
        if (!isPreviewMode) {
            timeUpdateJob = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    override fun onRetry() {
        _hasError.value = false
        retryCount = 0
        showNextPhoto()
    }
}