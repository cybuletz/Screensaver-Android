// Create new file: AlbumSelectionViewModel.kt
package com.example.screensaver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screensaver.models.Album
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumSelectionViewModel @Inject constructor() : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedAlbums = MutableStateFlow<Set<Album>>(emptySet())
    val selectedAlbums: StateFlow<Set<Album>> = _selectedAlbums

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
}