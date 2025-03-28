package com.photostreamr.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.photostreamr.data.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@Singleton
class SecurityPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    private val TAG = "SecurityPreferences"
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        private const val SECURITY_PREFS_FILE = "security_prefs"
        private const val KEY_SECURITY_ENABLED = "security_enabled"
        private const val KEY_AUTH_METHOD = "auth_method"
        private const val KEY_PASSCODE = "passcode"
        private const val KEY_ALLOW_BIOMETRIC = "allow_biometric"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_END_TIME = "lockout_end_time"
        const val AUTH_METHOD_NONE = "none"
        const val AUTH_METHOD_PASSCODE = "passcode"
        const val AUTH_METHOD_BIOMETRIC = "biometric"

        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION = 300000L // 5 minutes in milliseconds
    }

    private val securityPrefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runBlocking(Dispatchers.IO) {
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
    }

    private val _securityEnabledFlow = MutableStateFlow(isSecurityEnabled)
    val securityEnabledFlow: Flow<Boolean> = _securityEnabledFlow.asStateFlow()

    var isSecurityEnabled: Boolean
        get() = sharedPreferences.getBoolean("security_enabled", false)
        set(value) {
            sharedPreferences.edit().putBoolean("security_enabled", value).apply()
            _securityEnabledFlow.value = value
        }

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

    private var lockoutEndTime: Long
        get() = securityPrefs.getLong(KEY_LOCKOUT_END_TIME, 0L)
        set(value) = securityPrefs.edit().putLong(KEY_LOCKOUT_END_TIME, value).apply()

    fun clearFailedAttempts() {
        securityPrefs.edit()
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_LOCKOUT_END_TIME)
            .apply()
    }

    fun clearAll() {
        securityPrefs.edit().clear().apply()
    }

    // New security management functions
    fun isLockedOut(): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime < lockoutEndTime
    }

    fun incrementFailedAttempts() {
        failedAttempts = failedAttempts + 1
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockoutEndTime = System.currentTimeMillis() + LOCKOUT_DURATION
            Log.d(TAG, "Maximum failed attempts reached. Locked out until: ${getRemainingLockoutTime()}")
        }
    }

    fun getRemainingLockoutTime(): Long {
        return maxOf(0L, lockoutEndTime - System.currentTimeMillis())
    }

    fun validatePasscode(passcode: String): Boolean {
        if (isLockedOut()) {
            Log.d(TAG, "Authentication attempted while locked out. Remaining time: ${getRemainingLockoutTime()}")
            return false
        }

        return if (passcode == this.passcode) {
            clearFailedAttempts()
            true
        } else {
            incrementFailedAttempts()
            Log.d(TAG, "Invalid passcode attempt. Failed attempts: $failedAttempts")
            false
        }
    }

    fun getFormattedRemainingLockoutTime(): String {
        val remainingMillis = getRemainingLockoutTime()
        if (remainingMillis <= 0) return ""

        val minutes = remainingMillis / 60000
        val seconds = (remainingMillis % 60000) / 1000
        return String.format("%02d:%02d", minutes, seconds)
    }
}