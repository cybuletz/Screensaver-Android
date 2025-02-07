package com.example.screensaver.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import java.security.KeyStore

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private lateinit var securePreferences: SharedPreferences
    private val _credentialsFlow = MutableStateFlow<CredentialState>(CredentialState.NotInitialized)
    val credentialsFlow: StateFlow<CredentialState> = _credentialsFlow.asStateFlow()

    companion object {
        private const val SECURE_PREFS_NAME = "secure_storage"
        private const val KEY_GOOGLE_ACCESS_TOKEN = "google_access_token"
        private const val KEY_GOOGLE_REFRESH_TOKEN = "google_refresh_token"
        private const val KEY_TOKEN_EXPIRATION = "token_expiration"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_LAST_AUTH = "last_auth_time"
        private const val KEY_REFRESH_ATTEMPTS = "refresh_attempts"

        private const val FALLBACK_PREFS_NAME = "secure_storage_fallback"
        private const val EMERGENCY_PREFS_NAME = "secure_storage_emergency"
    }

    init {
        initializeSecurePreferences()
    }

    private fun initializeSecurePreferences() {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            securePreferences = EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            verifyAndRepairPreferences()
            initializeCredentialState()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize secure preferences, attempting recovery")
            handleInitializationError()
        }
    }

    private fun verifyAndRepairPreferences() {
        try {
            // Try to read any value to verify the preferences are working
            securePreferences.all
        } catch (e: Exception) {
            Timber.e(e, "Error verifying secure preferences")
            handleInitializationError()
        }
    }

    private fun handleInitializationError() {
        try {
            // Delete the existing preferences file
            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$SECURE_PREFS_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
            }

            // Try to delete the master key
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            } catch (e: Exception) {
                Timber.e(e, "Error deleting master key, continuing anyway")
            }

            // Create new master key
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            // Try to create new encrypted preferences with a different name
            securePreferences = try {
                EncryptedSharedPreferences.create(
                    context,
                    FALLBACK_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to create fallback encrypted preferences, falling back to regular preferences")
                // Fall back to unencrypted preferences as last resort
                context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            }

            // Reset state
            _credentialsFlow.value = CredentialState.NotInitialized
            Timber.d("Successfully recovered from secure storage initialization error")
        } catch (e: Exception) {
            Timber.e(e, "Failed to recover from secure storage initialization error")
            // Create emergency fallback preferences
            securePreferences = context.getSharedPreferences(
                EMERGENCY_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            _credentialsFlow.value = CredentialState.Error(e)
        }
    }

    private fun initializeCredentialState() {
        try {
            val credentials = getGoogleCredentials()
            _credentialsFlow.value = when {
                credentials == null -> CredentialState.NotInitialized
                credentials.isExpired -> CredentialState.Expired(credentials)
                else -> CredentialState.Valid(credentials)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize credential state")
            _credentialsFlow.value = CredentialState.Error(e)
            handleInitializationError()
        }
    }

    fun saveGoogleCredentials(
        accessToken: String,
        refreshToken: String,
        expirationTime: Long,
        email: String
    ) {
        try {
            securePreferences.edit().apply {
                putString(KEY_GOOGLE_ACCESS_TOKEN, accessToken)
                putString(KEY_GOOGLE_REFRESH_TOKEN, refreshToken)
                putLong(KEY_TOKEN_EXPIRATION, expirationTime)
                putString(KEY_ACCOUNT_EMAIL, email)
                putLong(KEY_LAST_AUTH, Instant.now().epochSecond)
                putInt(KEY_REFRESH_ATTEMPTS, 0)
            }.apply()

            val credentials = GoogleCredentials(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expirationTime = expirationTime,
                email = email,
                lastAuthTime = Instant.now().epochSecond
            )
            _credentialsFlow.value = CredentialState.Valid(credentials)

            Timber.d("Google credentials saved successfully for $email")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save Google credentials")
            _credentialsFlow.value = CredentialState.Error(e)
            handleInitializationError()
            throw SecurityException("Failed to save credentials securely", e)
        }
    }

    fun getGoogleCredentials(): GoogleCredentials? {
        return try {
            val accessToken = securePreferences.getString(KEY_GOOGLE_ACCESS_TOKEN, null)
            val refreshToken = securePreferences.getString(KEY_GOOGLE_REFRESH_TOKEN, null)
            val expirationTime = securePreferences.getLong(KEY_TOKEN_EXPIRATION, 0)
            val email = securePreferences.getString(KEY_ACCOUNT_EMAIL, null)
            val lastAuth = securePreferences.getLong(KEY_LAST_AUTH, 0)

            if (accessToken != null && refreshToken != null && email != null) {
                GoogleCredentials(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expirationTime = expirationTime,
                    email = email,
                    lastAuthTime = lastAuth
                )
            } else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to retrieve Google credentials")
            handleInitializationError()
            null
        }
    }

    fun clearGoogleCredentials() {
        try {
            securePreferences.edit().apply {
                remove(KEY_GOOGLE_ACCESS_TOKEN)
                remove(KEY_GOOGLE_REFRESH_TOKEN)
                remove(KEY_TOKEN_EXPIRATION)
                remove(KEY_ACCOUNT_EMAIL)
                remove(KEY_LAST_AUTH)
                remove(KEY_REFRESH_ATTEMPTS)
            }.apply()
            _credentialsFlow.value = CredentialState.NotInitialized
            Timber.d("Google credentials cleared successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear Google credentials")
            _credentialsFlow.value = CredentialState.Error(e)
            handleInitializationError()
            throw SecurityException("Failed to clear credentials", e)
        }
    }

    fun updateAccessToken(accessToken: String, expirationTime: Long) {
        try {
            securePreferences.edit().apply {
                putString(KEY_GOOGLE_ACCESS_TOKEN, accessToken)
                putLong(KEY_TOKEN_EXPIRATION, expirationTime)
                putLong(KEY_LAST_AUTH, Instant.now().epochSecond)
                putInt(KEY_REFRESH_ATTEMPTS, 0)
            }.apply()

            val currentCredentials = getGoogleCredentials()
            if (currentCredentials != null) {
                _credentialsFlow.value = CredentialState.Valid(currentCredentials)
            }

            Timber.d("Access token updated successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update access token")
            _credentialsFlow.value = CredentialState.Error(e)
            handleInitializationError()
            throw SecurityException("Failed to update access token", e)
        }
    }

    fun recordRefreshAttempt() {
        try {
            val attempts = securePreferences.getInt(KEY_REFRESH_ATTEMPTS, 0)
            securePreferences.edit()
                .putInt(KEY_REFRESH_ATTEMPTS, attempts + 1)
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "Failed to record refresh attempt")
            handleInitializationError()
        }
    }

    fun getRefreshAttempts(): Int {
        return try {
            securePreferences.getInt(KEY_REFRESH_ATTEMPTS, 0)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get refresh attempts")
            handleInitializationError()
            0
        }
    }

    sealed class CredentialState {
        object NotInitialized : CredentialState()
        data class Valid(val credentials: GoogleCredentials) : CredentialState()
        data class Expired(val credentials: GoogleCredentials) : CredentialState()
        data class Error(val exception: Exception) : CredentialState()
    }

    data class GoogleCredentials(
        val accessToken: String,
        val refreshToken: String,
        val expirationTime: Long,
        val email: String,
        val lastAuthTime: Long
    ) {
        val isExpired: Boolean
            get() = Instant.now().epochSecond >= expirationTime

        val needsRefresh: Boolean
            get() = isExpired ||
                    Instant.now().epochSecond - lastAuthTime > 3600
    }
}