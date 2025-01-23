package com.example.screensaver.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.screensaver.managers.GooglePhotosManager
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem
import com.example.screensaver.utils.SingleLiveEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * ViewModel for managing photo-related data and operations.
 * Handles photo loading, caching, and display logic for both screensaver and lock screen.
 */
class PhotoViewModel(
    application: Application,
    private val photosManager: GooglePhotosManager
) : AndroidViewModel(application) {

    private val _currentPhoto = MutableLiveData<MediaItem>()
    val currentPhoto: LiveData<MediaItem> = _currentPhoto

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState

    private val _error = SingleLiveEvent<String>()
    val error: LiveData<String> = _error

    private val _selectedAlbums = MutableLiveData<List<Album>>()
    val selectedAlbums: LiveData<List<Album>> = _selectedAlbums

    private var mediaItems = mutableListOf<MediaItem>()
    private var currentIndex = -1
    private var photoChangeJob: Job? = null
    private val isActive = AtomicBoolean(false)

    enum class LoadingState {
        IDLE, LOADING, SUCCESS, ERROR
    }

    companion object {
        private const val PHOTO_CHANGE_INTERVAL = 30_000L // 30 seconds
        private const val RETRY_DELAY = 5_000L // 5 seconds
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    /**
     * Initializes the photo display with selected albums
     */
    fun initialize(albums: List<Album>) {
        viewModelScope.launch {
            try {
                _loadingState.value = LoadingState.LOADING
                _selectedAlbums.value = albums
                loadMediaItems()
                startPhotoChanging()
                _loadingState.value = LoadingState.SUCCESS
            } catch (e: Exception) {
                handleError("Failed to initialize photos", e)
            }
        }
    }

    /**
     * Loads media items from selected albums
     */
    private suspend fun loadMediaItems() {
        try {
            mediaItems.clear()
            _selectedAlbums.value?.forEach { album ->
                val items = photosManager.getMediaItems(album.id)
                mediaItems.addAll(items)
            }
            mediaItems.shuffle()
        } catch (e: Exception) {
            handleError("Failed to load media items", e)
        }
    }

    /**
     * Starts the automatic photo changing process
     */
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

    /**
     * Shows the next photo in the sequence
     */
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
                }
            } catch (e: Exception) {
                handleError("Failed to show next photo", e)
            }
        }
    }

    /**
     * Shows the previous photo in the sequence
     */
    fun showPreviousPhoto() {
        viewModelScope.launch {
            try {
                if (mediaItems.isNotEmpty()) {
                    currentIndex = if (currentIndex <= 0) mediaItems.size - 1 else currentIndex - 1
                    _currentPhoto.value = mediaItems[currentIndex].apply {
                        updateLoadState(MediaItem.LoadState.LOADING)
                    }
                }
            } catch (e: Exception) {
                handleError("Failed to show previous photo", e)
            }
        }
    }

    /**
     * Shows a random photo from the collection
     */
    fun showRandomPhoto() {
        viewModelScope.launch {
            try {
                if (mediaItems.isNotEmpty()) {
                    currentIndex = Random.nextInt(mediaItems.size)
                    _currentPhoto.value = mediaItems[currentIndex].apply {
                        updateLoadState(MediaItem.LoadState.LOADING)
                    }
                }
            } catch (e: Exception) {
                handleError("Failed to show random photo", e)
            }
        }
    }

    /**
     * Handles photo loading completion
     */
    fun onPhotoLoaded(mediaItem: MediaItem) {
        mediaItem.updateLoadState(MediaItem.LoadState.LOADED)
    }

    /**
     * Handles photo loading errors
     */
    fun onPhotoLoadError(mediaItem: MediaItem) {
        mediaItem.updateLoadState(MediaItem.LoadState.ERROR)
        retryLoadingWithBackoff(mediaItem)
    }

    /**
     * Retries loading a photo with exponential backoff
     */
    private fun retryLoadingWithBackoff(mediaItem: MediaItem, attempt: Int = 0) {
        viewModelScope.launch {
            if (attempt < MAX_RETRY_ATTEMPTS) {
                delay(RETRY_DELAY * (attempt + 1))
                mediaItem.updateLoadState(MediaItem.LoadState.LOADING)
                _currentPhoto.value = mediaItem
            } else {
                handleError("Failed to load photo after $MAX_RETRY_ATTEMPTS attempts", null)
                showNextPhoto()
            }
        }
    }

    /**
     * Refreshes the media items from the selected albums
     */
    fun refresh() {
        viewModelScope.launch {
            try {
                _loadingState.value = LoadingState.LOADING
                loadMediaItems()
                showNextPhoto()
                _loadingState.value = LoadingState.SUCCESS
            } catch (e: Exception) {
                handleError("Failed to refresh photos", e)
            }
        }
    }

    private fun handleError(message: String, error: Exception?) {
        _loadingState.value = LoadingState.ERROR
        _error.value = message
        error?.let {
            // Log the error or handle it according to your needs
        }
    }

    /**
     * Stops photo changing and cleans up resources
     */
    fun stop() {
        isActive.set(false)
        photoChangeJob?.cancel()
        photoChangeJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}