package com.photostreamr.recovery

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
import kotlinx.coroutines.withContext


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
                    attemptRecovery(preserveAuth = true)
                }
                .collect { state ->
                    try {
                        validateState(state)
                    } catch (e: Exception) {
                        Timber.w(e, "State validation failed")
                        attemptRecovery(preserveAuth = true)
                    }
                }
        }
    }

    private suspend fun validateState(state: AppDataState) {
        val validationResult = stateValidator.validate(state)
        if (!validationResult.isValid) {
            Timber.w("State validation failed: ${validationResult.errors.joinToString()}")
            attemptRecovery(preserveAuth = true)
        }
    }

    private suspend fun attemptRecovery(preserveAuth: Boolean = true) {
        val currentState = appDataManager.getCurrentState()
        val recoveryHistory = currentState.recoveryAttempts
        val recentAttempts = recoveryHistory.filter {
            Instant.now().toEpochMilli() - it < RECOVERY_WINDOW_MS
        }

        if (recentAttempts.size >= MAX_RECOVERY_ATTEMPTS) {
            Timber.e("Too many recovery attempts, resetting to defaults")
            if (preserveAuth) {
                performSafeReset(currentState)
            } else {
                appDataManager.resetToDefaults()
            }
            return
        }

        try {
            val backupState = appDataManager.loadBackupState()
            if (stateValidator.validate(backupState).isValid) {
                restoreFromBackup(backupState, preserveAuth, currentState)
            } else {
                Timber.w("Backup state invalid, performing partial recovery")
                performPartialRecovery(preserveAuth, currentState)
            }
        } catch (e: Exception) {
            Timber.e(e, "Recovery failed, performing safe reset")
            performSafeReset(currentState)
        }
    }

    private suspend fun restoreFromBackup(
        backupState: AppDataState,
        preserveAuth: Boolean,
        currentState: AppDataState
    ) {
        withContext(Dispatchers.Default) {
            appDataManager.updateState { _ ->
                backupState.copy(
                    // Preserve authentication if needed
                    authToken = if (preserveAuth) currentState.authToken else backupState.authToken,
                    refreshToken = if (preserveAuth) currentState.refreshToken else backupState.refreshToken,
                    accountEmail = if (preserveAuth) currentState.accountEmail else backupState.accountEmail,
                    // Update recovery metadata
                    recoveryAttempts = currentState.recoveryAttempts + Instant.now().toEpochMilli(),
                    lastModified = Instant.now().epochSecond
                )
            }
            Timber.i("State restored from backup, auth preserved: $preserveAuth")
        }
    }

    private suspend fun performPartialRecovery(preserveAuth: Boolean, currentState: AppDataState) {
        withContext(Dispatchers.Default) {
            appDataManager.updateState { _ ->
                currentState.copy(
                    isInPreviewMode = false,
                    previewCount = 0,
                    lastPreviewTimestamp = 0,
                    // Don't clear auth tokens if preserving auth
                    authToken = if (preserveAuth) currentState.authToken else "",
                    refreshToken = if (preserveAuth) currentState.refreshToken else "",
                    accountEmail = if (preserveAuth) currentState.accountEmail else "",
                    recoveryAttempts = currentState.recoveryAttempts + Instant.now().toEpochMilli(),
                    lastModified = Instant.now().epochSecond
                )
            }
            Timber.i("Partial state recovery completed, auth preserved: $preserveAuth")
        }
    }

    private suspend fun performSafeReset(currentState: AppDataState) {
        withContext(Dispatchers.Default) {
            try {
                // Create a new default state while preserving authentication
                val defaultState = AppDataState.createDefault().copy(
                    authToken = currentState.authToken,
                    refreshToken = currentState.refreshToken,
                    accountEmail = currentState.accountEmail,
                    lastModified = Instant.now().epochSecond
                )

                // Update the state while preserving critical data
                appDataManager.updateState { _ -> defaultState }

                // Clear non-essential caches
                withContext(Dispatchers.IO) {
                    context.cacheDir
                        .listFiles()
                        ?.filter { !it.name.contains("auth") && !it.name.contains("token") }
                        ?.forEach { it.deleteRecursively() }

                    context.externalCacheDir
                        ?.listFiles()
                        ?.filter { !it.name.contains("auth") && !it.name.contains("token") }
                        ?.forEach { it.deleteRecursively() }
                }

                Timber.i("Safe reset completed, authentication preserved")
            } catch (e: Exception) {
                Timber.e(e, "Safe reset failed")
                // If safe reset fails, don't throw - we're already in an error recovery path
            }
        }
    }
}

