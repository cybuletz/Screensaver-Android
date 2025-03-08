package com.example.screensaver.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import com.example.screensaver.models.MediaItem

@HiltViewModel
class PhotoSelectionState @Inject constructor() : ViewModel() {
    private val _selectedPhotos = MutableStateFlow<Set<String>>(emptySet())
    val selectedPhotos: StateFlow<Set<String>> = _selectedPhotos

    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount

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
        val current = _selectedPhotos.value.toMutableSet()
        current.addAll(photos)
        _selectedPhotos.value = current
        _selectedCount.value = current.size
    }

    fun isValid(): Boolean = _selectedPhotos.value.isNotEmpty()
}