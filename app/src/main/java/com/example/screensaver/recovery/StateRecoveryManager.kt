package com.example.screensaver.recovery

import android.content.Context
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.AppDataState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateRecoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDataManager: AppDataManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateValidator = StateValidator()

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
    }

    init {
        monitorStateChanges()
    }

    private fun monitorStateChanges() {
        scope.launch {
            appDataManager.observeState()
                .catch { e ->
                    Timber.e(e, "Error monitoring state changes")
                    attemptRecovery()
                }
                .collect { state ->
                    try {
                        validateState(state)
                    } catch (e: Exception) {
                        Timber.w(e, "State validation failed")
                        attemptRecovery()
                    }
                }
        }
    }

    private suspend fun validateState(state: AppDataState) {
        val validationResult = stateValidator.validate(state)
        if (!validationResult.isValid) {
            Timber.w("State validation failed: ${validationResult.errors.joinToString()}")
            attemptRecovery()
        }
    }

    private fun attemptRecovery() {
        val recoveryHistory = appDataManager.getCurrentState().recoveryAttempts
        val recentAttempts = recoveryHistory.filter {
            Instant.now().toEpochMilli() - it < RECOVERY_WINDOW_MS
        }

        if (recentAttempts.size >= MAX_RECOVERY_ATTEMPTS) {
            Timber.e("Too many recovery attempts, resetting to defaults")
            appDataManager.resetToDefaults()
            return
        }

        try {
            val backupState = appDataManager.loadBackupState()
            if (stateValidator.validate(backupState).isValid) {
                restoreFromBackup(backupState)
            } else {
                Timber.w("Backup state invalid, performing partial recovery")
                performPartialRecovery()
            }
        } catch (e: Exception) {
            Timber.e(e, "Recovery failed, resetting to defaults")
            appDataManager.resetToDefaults()
        }
    }

    private fun restoreFromBackup(backupState: AppDataState) {
        appDataManager.updateState { current ->
            backupState.copy(
                recoveryAttempts = current.recoveryAttempts + Instant.now().toEpochMilli(),
                lastModified = Instant.now().epochSecond
            )
        }
        Timber.i("State restored from backup")
    }

    private fun performPartialRecovery() {
        appDataManager.updateState { current ->
            current.copy(
                isInPreviewMode = false,
                previewCount = 0,
                lastPreviewTimestamp = 0,
                recoveryAttempts = current.recoveryAttempts + Instant.now().toEpochMilli(),
                lastModified = Instant.now().epochSecond
            )
        }
        Timber.i("Partial state recovery completed")
    }
}

