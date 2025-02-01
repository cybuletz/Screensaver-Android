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
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val securePreferences: SharedPreferences
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
    }

    init {
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

        initializeCredentialState()
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
                putInt(KEY_REFRESH_ATTEMPTS, 0) // Reset refresh attempts
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

            Timber.d("Retrieved credentials - Access Token: ${accessToken != null}, " +
                    "Refresh Token: ${refreshToken != null}, " +
                    "Expiration Time: $expirationTime, " +
                    "Email: $email")

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
            throw SecurityException("Failed to clear credentials", e)
        }
    }

    fun updateAccessToken(accessToken: String, expirationTime: Long) {
        try {
            securePreferences.edit().apply {
                putString(KEY_GOOGLE_ACCESS_TOKEN, accessToken)
                putLong(KEY_TOKEN_EXPIRATION, expirationTime)
                putLong(KEY_LAST_AUTH, Instant.now().epochSecond)
                putInt(KEY_REFRESH_ATTEMPTS, 0) // Reset refresh attempts after successful update
            }.apply()

            // Update the flow with new credentials
            val currentCredentials = getGoogleCredentials()
            if (currentCredentials != null) {
                _credentialsFlow.value = CredentialState.Valid(currentCredentials)
            }

            Timber.d("Access token updated successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update access token")
            _credentialsFlow.value = CredentialState.Error(e)
            throw SecurityException("Failed to update access token", e)
        }
    }

    fun recordRefreshAttempt() {
        val attempts = securePreferences.getInt(KEY_REFRESH_ATTEMPTS, 0)
        securePreferences.edit()
            .putInt(KEY_REFRESH_ATTEMPTS, attempts + 1)
            .apply()
    }

    fun getRefreshAttempts(): Int =
        securePreferences.getInt(KEY_REFRESH_ATTEMPTS, 0)

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
                    Instant.now().epochSecond - lastAuthTime > 3600 // Refresh if last auth was more than 1 hour ago
    }
}