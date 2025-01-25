package com.example.screensaver.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val previewManager: PreviewManager,
    private val preferences: AppPreferences
) : ViewModel() {

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Initial)
    val previewState: StateFlow<PreviewState> = _previewState

    private val _cooldownSeconds = MutableStateFlow(0L)
    val cooldownSeconds: StateFlow<Long> = _cooldownSeconds

    sealed class PreviewState {
        object Initial : PreviewState()
        object Loading : PreviewState()
        object Active : PreviewState()
        data class Cooldown(val remainingSeconds: Long) : PreviewState()
        data class Error(val message: String) : PreviewState()
    }

    fun startPreview() {
        viewModelScope.launch {
            if (!previewManager.canStartPreview()) {
                val timeUntilNext = previewManager.getTimeUntilNextPreviewAllowed() / 1000
                _previewState.value = PreviewState.Cooldown(timeUntilNext)
                _cooldownSeconds.value = timeUntilNext
                return@launch
            }

            try {
                _previewState.value = PreviewState.Loading
                previewManager.startPreview()
                _previewState.value = PreviewState.Active
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Failed to start preview")
            }
        }
    }

    fun endPreview() {
        viewModelScope.launch {
            try {
                if (previewManager.isInPreviewMode()) {
                    previewManager.endPreview()
                }
                _previewState.value = PreviewState.Initial
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Failed to end preview")
            }
        }
    }

    fun getRemainingPreviews(): Int {
        return previewManager.getRemainingPreviews()
    }

    fun isPreviewModeActive(): Boolean {
        return previewManager.isInPreviewMode()
    }

    override fun onCleared() {
        super.onCleared()
        if (previewManager.isInPreviewMode()) {
            previewManager.endPreview()
        }
    }
}