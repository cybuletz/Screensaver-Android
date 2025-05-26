package com.photostreamr.data

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
import com.photostreamr.utils.AppPreferences
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable

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
    private val photoCache: PhotoCache,
    private val coroutineScope: CoroutineScope
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _stateFlow = MutableStateFlow(AppDataState.createDefault())
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
                coroutineScope.launch {
                    _stateFlow.value = loadState()
                }
            }
        }

        // Monitor credentials state changes
        coroutineScope.launch {
            secureStorage.credentialsFlow.collect { state ->
                updateAuthState(state)
            }
        }

        // Load initial state
        coroutineScope.launch {
            try {
                // First try to load and validate the state
                val initialState = try {
                    val state = loadState()
                    state.validate()
                    state
                } catch (e: Exception) {
                    Timber.w(e, "Initial state validation failed, attempting repair")
                    try {
                        val backupState = loadBackupState()
                        backupState.validate()
                        backupState
                    } catch (e2: Exception) {
                        Timber.e(e2, "Backup state invalid, using default")
                        AppDataState.createDefault()
                    }
                }

                // Save the validated/repaired state
                saveState(initialState)

                withContext(Dispatchers.Main) {
                    _stateFlow.value = initialState
                }

                Timber.d("Initial state validation completed")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize state")
                withContext(Dispatchers.Main) {
                    _stateFlow.value = AppDataState.createDefault()
                }
            }
        }
    }

    suspend fun performFullReset() {
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                // Clear secure storage
                secureStorage.clearGoogleCredentials()

                // Clear photo cache
                photoCache.cleanup()

                // Clear app preferences
                appPreferences.clearAll()

                // Clear main preferences
                withContext(Dispatchers.IO) {
                    preferences.edit().clear().commit()
                }

                // Clear backup state
                withContext(Dispatchers.IO) {
                    context.getSharedPreferences("${PREFERENCES_NAME}${BACKUP_SUFFIX}", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                }

                // Clear all SharedPreferences files
                clearAllSharedPreferences()

                // Reset to default state
                val defaultState = AppDataState.createDefault()
                saveState(defaultState)

                withContext(Dispatchers.Main) {
                    _stateFlow.value = defaultState
                    _authState.value = AuthState.NotAuthenticated
                }

                // Clear cache directories
                withContext(Dispatchers.IO) {
                    context.cacheDir.deleteRecursively()
                    context.externalCacheDir?.deleteRecursively()
                }

                Timber.d("Full data reset completed")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to perform full data reset")
            throw e
        }
    }

    private suspend fun clearAllSharedPreferences() {
        withContext(Dispatchers.IO) {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists() && prefsDir.isDirectory) {
                prefsDir.listFiles()?.forEach { file ->
                    try {
                        if (file.name.endsWith(".xml")) {
                            val prefsName = file.name.replace(".xml", "")
                            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                                .edit()
                                .clear()
                                .commit()
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error clearing preferences file: ${file.name}")
                    }
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
            coroutineScope.launch(Dispatchers.IO) {
                saveState(newState)
                withContext(Dispatchers.Main) {
                    _stateFlow.value = newState
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update state")
            // Revert to last known good state
            coroutineScope.launch {
                _stateFlow.value = loadState()
            }
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
     * Signs out the current user
     */
    fun signOut() {
        try {
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
        coroutineScope.launch(Dispatchers.IO) {
            saveState(defaultState)
            withContext(Dispatchers.Main) {
                _stateFlow.value = defaultState
                _authState.value = AuthState.NotAuthenticated
            }
            coroutineScope.launch {
                signOut()
            }
        }
    }

    private suspend fun saveState(state: AppDataState) {
        withContext(Dispatchers.IO) {
            try {
                // Defensive: remove any nulls before serialization
                val safeState = state.copy(
                    selectedLocalPhotos = state.selectedLocalPhotos.filterNotNull().toSet(),
                    photoSources = state.photoSources.filterNotNull().toSet(),
                    selectedAlbums = state.selectedAlbums.filterNotNull().toSet(),
                    selectedLocalFolders = state.selectedLocalFolders.filterNotNull().toSet(),
                    activeDays = state.activeDays.filterNotNull().toSet()
                )
                // First save to backup
                context.getSharedPreferences("${PREFERENCES_NAME}${BACKUP_SUFFIX}", Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_APP_STATE, gson.toJson(safeState))
                    .commit()

                // Then save to main storage
                preferences.edit()
                    .putString(KEY_APP_STATE, gson.toJson(safeState))
                    .commit()
            } catch (e: Exception) {
                Timber.e(e, "Failed to save state")
                throw e
            }
        }
    }


    private suspend fun loadState(): AppDataState {
        return withContext(Dispatchers.IO) {
            try {
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
    }

    fun createBackup(state: AppDataState) {
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

    internal suspend fun loadBackupState(): AppDataState {
        return withContext(Dispatchers.IO) {
            try {
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
    }

    sealed class AuthState {
        object NotAuthenticated : AuthState()
        data class Authenticated(val email: String) : AuthState()
        object TokenExpired : AuthState()
        data class Error(val exception: Exception) : AuthState()
    }
}