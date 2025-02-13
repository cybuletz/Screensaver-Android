package com.example.screensaver.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.screensaver.data.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    private val TAG = "SecurityPreferences"

    companion object {
        private const val SECURITY_PREFS_FILE = "security_prefs"
        private const val KEY_SECURITY_ENABLED = "security_enabled"
        private const val KEY_AUTH_METHOD = "auth_method"
        private const val KEY_PASSCODE = "passcode"
        private const val KEY_ALLOW_BIOMETRIC = "allow_biometric"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val AUTH_METHOD_NONE = "none"
        const val AUTH_METHOD_PASSCODE = "passcode"
        const val AUTH_METHOD_BIOMETRIC = "biometric"
    }

    private val securityPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURITY_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating encrypted preferences", e)
            context.getSharedPreferences(SECURITY_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    var isSecurityEnabled: Boolean
        get() = securityPrefs.getBoolean(KEY_SECURITY_ENABLED, false)
        set(value) = securityPrefs.edit().putBoolean(KEY_SECURITY_ENABLED, value).apply()

    var authMethod: String
        get() = securityPrefs.getString(KEY_AUTH_METHOD, "passcode") ?: "passcode"
        set(value) = securityPrefs.edit().putString(KEY_AUTH_METHOD, value).apply()

    var passcode: String?
        get() = securityPrefs.getString(KEY_PASSCODE, null)
        set(value) = securityPrefs.edit().putString(KEY_PASSCODE, value).apply()

    var allowBiometric: Boolean
        get() = securityPrefs.getBoolean(KEY_ALLOW_BIOMETRIC, false)
        set(value) = securityPrefs.edit().putBoolean(KEY_ALLOW_BIOMETRIC, value).apply()

    var failedAttempts: Int
        get() = securityPrefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) = securityPrefs.edit().putInt(KEY_FAILED_ATTEMPTS, value).apply()

    fun clearFailedAttempts() {
        securityPrefs.edit().remove(KEY_FAILED_ATTEMPTS).apply()
    }

    fun clearAll() {
        securityPrefs.edit().clear().apply()
    }
}