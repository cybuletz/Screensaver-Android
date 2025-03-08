package com.example.screensaver.settings

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Singleton

@Singleton
class SharedPhotoSelectionState @Inject constructor() {
    private val _selectedPhotos = MutableStateFlow<Set<String>>(emptySet())
    val selectedPhotos: StateFlow<Set<String>> = _selectedPhotos.asStateFlow()

    private val _selectedCount = MutableStateFlow(0)

    fun togglePhotoSelection(photoId: String) {
        val current = _selectedPhotos.value.toMutableSet()
        if (current.contains(photoId)) {
            current.remove(photoId)
        } else {
            current.add(photoId)
        }
        _selectedPhotos.value = current
        _selectedCount.value = current.size
    }

    fun addPhotos(photos: Collection<String>) {
        Log.d(TAG, "Adding ${photos.size} photos to selection")
        val current = _selectedPhotos.value.toMutableSet()
        current.addAll(photos)
        _selectedPhotos.value = current
        Log.d(TAG, "Selection updated, now contains ${current.size} photos")
    }

    fun isValid(): Boolean = _selectedPhotos.value.isNotEmpty()

    companion object {
        private const val TAG = "SharedPhotoSelectionState"
    }
}