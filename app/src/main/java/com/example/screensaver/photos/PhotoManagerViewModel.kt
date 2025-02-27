package com.example.screensaver.photos

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import com.example.screensaver.models.MediaItem
import java.util.UUID
import androidx.lifecycle.viewModelScope

@HiltViewModel
class PhotoManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lockScreenPhotoManager: LockScreenPhotoManager,
    private val preferences: AppPreferences
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<PhotoManagerState>(PhotoManagerState.Idle)
    private val _photos = MutableStateFlow<List<ManagedPhoto>>(emptyList())
    private val _virtualAlbums = MutableStateFlow<List<VirtualAlbum>>(emptyList())
    private val _selectedCount = MutableStateFlow(0)

    val state: StateFlow<PhotoManagerState> = _state.asStateFlow()
    val photos: StateFlow<List<ManagedPhoto>> = _photos.asStateFlow()
    val virtualAlbums: StateFlow<List<VirtualAlbum>> = _virtualAlbums.asStateFlow()
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    companion object {
        private const val TAG = "PhotoManagerViewModel"
    }

    data class ManagedPhoto(
        val id: String,
        val uri: String,
        val sourceType: PhotoSourceType,
        val albumId: String,
        val isSelected: Boolean = false,
        val dateAdded: Long = System.currentTimeMillis()
    )

    enum class PhotoSourceType {
        LOCAL,
        GOOGLE_PHOTOS,
        VIRTUAL_ALBUM
    }

    enum class SortOption {
        DATE_DESC,
        DATE_ASC,
        SOURCE
    }

    data class VirtualAlbum(
        val id: String,
        val name: String,
        val photoUris: List<String>,
        val dateCreated: Long = System.currentTimeMillis(),
        val isSelected: Boolean = false
    )

    sealed class PhotoManagerState {
        object Idle : PhotoManagerState()
        object Loading : PhotoManagerState()
        data class Success(val message: String) : PhotoManagerState()
        data class Error(val message: String) : PhotoManagerState()
    }

    init {
        loadInitialState()
    }

    private fun loadInitialState() {
        scope.launch {
            try {
                _state.value = PhotoManagerState.Loading

                // Load virtual albums from preferences
                val savedAlbums = preferences.getString("virtual_albums", "")
                if (savedAlbums.isNotEmpty()) {
                    // Parse saved albums JSON
                }

                // Load all photos
                val localPhotos = lockScreenPhotoManager.loadPhotos()
                val managedPhotos = localPhotos?.map { mediaItem ->
                    ManagedPhoto(
                        id = mediaItem.id,
                        uri = mediaItem.baseUrl,
                        sourceType = determineSourceType(mediaItem),
                        albumId = mediaItem.albumId
                    )
                } ?: emptyList()

                _photos.value = managedPhotos
                _state.value = PhotoManagerState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial state", e)
                _state.value = PhotoManagerState.Error("Failed to load photos")
            }
        }
    }

    private fun determineSourceType(mediaItem: MediaItem): PhotoSourceType {
        return when {
            mediaItem.baseUrl.contains("googleusercontent") -> PhotoSourceType.GOOGLE_PHOTOS
            else -> PhotoSourceType.LOCAL
        }
    }

    fun togglePhotoSelection(photoId: String) {
        val currentPhotos = _photos.value.toMutableList()
        val photoIndex = currentPhotos.indexOfFirst { it.id == photoId }

        if (photoIndex != -1) {
            currentPhotos[photoIndex] = currentPhotos[photoIndex].copy(
                isSelected = !currentPhotos[photoIndex].isSelected
            )
            _photos.value = currentPhotos
            _selectedCount.value = currentPhotos.count { it.isSelected }
        }
    }

    fun createVirtualAlbum(name: String) {
        scope.launch {
            try {
                _state.value = PhotoManagerState.Loading

                val selectedPhotos = _photos.value.filter { it.isSelected }
                if (selectedPhotos.isEmpty()) {
                    _state.value = PhotoManagerState.Error("No photos selected")
                    return@launch
                }

                val newAlbum = VirtualAlbum(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    photoUris = selectedPhotos.map { it.uri }
                )

                val currentAlbums = _virtualAlbums.value.toMutableList()
                currentAlbums.add(newAlbum)
                _virtualAlbums.value = currentAlbums

                // Save to preferences
                saveVirtualAlbums(currentAlbums)

                // Clear selection
                clearSelection()
                _state.value = PhotoManagerState.Success("Album created successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating virtual album", e)
                _state.value = PhotoManagerState.Error("Failed to create album")
            }
        }
    }

    fun deleteVirtualAlbum(albumId: String) {
        scope.launch {
            try {
                _state.value = PhotoManagerState.Loading

                val currentAlbums = _virtualAlbums.value.toMutableList()
                val albumIndex = currentAlbums.indexOfFirst { it.id == albumId }

                if (albumIndex != -1) {
                    currentAlbums.removeAt(albumIndex)
                    _virtualAlbums.value = currentAlbums
                    saveVirtualAlbums(currentAlbums)
                    _state.value = PhotoManagerState.Success("Album deleted")
                } else {
                    _state.value = PhotoManagerState.Error("Album not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting virtual album", e)
                _state.value = PhotoManagerState.Error("Failed to delete album")
            }
        }
    }

    fun sortPhotos(option: SortOption) {
        val sorted = when (option) {
            SortOption.DATE_DESC -> _photos.value.sortedByDescending { it.dateAdded }
            SortOption.DATE_ASC -> _photos.value.sortedBy { it.dateAdded }
            SortOption.SOURCE -> _photos.value.sortedBy { it.sourceType.name }
        }
        _photos.value = sorted
    }

    private fun clearSelection() {
        val currentPhotos = _photos.value.map { it.copy(isSelected = false) }
        _photos.value = currentPhotos
        _selectedCount.value = 0
    }

    private suspend fun saveVirtualAlbums(albums: List<VirtualAlbum>) {
        withContext(Dispatchers.IO) {
            try {
                // Save albums to preferences as JSON
                preferences.setString("virtual_albums", albums.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error saving virtual albums", e)
            }
        }
    }

    fun toggleVirtualAlbumSelection(albumId: String) {
        viewModelScope.launch {
            try {
                lockScreenPhotoManager.toggleVirtualAlbumSelection(albumId)
                _state.value = PhotoManagerState.Success("Album selection updated")
            } catch (e: Exception) {
                _state.value = PhotoManagerState.Error("Failed to update album selection")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        scope.launch {
            saveVirtualAlbums(_virtualAlbums.value)
        }
    }
}