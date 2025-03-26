package com.photostreamr.security

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.example.screensaver.data.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit


@Singleton
class AppAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securityPreferences: SecurityPreferences,
    private val biometricHelper: BiometricHelper,
    private val secureStorage: SecureStorage
) {
    private val TAG = "AppAuthManager"
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var authenticated = false
    private var lockoutEndTime = 0L

    companion object {
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION = 300000L // 5 minutes in milliseconds
    }


    sealed class LockoutState {
        object NotLocked : LockoutState()
        data class Locked(val remainingTime: Long) : LockoutState()
    }

    init {
        updateAuthState()
    }

    private fun updateAuthState() {
        _authState.value = when {
            !securityPreferences.isSecurityEnabled -> AuthState.NotRequired
            isLockedOut() -> AuthState.Error("Too many failed attempts. Try again later.")
            isAuthenticated() -> AuthState.Authenticated
            else -> AuthState.Unauthenticated
        }
    }

    fun setAuthenticated(value: Boolean) {
        Log.d(TAG, "Setting authenticated state to: $value")
        authenticated = value
        if (value) {
            _authState.value = AuthState.Authenticated
            securityPreferences.clearFailedAttempts()
            lockoutEndTime = 0L
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    fun authenticateWithPasscode(passcode: String): Boolean {
        if (!canAttemptAuth()) {
            _authState.value = AuthState.Error("Too many attempts. Please try again later.")
            return false
        }

        val stored = securityPreferences.passcode
        return if (stored == passcode) {
            setAuthenticated(true)
            true
        } else {
            handleFailedAttempt()
            false
        }
    }

    private fun handleFailedAttempt() {
        val attempts = securityPreferences.failedAttempts + 1
        securityPreferences.failedAttempts = attempts

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            lockoutEndTime = System.currentTimeMillis() + LOCKOUT_DURATION
            Log.w(TAG, "Maximum failed attempts reached, locked out until: ${getRemainingLockoutTime()}")
            _authState.value = AuthState.Error("Too many failed attempts. Try again in ${getRemainingLockoutTime()}")
        }
        updateAuthState()
    }

    fun getLockoutState(): LockoutState {
        val currentTime = System.currentTimeMillis()
        return if (currentTime < lockoutEndTime) {
            LockoutState.Locked(lockoutEndTime - currentTime)
        } else {
            if (lockoutEndTime != 0L) {
                // Reset when lockout expires
                securityPreferences.clearFailedAttempts()
                lockoutEndTime = 0L
            }
            LockoutState.NotLocked
        }
    }

    fun getRemainingLockoutTime(): String {
        val remainingMillis = lockoutEndTime - System.currentTimeMillis()
        if (remainingMillis <= 0) return ""

        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMillis) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun isLockedOut(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime >= lockoutEndTime) {
            // Lockout period has expired
            if (lockoutEndTime != 0L) {
                // Reset failed attempts after lockout expires
                securityPreferences.clearFailedAttempts()
                lockoutEndTime = 0L
            }
            return false
        }
        return true
    }

    fun isAuthenticated(): Boolean = authenticated

    fun resetAuthenticationState() {
        Log.d(TAG, "Resetting authentication state")
        authenticated = false
        _authState.value = AuthState.Unauthenticated
    }

    fun authenticateWithBiometric(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!securityPreferences.allowBiometric) {
            onError("Biometric authentication not enabled")
            return
        }

        if (!biometricHelper.isBiometricAvailable()) {
            onError("Biometric authentication not available")
            return
        }

        biometricHelper.showBiometricPrompt(
            activity = activity,
            onSuccess = {
                _authState.value = AuthState.Authenticated
                securityPreferences.clearFailedAttempts()
                onSuccess()
            },
            onError = { message ->
                _authState.value = AuthState.Error(message)
                onError(message)
            },
            onFailed = {
                handleFailedAttempt()
                onError("Authentication failed")
            }
        )
    }

    fun logout() {
        authenticated = false
        _authState.value = AuthState.Unauthenticated
        // Don't reset failed attempts here
    }

    fun canAttemptAuth(): Boolean {
        return !isLockedOut()
    }
}