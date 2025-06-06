package com.photostreamr.security

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.photostreamr.MainActivity
import com.photostreamr.data.SecureStorage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.photostreamr.R

@AndroidEntryPoint
class SecurityPreferenceFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var securityPreferences: SecurityPreferences

    @Inject
    lateinit var authManager: AppAuthManager

    @Inject
    lateinit var biometricHelper: BiometricHelper

    @Inject
    lateinit var secureStorage: SecureStorage

    private var setupPasscodeDialog: PasscodeDialog? = null
    private var firstPasscode: String? = null

    private var pendingSecurityChanges = false
    private var pendingEnable = false
    private var pendingDisableAuthentication = false
    private var pendingPasscode: String? = null


    companion object {
        private const val TAG = "SecurityPreferenceFragment"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.security_preferences, rootKey)
        setupPreferences()
        updatePreferencesState(securityPreferences.isSecurityEnabled)
    }

    private fun setupPreferences() {
        findPreference<SwitchPreferenceCompat>("security_enabled")?.apply {
            isChecked = securityPreferences.isSecurityEnabled
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (securityPreferences.isLockedOut()) {
                    showLockoutError()
                    false
                } else {
                    pendingSecurityChanges = true
                    pendingEnable = enabled
                    if (enabled) {
                        showPasscodeSetupDialog()
                    } else {
                        pendingDisableAuthentication = true
                        isChecked = false
                    }
                    false
                }
            }
        }

        findPreference<ListPreference>("auth_method")?.apply {
            isEnabled = securityPreferences.isSecurityEnabled
            value = securityPreferences.authMethod
            setOnPreferenceChangeListener { _, newValue ->
                securityPreferences.authMethod = newValue as String
                true
            }
        }

        findPreference<EditTextPreference>("passcode")?.apply {
            isEnabled = securityPreferences.isSecurityEnabled
            summary = if (securityPreferences.isSecurityEnabled) "Change passcode" else "Set up passcode first"
        }

        findPreference<SwitchPreferenceCompat>("allow_biometric")?.apply {
            isEnabled = securityPreferences.isSecurityEnabled && biometricHelper.isBiometricAvailable()
            isChecked = securityPreferences.allowBiometric
            isVisible = biometricHelper.isBiometricAvailable()
            setOnPreferenceChangeListener { _, newValue ->
                securityPreferences.allowBiometric = newValue as Boolean
                true
            }
        }
    }

    private fun showLockoutError() {
        val remainingTime = securityPreferences.getFormattedRemainingLockoutTime()
        Toast.makeText(
            context,
            getString(R.string.lockout_error_message, remainingTime),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showPasscodeSetupDialog() {
        if (securityPreferences.isLockedOut()) {
            showLockoutError()
            return
        }

        firstPasscode = null
        setupPasscodeDialog?.dismiss()

        setupPasscodeDialog = PasscodeDialog.newInstance(
            mode = PasscodeDialog.Mode.SET_NEW,
            title = getString(R.string.set_passcode),
            message = getString(R.string.set_passcode_message)
        ).apply {
            setCallback(object : PasscodeDialog.PasscodeDialogCallback {
                override fun onPasscodeConfirmed(passcode: String) {
                    handlePasscodeSetup(passcode)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Dialog error: $message")
                    findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = false
                    setupPasscodeDialog = null
                    firstPasscode = null
                }

                override fun onDismiss() {
                    if (!securityPreferences.isSecurityEnabled) {
                        findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = false
                    }
                    setupPasscodeDialog = null
                    firstPasscode = null
                }
            })
        }

        setupPasscodeDialog?.show(childFragmentManager, "setup_passcode")
    }

    private fun handlePasscodeSetup(passcode: String) {
        if (firstPasscode == null) {
            firstPasscode = passcode
            setupPasscodeDialog?.updateDialog(
                title = getString(R.string.confirm_passcode),
                message = getString(R.string.confirm_passcode_message)
            )
        } else if (passcode == firstPasscode) {
            // Store passcode for later instead of enabling immediately
            pendingPasscode = passcode
            setupPasscodeDialog?.dismiss()
            // Keep the switch visually on
            findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = true
        } else {
            firstPasscode = null
            Toast.makeText(context, getString(R.string.passcode_mismatch), Toast.LENGTH_SHORT).show()
            setupPasscodeDialog?.updateDialog(
                title = getString(R.string.set_passcode),
                message = getString(R.string.set_passcode_message)
            )
        }
    }

    fun applyChanges() {
        if (pendingSecurityChanges) {
            if (!pendingEnable) {
                // Existing disable logic remains unchanged
                showAuthenticationDialog { authenticated ->
                    if (authenticated) {
                        disableSecurity()
                    } else {
                        findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = true
                        pendingSecurityChanges = false
                        pendingDisableAuthentication = false
                        pendingEnable = true
                    }
                }
            } else if (pendingPasscode != null) {
                // Now actually enable security with the stored passcode
                enableSecurity(pendingPasscode!!)
                pendingPasscode = null
            }
        }
    }

    fun cancelChanges() {
        if (pendingSecurityChanges) {
            Log.d(TAG, "Canceling pending security changes")

            if (pendingDisableAuthentication) {
                findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = true
                pendingDisableAuthentication = false
            } else if (pendingEnable) {
                // Revert the switch if we were enabling security
                findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = false
            }

            // Clear pending states
            pendingPasscode = null
            firstPasscode = null
            pendingSecurityChanges = false
            pendingEnable = false
        }
    }

    private fun enableSecurity(passcode: String) {
        lifecycleScope.launch {
            try {
                val useBiometric = biometricHelper.isBiometricAvailable()

                securityPreferences.run {
                    this.passcode = passcode
                    this.authMethod = if (useBiometric)
                        SecurityPreferences.AUTH_METHOD_BIOMETRIC
                    else SecurityPreferences.AUTH_METHOD_PASSCODE
                    this.allowBiometric = useBiometric
                    this.isSecurityEnabled = true
                }

                authManager.setAuthenticated(true)

                withContext(Dispatchers.Main) {
                    updatePreferencesState(true)
                    findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = true
                    pendingSecurityChanges = true
                    pendingEnable = true
                    setupPasscodeDialog?.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save security settings", e)
                withContext(Dispatchers.Main) {
                    findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = false
                    Toast.makeText(context, getString(R.string.security_setup_failed), Toast.LENGTH_SHORT).show()
                    setupPasscodeDialog?.dismiss()
                }
            }
        }
    }

    private fun disableSecurity() {
        lifecycleScope.launch {
            try {
                // Clear all security preferences
                securityPreferences.clearAll()

                // Reset authentication state
                authManager.resetAuthenticationState()

                // Clear any stored credentials in secure storage
                secureStorage.clearSecurityCredentials()

                // Only keep the minimize flag setting
                val wasMinimizeEnabled = secureStorage.shouldRemoveSecurityOnMinimize()
                secureStorage.setRemoveSecurityOnMinimize(wasMinimizeEnabled)

                // Update the security enabled flag last
                securityPreferences.isSecurityEnabled = false

                // Get reference to MainActivity and clear all security states
                (activity as? MainActivity)?.clearSecurityState()

                withContext(Dispatchers.Main) {
                    // Update UI state
                    updatePreferencesState(false)
                    findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = false

                    // Reset all pending flags
                    pendingSecurityChanges = false
                    pendingEnable = false
                    pendingDisableAuthentication = false

                    // Ensure biometric preference is reset
                    findPreference<SwitchPreferenceCompat>("allow_biometric")?.apply {
                        isChecked = false
                        isEnabled = false
                    }

                    // Reset auth method
                    findPreference<ListPreference>("auth_method")?.apply {
                        value = SecurityPreferences.AUTH_METHOD_NONE
                        isEnabled = false
                    }
                }

                Log.d(TAG, "Security completely disabled and all related data cleared")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Failed to clear security settings", e)
                    findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = true
                }
            }
        }
    }

    private fun showAuthenticationDialog(onAuthenticated: (Boolean) -> Unit) {
        if (securityPreferences.isLockedOut()) {
            showLockoutError()
            onAuthenticated(false)
            return
        }

        when (securityPreferences.authMethod) {
            SecurityPreferences.AUTH_METHOD_BIOMETRIC -> {
                if (securityPreferences.allowBiometric && biometricHelper.isBiometricAvailable()) {
                    biometricHelper.showBiometricPrompt(
                        activity = requireActivity(),
                        onSuccess = {
                            securityPreferences.clearFailedAttempts()
                            onAuthenticated(true)
                        },
                        onError = { message ->
                            if (securityPreferences.passcode != null) {
                                showPasscodeAuthDialog(onAuthenticated)
                            } else {
                                onAuthenticated(false)
                            }
                        },
                        onFailed = {
                            securityPreferences.incrementFailedAttempts()
                            if (securityPreferences.passcode != null) {
                                showPasscodeAuthDialog(onAuthenticated)
                            } else {
                                onAuthenticated(false)
                            }
                        }
                    )
                } else {
                    showPasscodeAuthDialog(onAuthenticated)
                }
            }
            else -> showPasscodeAuthDialog(onAuthenticated)
        }
    }

    private fun showPasscodeAuthDialog(onAuthenticated: (Boolean) -> Unit) {
        if (securityPreferences.isLockedOut()) {
            showLockoutError()
            onAuthenticated(false)
            return
        }

        PasscodeDialog.newInstance(
            mode = PasscodeDialog.Mode.VERIFY,
            title = getString(R.string.authentication_required),
            message = getString(R.string.enter_passcode_to_continue)
        ).apply {
            setCallback(object : PasscodeDialog.PasscodeDialogCallback {
                override fun onPasscodeConfirmed(passcode: String) {
                    if (securityPreferences.validatePasscode(passcode)) {
                        onAuthenticated(true)
                        dismiss()
                    } else {
                        if (securityPreferences.isLockedOut()) {
                            showLockoutError()
                            dismiss()
                        } else {
                            val remainingAttempts = SecurityPreferences.MAX_FAILED_ATTEMPTS -
                                    securityPreferences.failedAttempts
                            Toast.makeText(
                                context,
                                getString(R.string.invalid_passcode_attempts_remaining, remainingAttempts),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                override fun onError(message: String) {
                    onAuthenticated(false)
                }

                override fun onDismiss() {
                    onAuthenticated(false)
                }
            })
        }.show(childFragmentManager, "verify_passcode")
    }

    private fun updatePreferencesState(enabled: Boolean) {
        findPreference<ListPreference>("auth_method")?.apply {
            isEnabled = enabled && !securityPreferences.isLockedOut()
            value = securityPreferences.authMethod
        }

        findPreference<EditTextPreference>("passcode")?.apply {
            isEnabled = enabled && !securityPreferences.isLockedOut()
            summary = if (enabled) "Change passcode" else "Set up passcode first"
        }

        findPreference<SwitchPreferenceCompat>("allow_biometric")?.apply {
            isEnabled = enabled && biometricHelper.isBiometricAvailable() &&
                    !securityPreferences.isLockedOut()
            isChecked = securityPreferences.allowBiometric
            isVisible = biometricHelper.isBiometricAvailable()
        }
    }

    private fun showError(message: String, error: Exception) {
        Log.e(TAG, "$message: ${error.message}", error)
        Toast.makeText(context, "$message: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
    }


}