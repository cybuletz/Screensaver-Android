package com.example.screensaver

import android.content.Context
import com.example.screensaver.data.AppDataManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoSourceState @Inject constructor(
    @ApplicationContext context: Context,
    private val appDataManager: AppDataManager
) {
    companion object {
        private const val MAX_DAILY_PREVIEWS = 10
        private const val MIN_PREVIEW_INTERVAL = 5000L // 5 seconds between previews
        private const val PREVIEW_COUNT_RESET_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
    }

    init {
        // Add this initialization block to load state immediately when created
        restoreState()
    }

    private fun restoreState() {
        val currentState = appDataManager.getCurrentState()
        // Load saved state immediately
        hasSelectedSource = currentState.photoSources.isNotEmpty()
        hasSelectedPhotos = currentState.selectedAlbums.isNotEmpty()
        isInPreviewMode = currentState.isInPreviewMode

        // Reset preview mode if it was active during force close
        if (isInPreviewMode) {
            resetPreviewStats()
        }
    }

    var hasSelectedSource: Boolean
        get() = appDataManager.getCurrentState().photoSources.isNotEmpty()
        set(value) = appDataManager.updateState {
            if (value && it.photoSources.isEmpty()) {
                it.copy(photoSources = setOf("local"))
            } else if (!value) {
                it.copy(photoSources = emptySet())
            } else it
        }

    var hasSelectedPhotos: Boolean
        get() = appDataManager.getCurrentState().selectedAlbums.isNotEmpty()
        set(value) = appDataManager.updateState {
            if (!value) it.copy(selectedAlbums = emptySet()) else it
        }

    var lastPreviewTimestamp: Long
        get() = appDataManager.getCurrentState().lastPreviewTimestamp
        private set(value) = appDataManager.updateState {
            it.copy(lastPreviewTimestamp = value)
        }

    var previewCount: Int
        get() = appDataManager.getCurrentState().previewCount
        private set(value) = appDataManager.updateState {
            it.copy(previewCount = value)
        }

    private var lastPreviewResetTime: Long
        get() = appDataManager.getCurrentState().lastPreviewResetTime
        set(value) = appDataManager.updateState {
            it.copy(lastPreviewResetTime = value)
        }

    var isInPreviewMode: Boolean
        get() = appDataManager.getCurrentState().isInPreviewMode
        set(value) = appDataManager.updateState {
            it.copy(isInPreviewMode = value)
        }

    fun reset() {
        appDataManager.updateState {
            it.copy(
                photoSources = emptySet(),
                selectedAlbums = emptySet(),
                lastPreviewTimestamp = 0,
                previewCount = 0,
                lastPreviewResetTime = 0,
                isInPreviewMode = false
            )
        }
        Timber.d("PhotoSourceState reset completed")
    }

    fun isScreensaverReady(): Boolean {
        return hasSelectedSource && hasSelectedPhotos
    }

    fun recordPreviewStarted() {
        checkAndResetDailyCount()
        lastPreviewTimestamp = System.currentTimeMillis()
        previewCount++
        isInPreviewMode = true
        Timber.d("Preview started: count=$previewCount")
    }

    fun recordPreviewEnded() {
        isInPreviewMode = false
        Timber.d("Preview ended")
    }

    fun getTimeSinceLastPreview(): Long {
        return if (lastPreviewTimestamp == 0L) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() - lastPreviewTimestamp
        }
    }

    private fun checkAndResetDailyCount() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPreviewResetTime >= PREVIEW_COUNT_RESET_INTERVAL) {
            resetPreviewStats()
            lastPreviewResetTime = currentTime
            Timber.d("Daily preview count reset")
        }
    }

    fun resetPreviewStats() {
        appDataManager.updateState {
            it.copy(
                lastPreviewTimestamp = 0,
                previewCount = 0,
                isInPreviewMode = false
            )
        }
        Timber.d("Preview stats reset")
    }
}