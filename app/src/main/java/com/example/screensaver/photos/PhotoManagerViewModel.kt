package com.example.screensaver.photos

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.PhotoRepository
import com.example.screensaver.models.MediaItem
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject


@HiltViewModel
class PhotoManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val googlePhotosManager: GooglePhotosManager,
    private val preferences: AppPreferences,
    private val secureStorage: SecureStorage,
    private val photoRepository: PhotoRepository
) : ViewModel() {

    private val _state = MutableStateFlow<PhotoManagerState>(PhotoManagerState.Idle)
    private val _photos = MutableStateFlow<List<ManagedPhoto>>(emptyList())
    private val _virtualAlbums = MutableStateFlow<List<VirtualAlbum>>(emptyList())
    private val _selectedCount = MutableStateFlow(0)

    val state: StateFlow<PhotoManagerState> = _state.asStateFlow()
    val photos: StateFlow<List<ManagedPhoto>> = _photos.asStateFlow()
    val virtualAlbums: StateFlow<List<VirtualAlbum>> = _virtualAlbums.asStateFlow()
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    data class ManagedPhoto(
        val id: String,
        val uri: String,
        val sourceType: PhotoSourceType,
        val albumId: String,
        val dateAdded: Long,
        val isSelected: Boolean = false
    )

    enum class PhotoSourceType {
        LOCAL_PICKED,
        LOCAL_ALBUM,
        GOOGLE_PHOTOS,
        VIRTUAL_ALBUM
    }

    sealed class PhotoManagerState {
        object Idle : PhotoManagerState()
        object Loading : PhotoManagerState()
        object Empty : PhotoManagerState()
        data class Error(val message: String) : PhotoManagerState()
        data class Success(val message: String) : PhotoManagerState()
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

    companion object {
        private const val TAG = "PhotoManagerViewModel"
    }

    init {
        viewModelScope.launch {
            try {
                // Load saved albums from preferences
                val savedAlbums = preferences.getString("virtual_albums", "[]")
                val jsonArray = JSONArray(savedAlbums)
                val albums = mutableListOf<VirtualAlbum>()

                for (i in 0 until jsonArray.length()) {
                    val albumJson = jsonArray.getJSONObject(i)
                    val photoUrisArray = albumJson.getJSONArray("photoUris")
                    val photoUris = mutableListOf<String>()
                    for (j in 0 until photoUrisArray.length()) {
                        photoUris.add(photoUrisArray.getString(j))
                    }

                    albums.add(VirtualAlbum(
                        id = albumJson.getString("id"),
                        name = albumJson.getString("name"),
                        photoUris = photoUris,
                        dateCreated = albumJson.getLong("dateCreated"),
                        isSelected = albumJson.optBoolean("isSelected", false)
                    ))
                }

                Log.d(TAG, "Loaded ${albums.size} albums from preferences")
                _virtualAlbums.value = albums

                // Restore albums to PhotoRepository
                albums.forEach { album ->
                    photoRepository.addVirtualAlbum(
                        PhotoRepository.VirtualAlbum(
                        id = album.id,
                        name = album.name,
                        photoUris = album.photoUris,
                        dateCreated = album.dateCreated,
                        isSelected = album.isSelected
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading virtual albums", e)
            }

            // Now load initial state
            loadInitialState()
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

    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                _state.value = PhotoManagerState.Loading
                Log.d(TAG, "Starting to load initial state")

                val photos = mutableListOf<ManagedPhoto>()

                // First try to get photos from PhotoRepository
                val currentPhotoCount = photoRepository.getPhotoCount()
                Log.d(TAG, "PhotoRepository has $currentPhotoCount photos")

                // Convert existing photos from PhotoRepository
                for (i in 0 until currentPhotoCount) {
                    photoRepository.getPhotoUrl(i)?.let { url ->
                        val sourceType = when {
                            url.contains("com.google.android.apps.photos.cloudpicker") -> PhotoSourceType.GOOGLE_PHOTOS
                            url.contains("content://media/picker") -> PhotoSourceType.LOCAL_PICKED
                            url.contains("content://media/external") -> PhotoSourceType.LOCAL_ALBUM
                            else -> PhotoSourceType.VIRTUAL_ALBUM
                        }

                        photos.add(ManagedPhoto(
                            id = url,
                            uri = url,
                            sourceType = sourceType,
                            albumId = when(sourceType) {
                                PhotoSourceType.GOOGLE_PHOTOS -> "google_photos"
                                PhotoSourceType.LOCAL_PICKED -> "picked_photos"
                                PhotoSourceType.LOCAL_ALBUM -> "local_albums"
                                PhotoSourceType.VIRTUAL_ALBUM -> "virtual_albums"
                            },
                            dateAdded = System.currentTimeMillis()
                        ))
                        Log.d(TAG, "Added photo from PhotoRepository: $url (type: $sourceType)")
                    }
                }

                // Add photos from picked URIs if they're not already included
                val pickedUris = preferences.getPickedUris()
                Log.d(TAG, "Found ${pickedUris.size} picked URIs in preferences")

                pickedUris.forEach { uriString ->
                    if (!photos.any { it.uri == uriString }) {
                        val sourceType = when {
                            uriString.contains("com.google.android.apps.photos.cloudpicker") -> PhotoSourceType.GOOGLE_PHOTOS
                            else -> PhotoSourceType.LOCAL_PICKED
                        }
                        photos.add(ManagedPhoto(
                            id = uriString,
                            uri = uriString,
                            sourceType = sourceType,
                            albumId = if (sourceType == PhotoSourceType.GOOGLE_PHOTOS) "google_picked" else "local_picked",
                            dateAdded = System.currentTimeMillis()
                        ))
                        Log.d(TAG, "Added picked photo: $uriString")

                        // Make sure it's in the PhotoRepository
                        val mediaItem = MediaItem(
                            id = uriString,
                            albumId = "local_picked",
                            baseUrl = uriString,
                            mimeType = "image/*",
                            width = 0,
                            height = 0,
                            description = null,
                            createdAt = System.currentTimeMillis(),
                            loadState = MediaItem.LoadState.IDLE
                        )
                        photoRepository.addPhotos(listOf(mediaItem), PhotoRepository.PhotoAddMode.MERGE)
                        Log.d(TAG, "Added missing photo to PhotoRepository: $uriString")
                    }
                }

                // Load local photos from selected albums if any
                val selectedAlbumIds = preferences.getSelectedAlbumIds()
                if (selectedAlbumIds.isNotEmpty()) {
                    Log.d(TAG, "Loading photos from ${selectedAlbumIds.size} local albums")
                    photos.addAll(loadLocalPhotos())
                }

                // Update virtual albums state
                val virtualAlbums = preferences.getVirtualAlbums()
                _virtualAlbums.value = virtualAlbums.map { album ->
                    VirtualAlbum(
                        id = album.id,
                        name = album.name,
                        photoUris = album.photoUris,
                        dateCreated = album.dateCreated,
                        isSelected = preferences.getSelectedVirtualAlbumIds().contains(album.id)
                    )
                }

                // Log detailed photo counts by source
                Log.d(TAG, """Photos loaded by source:
                • Total: ${photos.size}
                • Google Photos: ${photos.count { it.sourceType == PhotoSourceType.GOOGLE_PHOTOS }}
                • Local Picked: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_PICKED }}
                • Local Album: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_ALBUM }}
                • Virtual Album: ${photos.count { it.sourceType == PhotoSourceType.VIRTUAL_ALBUM }}
            """.trimIndent())

                if (photos.isNotEmpty()) {
                    _photos.value = photos.sortedByDescending { it.dateAdded }
                    _state.value = PhotoManagerState.Idle
                } else {
                    _photos.value = emptyList()
                    _state.value = PhotoManagerState.Empty
                    Log.d(TAG, "No photos found")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial state", e)
                _state.value = PhotoManagerState.Error("Failed to load photos: ${e.message}")
                _photos.value = emptyList()
            }
        }
    }

    private suspend fun loadLocalPhotos(): List<ManagedPhoto> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<ManagedPhoto>()
        val selectedAlbumIds = preferences.getSelectedAlbumIds()

        try {
            // First load picked photos
            preferences.getPickedUris().forEach { uriString ->
                try {
                    Uri.parse(uriString)?.let { uri ->
                        context.contentResolver.query(
                            uri,
                            arrayOf(
                                MediaStore.Images.Media._ID,
                                MediaStore.Images.Media.DATE_ADDED,
                                MediaStore.Images.Media.DISPLAY_NAME
                            ),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                                val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert to milliseconds

                                photos.add(
                                    ManagedPhoto(
                                        id = uri.toString(),
                                        uri = uri.toString(),
                                        sourceType = PhotoSourceType.LOCAL_PICKED,
                                        albumId = "picked_photos",
                                        dateAdded = dateAdded
                                    )
                                )
                            } else {
                                // If cursor is empty but URI exists, add with current timestamp
                                photos.add(
                                    ManagedPhoto(
                                        id = uri.toString(),
                                        uri = uri.toString(),
                                        sourceType = PhotoSourceType.LOCAL_PICKED,
                                        albumId = "picked_photos",
                                        dateAdded = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading picked photo: $uriString", e)
                }
            }

            // Then load photos from selected albums
            selectedAlbumIds.forEach { albumId ->
                try {
                    val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
                    val selectionArgs = arrayOf(albumId)
                    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DATE_ADDED
                        ),
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            )

                            photos.add(
                                ManagedPhoto(
                                    id = id.toString(),
                                    uri = contentUri.toString(),
                                    sourceType = PhotoSourceType.LOCAL_ALBUM,
                                    albumId = albumId,
                                    dateAdded = dateAdded * 1000 // Convert to milliseconds
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading photos from album $albumId", e)
                }
            }

            Log.d(TAG, """Local photos loaded:
            • Total: ${photos.size}
            • Picked: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_PICKED }}
            • Album: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_ALBUM }}
        """.trimIndent())

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLocalPhotos", e)
        }

        photos
    }

    fun selectAllPhotos() {
        val updatedPhotos = _photos.value.map { it.copy(isSelected = true) }
        _photos.value = updatedPhotos
        _selectedCount.value = updatedPhotos.size
    }

    fun deselectAllPhotos() {
        val updatedPhotos = _photos.value.map { it.copy(isSelected = false) }
        _photos.value = updatedPhotos
        _selectedCount.value = 0
    }

    fun createVirtualAlbum(name: String) {
        viewModelScope.launch {
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

                // Add to PhotoRepository
                photoRepository.addVirtualAlbum(
                    PhotoRepository.VirtualAlbum(
                    id = newAlbum.id,
                    name = newAlbum.name,
                    photoUris = newAlbum.photoUris,
                    dateCreated = newAlbum.dateCreated,
                    isSelected = true
                ))

                // Update local state
                val currentAlbums = _virtualAlbums.value.toMutableList()
                currentAlbums.add(newAlbum)
                _virtualAlbums.value = currentAlbums

                // Save immediately after creation
                saveVirtualAlbums(currentAlbums)

                clearSelection()
                _state.value = PhotoManagerState.Success("Album created successfully")

                Log.d(TAG, "Created and saved album: ${newAlbum.name} with ${newAlbum.photoUris.size} photos")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating virtual album", e)
                _state.value = PhotoManagerState.Error("Failed to create album")
            }
        }
    }

    fun reloadVirtualAlbums() {
        viewModelScope.launch {
            try {
                val savedAlbums = preferences.getString("virtual_albums", "[]")
                val jsonArray = JSONArray(savedAlbums)
                val albums = mutableListOf<VirtualAlbum>()

                for (i in 0 until jsonArray.length()) {
                    val albumJson = jsonArray.getJSONObject(i)
                    val photoUrisArray = albumJson.getJSONArray("photoUris")
                    val photoUris = mutableListOf<String>()
                    for (j in 0 until photoUrisArray.length()) {
                        photoUris.add(photoUrisArray.getString(j))
                    }

                    albums.add(VirtualAlbum(
                        id = albumJson.getString("id"),
                        name = albumJson.getString("name"),
                        photoUris = photoUris,
                        dateCreated = albumJson.getLong("dateCreated"),
                        isSelected = albumJson.optBoolean("isSelected", false)
                    ))
                }

                Log.d(TAG, "Reloaded ${albums.size} albums from preferences")
                _virtualAlbums.value = albums
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading virtual albums", e)
            }
        }
    }

    fun deleteVirtualAlbum(albumId: String) {
        viewModelScope.launch {
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

    fun groupPhotosBySource() {
        val grouped = _photos.value.sortedBy {
            when (it.sourceType) {
                PhotoSourceType.GOOGLE_PHOTOS -> 0
                PhotoSourceType.LOCAL_PICKED -> 1
                PhotoSourceType.LOCAL_ALBUM -> 2
                PhotoSourceType.VIRTUAL_ALBUM -> 3
            }
        }
        _photos.value = grouped
    }

    fun removeSelectedPhotos() {
        viewModelScope.launch {
            try {
                _state.value = PhotoManagerState.Loading
                val selectedPhotos = _photos.value.filter { it.isSelected }

                // Remove from PhotoRepository and update preferences
                selectedPhotos.forEach { photo ->
                    photoRepository.removePhoto(photo.uri)

                    // Remove from picked URIs if it was individually picked
                    if (photo.sourceType == PhotoSourceType.LOCAL_PICKED) {
                        preferences.removePickedUri(photo.uri)
                    }
                }

                // Update local photo selections
                val currentLocalPhotos = preferences.getLocalSelectedPhotos()
                val updatedLocalPhotos = currentLocalPhotos.filterNot { uri ->
                    selectedPhotos.any { photo -> photo.uri == uri }
                }.toSet()
                preferences.updateLocalSelectedPhotos(updatedLocalPhotos)

                // Update album selections if needed
                val currentAlbumIds = preferences.getSelectedAlbumIds()
                val remainingPhotos = _photos.value.filterNot { it.isSelected }
                val albumsToKeep = remainingPhotos
                    .filter { it.sourceType == PhotoSourceType.LOCAL_ALBUM }
                    .mapNotNull { it.albumId }
                    .toSet()

                preferences.setSelectedAlbumIds(currentAlbumIds.intersect(albumsToKeep))

                // Update UI state
                val updatedPhotos = _photos.value.filterNot { it.isSelected }
                _photos.value = updatedPhotos
                _selectedCount.value = 0
                _state.value = PhotoManagerState.Success("Removed ${selectedPhotos.size} photos")

            } catch (e: Exception) {
                _state.value = PhotoManagerState.Error("Failed to remove photos: ${e.message}")
            }
        }
    }

    private fun clearSelection() {
        val currentPhotos = _photos.value.map { it.copy(isSelected = false) }
        _photos.value = currentPhotos
        _selectedCount.value = 0
    }

    fun saveVirtualAlbums() {
        viewModelScope.launch {
            try {
                val albums = _virtualAlbums.value
                Log.d(TAG, "Saving ${albums.size} virtual albums")

                // First, save all albums to preferences
                val jsonArray = JSONArray()
                albums.forEach { album ->
                    val albumJson = JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("photoUris", JSONArray(album.photoUris))
                        put("dateCreated", album.dateCreated)
                        put("isSelected", album.isSelected)
                    }
                    jsonArray.put(albumJson)
                }
                preferences.setString("virtual_albums", jsonArray.toString())

                // Clear existing photos in PhotoRepository
                photoRepository.clearPhotos()

                // Add only photos from selected albums
                albums.filter { it.isSelected }
                    .flatMap { it.photoUris }
                    .distinct()
                    .forEach { uri ->
                        photoRepository.addPhoto(uri)
                    }

                Log.d(TAG, "Successfully saved ${albums.size} albums to preferences")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving virtual albums", e)
            }
        }
    }

    private suspend fun saveVirtualAlbums(albums: List<VirtualAlbum>) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Saving ${albums.size} virtual albums")

                val jsonArray = JSONArray()
                albums.forEach { album ->
                    val albumJson = JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("photoUris", JSONArray(album.photoUris))
                        put("dateCreated", album.dateCreated)
                        put("isSelected", album.isSelected)
                    }
                    jsonArray.put(albumJson)
                }
                preferences.setString("virtual_albums", jsonArray.toString())
                Log.d(TAG, "Successfully saved ${albums.size} albums to preferences")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving virtual albums", e)
            }
        }
    }

    fun toggleVirtualAlbumSelection(albumId: String) {
        val currentAlbums = _virtualAlbums.value.toMutableList()
        val index = currentAlbums.indexOfFirst { it.id == albumId }
        if (index != -1) {
            currentAlbums[index] = currentAlbums[index].copy(isSelected = !currentAlbums[index].isSelected)
            _virtualAlbums.value = currentAlbums
            Log.d(TAG, "Album selection toggled: $albumId, new state: ${currentAlbums[index].isSelected}")
        }
    }

    fun reloadState() {
        loadInitialState()
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            Log.d(TAG, "ViewModel being cleared, saving ${_virtualAlbums.value.size} albums")
            saveVirtualAlbums(_virtualAlbums.value)
        }
    }
}