package com.example.screensaver.recovery

import android.content.Context
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.AppDataState
import com.example.screensaver.data.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateRestoration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDataManager: AppDataManager,
    private val secureStorage: SecureStorage
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateValidator = StateValidator()

    companion object {
        private const val STATE_BACKUP_INTERVAL = 30 * 60 * 1000L // 30 minutes
        private const val MAX_BACKUP_AGE = 24 * 60 * 60 * 1000L // 24 hours
    }

    init {
        schedulePeriodicBackup()
    }

    private fun schedulePeriodicBackup() {
        scope.launch {
            appDataManager.observeState().collect { state ->
                if (shouldCreateBackup(state)) {
                    createStateBackup(state)
                }
            }
        }
    }

    private fun shouldCreateBackup(state: AppDataState): Boolean {
        val lastBackupTime = state.lastBackupTimestamp
        val currentTime = Instant.now().toEpochMilli()
        return currentTime - lastBackupTime >= STATE_BACKUP_INTERVAL
    }

    suspend fun restoreState(): RestoreResult {
        return try {
            val currentState = appDataManager.getCurrentState()

            if (stateValidator.validate(currentState).isValid) {
                Timber.d("Current state is valid, no restoration needed")
                return RestoreResult.Success(RestoreSource.CURRENT)
            }

            // Try backup state
            val backupState = appDataManager.loadBackupState()
            if (backupState != null && stateValidator.validate(backupState).isValid) {
                restoreFromBackup(backupState)
                return RestoreResult.Success(RestoreSource.BACKUP)
            }

            // Try partial restoration
            if (attemptPartialRestore(currentState)) {
                return RestoreResult.Success(RestoreSource.PARTIAL)
            }

            // Last resort: Reset to defaults
            appDataManager.resetToDefaults()
            RestoreResult.Success(RestoreSource.DEFAULT)

        } catch (e: Exception) {
            Timber.e(e, "State restoration failed")
            RestoreResult.Error(e)
        }
    }

    private suspend fun restoreFromBackup(backupState: AppDataState) {
        appDataManager.updateState { current ->
            backupState.copy(
                // Keep volatile states from current
                isInPreviewMode = false,
                previewCount = 0,
                lastPreviewTimestamp = 0,
                // Update restoration metadata
                lastRestoredTimestamp = Instant.now().toEpochMilli(),
                lastModified = Instant.now().epochSecond
            )
        }
        Timber.i("State restored from backup")
    }

    private suspend fun attemptPartialRestore(currentState: AppDataState): Boolean {
        return try {
            val partiallyRestoredState = currentState.copy(
                // Reset volatile states
                isInPreviewMode = false,
                previewCount = 0,
                lastPreviewTimestamp = 0,
                // Reset potentially corrupted states
                recoveryAttempts = emptyList(),
                // Keep essential settings
                photoSources = currentState.photoSources,
                selectedAlbums = currentState.selectedAlbums,
                displayMode = currentState.displayMode,
                // Update metadata
                lastRestoredTimestamp = Instant.now().toEpochMilli(),
                lastModified = Instant.now().epochSecond
            )

            if (stateValidator.validate(partiallyRestoredState).isValid) {
                appDataManager.updateState { partiallyRestoredState }
                Timber.i("Partial state restoration successful")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Partial state restoration failed")
            false
        }
    }

    private suspend fun createStateBackup(state: AppDataState) {
        try {
            appDataManager.createBackup(state.copy(
                lastBackupTimestamp = Instant.now().toEpochMilli()
            ))
            Timber.d("State backup created successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create state backup")
        }
    }

    sealed class RestoreResult {
        data class Success(val source: RestoreSource) : RestoreResult()
        data class Error(val exception: Exception) : RestoreResult()
    }

    enum class RestoreSource {
        CURRENT,
        BACKUP,
        PARTIAL,
        DEFAULT
    }
}