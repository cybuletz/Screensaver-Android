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
import androidx.preference.PreferenceManager

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
        private const val PREF_REMOVE_SECURITY_ON_RESTART = "remove_security_on_restart"
        private const val PREF_REMOVE_SECURITY_ON_MINIMIZE = "remove_security_on_minimize"
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

            // Simple verification without destructive recovery
            verifyPreferences()
            initializeCredentialState()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize secure preferences")
            // Fall back to regular preferences only if encryption is completely unavailable
            securePreferences = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun verifyPreferences() {
        try {
            // Simple read test
            securePreferences.all
        } catch (e: Exception) {
            Timber.e(e, "Error verifying secure preferences, but continuing")
            // Log error but don't take destructive action
        }
    }

    fun setRemoveSecurityOnMinimize(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_REMOVE_SECURITY_ON_MINIMIZE, enabled)
            .apply()
    }

    fun shouldRemoveSecurityOnMinimize(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_REMOVE_SECURITY_ON_MINIMIZE, false)
    }

    fun clearSecurityCredentials() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove("security_enabled")
            .remove("auth_method")
            .remove("passcode")
            .remove("allow_biometric")
            .apply()
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

    fun recordRefreshAttempt() {
        try {
            val attempts = securePreferences.getInt(KEY_REFRESH_ATTEMPTS, 0)
            securePreferences.edit()
                .putInt(KEY_REFRESH_ATTEMPTS, attempts + 1)
                .apply()
        } catch (e: Exception) {
            Timber.e(e, "Failed to record refresh attempt")
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