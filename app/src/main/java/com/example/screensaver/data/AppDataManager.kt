package com.example.screensaver.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import com.example.screensaver.utils.AppPreferences

/**
 * Manages the persistence and retrieval of application data state.
 * Uses both regular and encrypted SharedPreferences for storing sensitive data.
 */
@Singleton
class AppDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
    private val appPreferences: AppPreferences
) {
    private val preferences: SharedPreferences
    private val securePreferences: SharedPreferences

    private val _stateFlow = MutableStateFlow(loadState())
    val stateFlow = _stateFlow.asStateFlow()

    companion object {
        private const val PREFERENCES_NAME = "app_data_preferences"
        private const val SECURE_PREFERENCES_NAME = "secure_app_data"
        private const val KEY_APP_STATE = "app_state"
        private const val KEY_GOOGLE_ACCESS_TOKEN = "google_access_token"
        private const val KEY_GOOGLE_REFRESH_TOKEN = "google_refresh_token"
        private const val KEY_TOKEN_EXPIRATION = "token_expiration"
        private const val BACKUP_SUFFIX = "_backup"
    }

    init {
        // Initialize regular preferences
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        // Initialize encrypted preferences
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        securePreferences = EncryptedSharedPreferences.create(
            context,
            SECURE_PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Register listener for state changes
        preferences.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_APP_STATE) {
                _stateFlow.value = loadState()
            }
        }

        // Perform initial state validation
        validateAndRepairState()
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
     * Saves Google authentication tokens securely
     */
    fun saveGoogleTokens(
        accessToken: String,
        refreshToken: String,
        expirationTime: Long
    ) {
        securePreferences.edit {
            putString(KEY_GOOGLE_ACCESS_TOKEN, accessToken)
            putString(KEY_GOOGLE_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRATION, expirationTime)
        }
    }

    /**
     * Retrieves Google authentication tokens
     */
    fun getGoogleTokens(): GoogleTokens? {
        return try {
            val accessToken = securePreferences.getString(KEY_GOOGLE_ACCESS_TOKEN, null)
            val refreshToken = securePreferences.getString(KEY_GOOGLE_REFRESH_TOKEN, null)
            val expiration = securePreferences.getLong(KEY_TOKEN_EXPIRATION, 0)

            if (accessToken != null && refreshToken != null) {
                GoogleTokens(accessToken, refreshToken, expiration)
            } else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve Google tokens")
            null
        }
    }

    /**
     * Clears all Google authentication tokens
     */
    fun clearGoogleTokens() {
        securePreferences.edit {
            remove(KEY_GOOGLE_ACCESS_TOKEN)
            remove(KEY_GOOGLE_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRATION)
        }
    }

    /**
     * Resets all application data to defaults
     */
    fun resetToDefaults() {
        val defaultState = AppDataState.createDefault()
        saveState(defaultState)
        _stateFlow.value = defaultState
        clearGoogleTokens()
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

    /**
     * Data class for Google authentication tokens
     */
    data class GoogleTokens(
        val accessToken: String,
        val refreshToken: String,
        val expirationTime: Long
    ) {
        val isExpired: Boolean
            get() = Instant.now().epochSecond >= expirationTime
    }
}