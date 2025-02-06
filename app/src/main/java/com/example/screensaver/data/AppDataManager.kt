package com.example.screensaver.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.shared.GooglePhotosManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the persistence and retrieval of application data state.
 * Delegates authentication and sensitive data storage to SecureStorage.
 */
@Singleton
class AppDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val appPreferences: AppPreferences,
    private val secureStorage: SecureStorage,
    private val googlePhotosManager: GooglePhotosManager,
    private val photoCache: PhotoCache,
    private val coroutineScope: CoroutineScope
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _stateFlow = MutableStateFlow(loadState())
    val stateFlow = _stateFlow.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    companion object {
        private const val PREFERENCES_NAME = "app_data_preferences"
        private const val KEY_APP_STATE = "app_state"
        private const val BACKUP_SUFFIX = "_backup"
    }

    init {
        // Register listener for state changes
        preferences.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_APP_STATE) {
                _stateFlow.value = loadState()
            }
        }

        // Monitor credentials state changes
        coroutineScope.launch {
            secureStorage.credentialsFlow.collect { state ->
                updateAuthState(state)
            }
        }

        // Perform initial state validation
        validateAndRepairState()
    }

    suspend fun performFullReset() {
        try {
            withContext(NonCancellable) {
                // Clear Google Photos state
                googlePhotosManager.cleanup()

                // Clear secure storage
                secureStorage.clearGoogleCredentials()

                // Clear photo cache
                photoCache.cleanup()

                // Clear app preferences
                appPreferences.clearAll()

                // Clear main preferences
                preferences.edit().clear().apply()

                // Clear backup state
                context.getSharedPreferences("${PREFERENCES_NAME}${BACKUP_SUFFIX}", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()

                // Clear all SharedPreferences files
                clearAllSharedPreferences()

                // Reset to default state
                val defaultState = AppDataState.createDefault()
                saveState(defaultState)
                _stateFlow.value = defaultState
                _authState.value = AuthState.NotAuthenticated

                // Clear cache directories
                context.cacheDir.deleteRecursively()
                context.externalCacheDir?.deleteRecursively()

                Timber.d("Full data reset completed")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to perform full data reset")
            throw e
        }
    }

    private fun clearAllSharedPreferences() {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (prefsDir.exists() && prefsDir.isDirectory) {
            prefsDir.listFiles()?.forEach { file ->
                try {
                    if (file.name.endsWith(".xml")) {
                        val prefsName = file.name.replace(".xml", "")
                        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit() // Use commit() to ensure synchronous execution
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error clearing preferences file: ${file.name}")
                }
            }
        }
    }

    private fun updateAuthState(credentialState: SecureStorage.CredentialState) {
        _authState.value = when (credentialState) {
            is SecureStorage.CredentialState.Valid -> AuthState.Authenticated(credentialState.credentials.email)
            is SecureStorage.CredentialState.Expired -> AuthState.TokenExpired
            is SecureStorage.CredentialState.NotInitialized -> AuthState.NotAuthenticated
            is SecureStorage.CredentialState.Error -> AuthState.Error(credentialState.exception)
        }
    }

    /**
     * Updates the application state
     * @param update Lambda that receives current state and returns updated state
     */
    fun updateState(update: (AppDataState) -> AppDataState) {
        val currentState = _stateFlow.value
        val newState = update(currentState).withUpdatedTimestamp()

        try {
            newState.validate()
            saveState(newState)
            _stateFlow.value = newState
        } catch (e: Exception) {
            Timber.e(e, "Failed to update state")
            // Revert to last known good state
            _stateFlow.value = loadState()
        }
    }

    /**
     * Gets the current application state
     */
    fun getCurrentState(): AppDataState = _stateFlow.value

    /**
     * Observes state changes
     */
    fun observeState(): Flow<AppDataState> = stateFlow

    /**
     * Signs in a user with Google credentials
     */
    suspend fun signIn(accessToken: String, refreshToken: String, expirationTime: Long, email: String): Boolean {
        return try {
            secureStorage.saveGoogleCredentials(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expirationTime = expirationTime,
                email = email
            )
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save credentials during sign in")
            false
        }
    }

    /**
     * Signs out the current user
     */
    suspend fun signOut() {
        try {
            googlePhotosManager.cleanup()
            secureStorage.clearGoogleCredentials()
            appPreferences.clearSelectedAlbums()
        } catch (e: Exception) {
            Timber.e(e, "Error during sign out")
        }
    }

    /**
     * Resets all application data to defaults
     */
    fun resetToDefaults() {
        val defaultState = AppDataState.createDefault()
        saveState(defaultState)
        _stateFlow.value = defaultState
        coroutineScope.launch {
            signOut()
        }
    }

    private fun saveState(state: AppDataState) {
        try {
            // First save to backup
            preferences.edit {
                putString("${KEY_APP_STATE}${BACKUP_SUFFIX}", gson.toJson(state))
            }

            // Then save to main storage
            preferences.edit {
                putString(KEY_APP_STATE, gson.toJson(state))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save state")
            throw e
        }
    }

    private fun loadState(): AppDataState {
        return try {
            val json = preferences.getString(KEY_APP_STATE, null)
            if (json != null) {
                gson.fromJson(json, AppDataState::class.java)
            } else {
                AppDataState.createDefault()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load state, attempting to load backup")
            loadBackupState()
        }
    }

    suspend fun createBackup(state: AppDataState) {
        try {
            val json = gson.toJson(state)
            preferences.edit {
                putString("${KEY_APP_STATE}${BACKUP_SUFFIX}", json)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create backup")
            throw e
        }
    }

    internal fun loadBackupState(): AppDataState {
        return try {
            val backupJson = preferences.getString("${KEY_APP_STATE}${BACKUP_SUFFIX}", null)
            if (backupJson != null) {
                gson.fromJson(backupJson, AppDataState::class.java)
            } else {
                AppDataState.createDefault()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load backup state")
            AppDataState.createDefault()
        }
    }

    private fun validateAndRepairState() {
        try {
            val currentState = loadState()
            currentState.validate()
        } catch (e: Exception) {
            Timber.w(e, "State validation failed, attempting repair")
            val repairedState = loadBackupState()
            try {
                repairedState.validate()
                saveState(repairedState)
                _stateFlow.value = repairedState
            } catch (e: Exception) {
                Timber.e(e, "State repair failed, resetting to defaults")
                resetToDefaults()
            }
        }
    }

    sealed class AuthState {
        object NotAuthenticated : AuthState()
        data class Authenticated(val email: String) : AuthState()
        object TokenExpired : AuthState()
        data class Error(val exception: Exception) : AuthState()
    }
}