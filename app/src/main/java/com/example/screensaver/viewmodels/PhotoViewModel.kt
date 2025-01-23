package com.example.screensaver.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem
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
import java.util.Date
import com.example.screensaver.utils.OnPhotoLoadListener

@HiltViewModel
class PhotoViewModel @Inject constructor(
    application: Application,
    private val photosManager: GooglePhotosManager
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

    private val _selectedAlbums = MutableLiveData<List<Album>>()
    val selectedAlbums: LiveData<List<Album>> = _selectedAlbums

    // UI State for overlay information
    private val _showOverlay = MutableLiveData(true)
    val showOverlay: LiveData<Boolean> = _showOverlay

    private val _showClock = MutableLiveData(true)
    val showClock: LiveData<Boolean> = _showClock

    private val _showDate = MutableLiveData(true)
    val showDate: LiveData<Boolean> = _showDate

    private val _showLocation = MutableLiveData(true)
    val showLocation: LiveData<Boolean> = _showLocation

    private val _photoQuality = MutableLiveData<Int>()
    val photoQuality: LiveData<Int> = _photoQuality

    private val _currentTime = MutableLiveData<Date>()
    val currentTime: LiveData<Date> = _currentTime

    private val _currentDate = MutableLiveData<Date>()
    val currentDate: LiveData<Date> = _currentDate

    private var timeUpdateJob: Job? = null
    private var mediaItems = mutableListOf<MediaItem>()
    private var currentIndex = -1
    private var photoChangeJob: Job? = null
    private val isActive = AtomicBoolean(false)
    private var retryCount = 0

    enum class LoadingState {
        IDLE, LOADING, SUCCESS, ERROR
    }

    companion object {
        const val QUALITY_LOW = 1
        const val QUALITY_MEDIUM = 2
        const val QUALITY_HIGH = 3

        private const val PHOTO_CHANGE_INTERVAL = 30_000L
        private const val RETRY_DELAY = 5_000L
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    init {
        _photoQuality.value = QUALITY_HIGH
        updateDateTime()
        startTimeUpdates()
    }


    override fun onPhotoLoadComplete(success: Boolean) {
        _isLoading.value = false
        if (!success) {
            _hasError.value = true
            _errorMessage.value = "Failed to load photo"
        }
    }

    private fun updateDateTime() {
        val now = Date()
        _currentTime.value = now
        _currentDate.value = now
    }

    private fun startTimeUpdates() {
        timeUpdateJob = viewModelScope.launch {
            while (isActive.get()) {
                updateDateTime()
                delay(1000) // Update every second
            }
        }
    }

    fun initialize(albums: List<Album>) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _hasError.value = false
                _loadingState.value = LoadingState.LOADING
                _selectedAlbums.value = albums
                loadMediaItems()
                startPhotoChanging()
                _loadingState.value = LoadingState.SUCCESS
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
            _selectedAlbums.value?.forEach { album ->
                val items = photosManager.getMediaItems(album.id)
                mediaItems.addAll(items)
            }
            mediaItems.shuffle()
            _hasError.value = false
        } catch (e: Exception) {
            handleError("Failed to load media items: ${e.message}", e)
        } finally {
            _isLoading.value = false
        }
    }

    fun setPhotoQuality(quality: Int) {
        _photoQuality.value = quality
    }

    private fun startPhotoChanging() {
        if (isActive.compareAndSet(false, true)) {
            photoChangeJob = viewModelScope.launch {
                try {
                    while (isActive.get()) {
                        showNextPhoto()
                        delay(PHOTO_CHANGE_INTERVAL)
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

    private fun retryLoadingWithBackoff() {
        viewModelScope.launch {
            if (retryCount < MAX_RETRY_ATTEMPTS) {
                retryCount++
                _isLoading.value = true
                delay(RETRY_DELAY * retryCount)
                showNextPhoto()
            } else {
                handleError("Failed to load photo after $MAX_RETRY_ATTEMPTS attempts", null)
                retryCount = 0
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

    fun toggleOverlay() {
        _showOverlay.value = _showOverlay.value?.not()
    }




    fun stop() {
        isActive.set(false)
        photoChangeJob?.cancel()
        timeUpdateJob?.cancel()
        photoChangeJob = null
        timeUpdateJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    override fun onRetry() {
        _hasError.value = false
        showNextPhoto() // Change from loadPhotos() to showNextPhoto()
    }
}