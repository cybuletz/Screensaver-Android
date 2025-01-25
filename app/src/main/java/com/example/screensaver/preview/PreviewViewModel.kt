package com.example.screensaver.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.viewmodels.PhotoViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    private val preferences: AppPreferences
) : ViewModel() {

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Initial)
    val previewState: StateFlow<PreviewState> = _previewState

    private val _cooldownSeconds = MutableStateFlow(0L)
    val cooldownSeconds: StateFlow<Long> = _cooldownSeconds

    private var isPreviewActive = false

    companion object {
        private const val MAX_PREVIEW_COUNT = 5
        private const val PREVIEW_COOLDOWN_DURATION = 3600000L // 1 hour in milliseconds
    }

    sealed class PreviewState {
        object Initial : PreviewState()
        object Loading : PreviewState()
        object Active : PreviewState()
        data class Cooldown(val remainingSeconds: Long) : PreviewState()
        data class Error(val message: String) : PreviewState()
    }

    fun startPreview() {
        viewModelScope.launch {
            if (!canStartPreview()) {
                val timeUntilNext = getTimeUntilNextPreviewAllowed() / 1000
                _previewState.value = PreviewState.Cooldown(timeUntilNext)
                _cooldownSeconds.value = timeUntilNext
                return@launch
            }

            try {
                _previewState.value = PreviewState.Loading
                recordPreviewStart()
                isPreviewActive = true
                _previewState.value = PreviewState.Active
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Failed to start preview")
            }
        }
    }

    fun endPreview() {
        viewModelScope.launch {
            try {
                if (isPreviewActive) {
                    isPreviewActive = false
                }
                _previewState.value = PreviewState.Initial
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Failed to end preview")
            }
        }
    }

    private fun canStartPreview(): Boolean {
        val lastPreview = preferences.getLong(PREF_LAST_PREVIEW, 0)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastPreview = currentTime - lastPreview

        return getPreviewCount() < MAX_PREVIEW_COUNT ||
                timeSinceLastPreview > PREVIEW_COOLDOWN_DURATION
    }

    private fun getPreviewCount(): Int =
        preferences.getInt(PREF_PREVIEW_COUNT, 0)

    fun getRemainingPreviews(): Int =
        MAX_PREVIEW_COUNT - getPreviewCount()

    private fun getTimeUntilNextPreviewAllowed(): Long {
        val lastPreview = preferences.getLong(PREF_LAST_PREVIEW, 0)
        val currentTime = System.currentTimeMillis()
        val timeSinceLastPreview = currentTime - lastPreview

        return if (timeSinceLastPreview < PREVIEW_COOLDOWN_DURATION) {
            PREVIEW_COOLDOWN_DURATION - timeSinceLastPreview
        } else {
            0L
        }
    }

    private fun recordPreviewStart() {
        preferences.edit {
            putInt(PREF_PREVIEW_COUNT, getPreviewCount() + 1)
            putLong(PREF_LAST_PREVIEW, System.currentTimeMillis())
        }
    }

    fun isPreviewModeActive(): Boolean = isPreviewActive

    override fun onCleared() {
        super.onCleared()
        if (isPreviewActive) {
            endPreview()
        }
    }

    companion object {
        private const val PREF_PREVIEW_COUNT = "preview_count"
        private const val PREF_LAST_PREVIEW = "last_preview_timestamp"
    }
}