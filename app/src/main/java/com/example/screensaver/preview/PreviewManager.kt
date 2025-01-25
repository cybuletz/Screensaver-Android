package com.example.screensaver.preview

import android.content.Context
import android.os.SystemClock
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoSourceState: PhotoSourceState,
    private val preferences: AppPreferences
) {
    private val _canStartPreview = MutableStateFlow(true)
    val canStartPreview: StateFlow<Boolean> = _canStartPreview

    companion object {
        private const val MIN_PREVIEW_INTERVAL = 5000L // 5 seconds between previews
        private const val MAX_DAILY_PREVIEWS = 10
        private const val PREVIEW_COUNT_RESET_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
    }

    fun canStartPreview(): Boolean {
        return photoSourceState.canStartPreview()
    }

    fun getTimeUntilNextPreviewAllowed(): Long {
        return photoSourceState.getTimeUntilNextPreviewAllowed()
    }

    fun startPreview() {
        if (canStartPreview()) {
            photoSourceState.recordPreviewStarted()
            updatePreviewState()
        }
    }

    fun endPreview() {
        photoSourceState.recordPreviewEnded()
        updatePreviewState()
    }

    fun getRemainingPreviews(): Int {
        return photoSourceState.getRemainingPreviewsToday()
    }

    fun isInPreviewMode(): Boolean {
        return photoSourceState.isInPreviewMode
    }

    private fun updatePreviewState() {
        _canStartPreview.value = canStartPreview()
    }

    fun resetPreviewStats() {
        photoSourceState.resetPreviewStats()
        updatePreviewState()
    }
}