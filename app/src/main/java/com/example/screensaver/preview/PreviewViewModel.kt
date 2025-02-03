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
        private const val PREF_PREVIEW_COUNT = "preview_count"
        private const val PREF_LAST_PREVIEW = "last_preview_timestamp"
        private const val PREF_PREVIEW_START = "preview_start_timestamp"
    }

    init {
        // Check and reset preview count if cooldown period has passed
        resetPreviewCountIfNeeded()
        // Start cooldown tracking if needed
        updateCooldownStatus()
    }

    private fun resetPreviewCountIfNeeded() {
        val lastPreview = preferences.getLong(PREF_LAST_PREVIEW, 0)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPreview > PREVIEW_COOLDOWN_DURATION) {
            preferences.edit {
                putInt(PREF_PREVIEW_COUNT, 0)
                putLong(PREF_LAST_PREVIEW, 0)
            }
        }
    }

    private fun updateCooldownStatus() {
        viewModelScope.launch {
            if (!canStartPreview()) {
                val timeUntilNext = getTimeUntilNextPreviewAllowed() / 1000
                _cooldownSeconds.value = timeUntilNext
            }
        }
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
                _previewState.value = PreviewState.Initial
                recordPreviewStart()
                isPreviewActive = true
                _previewState.value = PreviewState.Available(getRemainingPreviews())
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Failed to start preview")
            }
        }
    }

    fun endPreview() {
        viewModelScope.launch {
            try {
                if (isPreviewActive) {
                    recordPreviewEnd()
                    isPreviewActive = false
                }
                _previewState.value = PreviewState.Initial
                updateCooldownStatus()
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Failed to end preview")
            }
        }
    }

    private fun canStartPreview(): Boolean {
        val previewCount = getPreviewCount()
        val timeSinceLastPreview = getTimeSinceLastPreview()

        return when {
            previewCount < MAX_PREVIEW_COUNT -> true
            timeSinceLastPreview > PREVIEW_COOLDOWN_DURATION -> {
                // Reset count if cooldown period has passed
                resetPreviewCountIfNeeded()
                true
            }
            else -> false
        }
    }

    private fun getPreviewCount(): Int =
        preferences.getInt(PREF_PREVIEW_COUNT, 0)

    fun getRemainingPreviews(): Int =
        MAX_PREVIEW_COUNT - getPreviewCount()

    private fun getTimeSinceLastPreview(): Long {
        val lastPreview = preferences.getLong(PREF_LAST_PREVIEW, 0)
        return System.currentTimeMillis() - lastPreview
    }

    fun getTimeUntilNextPreviewAllowed(): Long {
        val timeSinceLastPreview = getTimeSinceLastPreview()
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
            putLong(PREF_PREVIEW_START, System.currentTimeMillis())
        }
    }

    private fun recordPreviewEnd() {
        val startTime = preferences.getLong(PREF_PREVIEW_START, 0)
        if (startTime > 0) {
            preferences.edit {
                putLong(PREF_PREVIEW_START, 0)
            }
        }
    }

    fun isPreviewModeActive(): Boolean = isPreviewActive

    override fun onCleared() {
        super.onCleared()
        if (isPreviewActive) {
            endPreview()
        }
    }
}