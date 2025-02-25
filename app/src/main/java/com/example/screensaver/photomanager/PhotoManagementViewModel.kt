package com.example.screensaver.photomanager

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class PhotoManagementViewModel @Inject constructor(
    private val photoCollectionManager: PhotoCollectionManager,
    private val lockScreenPhotoManager: LockScreenPhotoManager,
    private val preferences: AppPreferences
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = false,
        val error: String? = null
    )

    sealed class ErrorEvent {
        data class NetworkError(val message: String) : ErrorEvent()
        data class StorageError(val message: String) : ErrorEvent()
        data class ValidationError(val message: String) : ErrorEvent()
    }

    private val _errorEvents = MutableSharedFlow<ErrorEvent>()
    val errorEvents: SharedFlow<ErrorEvent> = _errorEvents
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    companion object {
        private const val TAG = "PhotoManagementViewModel"
    }

    init {
        loadInitialState()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                photoCollectionManager.getCollections()
                _state.update { it.copy(isLoading = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun handleError(error: Exception) {
        viewModelScope.launch {
            val event = when (error) {
                is IOException -> ErrorEvent.NetworkError(error.localizedMessage ?: "Network error")
                is SecurityException -> ErrorEvent.StorageError(error.localizedMessage ?: "Storage error")
                else -> ErrorEvent.ValidationError(error.localizedMessage ?: "Validation error")
            }
            _errorEvents.emit(event)
        }
    }

    // Add performance optimization for image loading
    private val imageLoadingScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    Log.e(TAG, "Error loading images", throwable)
                }
    )
}