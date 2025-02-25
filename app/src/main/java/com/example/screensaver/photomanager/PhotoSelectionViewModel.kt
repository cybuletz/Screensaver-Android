package com.example.screensaver.photomanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.example.screensaver.lock.LockScreenPhotoManager.MediaItem

@HiltViewModel
class PhotoSelectionViewModel @Inject constructor(
    private val photoCollectionManager: PhotoCollectionManager,
    private val lockScreenPhotoManager: LockScreenPhotoManager,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _photos = MutableStateFlow<List<PhotoWithSelection>>(emptyList())
    val photos: StateFlow<List<PhotoWithSelection>> = _photos

    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    init {
        loadPhotos()
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            try {
                val localPhotos = lockScreenPhotoManager.getLocalPhotos()
                val pickedUris = preferences.getPickedUris()

                val photosList = mutableListOf<PhotoWithSelection>()

                localPhotos.forEach { photo ->
                    photosList.add(
                        PhotoWithSelection(
                            photo = photo,
                            source = PhotoCollectionManager.PhotoSource.LOCAL,
                            isSelected = false
                        )
                    )
                }

                pickedUris.forEach { uri ->
                    photosList.add(
                        PhotoWithSelection(
                            photo = MediaItem(
                                id = uri,
                                albumId = "picked_photos",
                                baseUrl = uri,
                                mimeType = "image/*",
                                width = 0,
                                height = 0
                            ),
                            source = PhotoCollectionManager.PhotoSource.GOOGLE_PHOTOS_PICKER,
                            isSelected = false
                        )
                    )
                }

                _photos.value = photosList
            } catch (e: Exception) {
                _events.emit(Event.ShowError(e.message ?: "Error loading photos"))
            }
        }
    }

    fun togglePhotoSelection(photo: PhotoWithSelection, isSelected: Boolean) {
        val currentList = _photos.value.toMutableList()
        val index = currentList.indexOfFirst { it.photo.id == photo.photo.id }
        if (index != -1) {
            currentList[index] = photo.copy(isSelected = isSelected)
            _photos.value = currentList
            _selectedCount.value = currentList.count { it.isSelected }
        }
    }

    fun clearSelection() {
        _photos.value = _photos.value.map { it.copy(isSelected = false) }
        _selectedCount.value = 0
    }

    suspend fun getAvailableCollections(): List<PhotoCollectionManager.Collection> {
        return photoCollectionManager.getCollections()
    }

    fun addSelectedPhotosToCollection(collection: PhotoCollectionManager.Collection) {
        viewModelScope.launch {
            try {
                val selectedPhotos = _photos.value.filter { it.isSelected }
                val photosBySource = selectedPhotos.groupBy { it.source }

                photosBySource.forEach { (source, photos) ->
                    photoCollectionManager.addPhotosToCollection(
                        collection.id,
                        photos.map { it.photo.baseUrl },
                        source
                    )
                }

                _events.emit(Event.PhotosAddedToCollection(selectedPhotos.size, collection.name))
            } catch (e: Exception) {
                _events.emit(Event.ShowError(e.message ?: "Error adding photos to collection"))
            }
        }
    }

    fun createCollectionAndAddPhotos(name: String, description: String?) {
        if (name.isBlank()) {
            viewModelScope.launch {
                _events.emit(Event.ShowError("Collection name cannot be empty"))
            }
            return
        }

        viewModelScope.launch {
            try {
                val collection = photoCollectionManager.createCollection(name, description)
                addSelectedPhotosToCollection(collection)
            } catch (e: Exception) {
                _events.emit(Event.ShowError(e.message ?: "Error creating collection"))
            }
        }
    }

    data class PhotoWithSelection(
        val photo: MediaItem,
        val source: PhotoCollectionManager.PhotoSource,
        val isSelected: Boolean
    )

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class PhotosAddedToCollection(val count: Int, val collectionName: String) : Event()
    }
}