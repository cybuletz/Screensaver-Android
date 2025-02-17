package com.example.screensaver.security

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.example.screensaver.MainActivity
import com.example.screensaver.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class SecurityPreferenceFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var securityPreferences: SecurityPreferences

    @Inject
    lateinit var authManager: AppAuthManager

    @Inject
    lateinit var biometricHelper: BiometricHelper

    private var setupPasscodeDialog: PasscodeDialog? = null
    private var firstPasscode: String? = null

    private var pendingSecurityChanges = false
    private var pendingEnable = false

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
                pendingSecurityChanges = true
                pendingEnable = enabled
                if (enabled) {
                    showPasscodeSetupDialog()
                } else {
                    showAuthenticationDialog { authenticated ->
                        if (authenticated) {
                            isChecked = false // Update the UI immediately
                            // Actual disable happens when OK is pressed
                        } else {
                            isChecked = true
                            pendingSecurityChanges = false
                        }
                    }
                }
                false // Don't update state yet
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

    private fun showPasscodeSetupDialog() {
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
            enableSecurity(passcode)
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
                // If we're disabling security, do it immediately
                disableSecurity()
            }
            // If we're enabling security, it's already done through the passcode dialog
            pendingSecurityChanges = false
        }
    }

    fun cancelChanges() {
        if (pendingSecurityChanges) {
            Log.d(TAG, "Canceling pending security changes")

            // Reset to previous security state
            securityPreferences.isSecurityEnabled = true  // Since we're canceling disable

            // Make sure security is re-enabled in MainActivity
            (activity as? MainActivity)?.enableSecurity()

            // Reset UI state to match current preferences
            updatePreferencesState(true)
            findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = true

            pendingSecurityChanges = false
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
                securityPreferences.clearAll()
                authManager.resetAuthenticationState()

                // Get reference to MainActivity and clear security state
                (activity as? MainActivity)?.clearSecurityState()

                withContext(Dispatchers.Main) {
                    updatePreferencesState(false)
                    findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = false
                    pendingSecurityChanges = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Failed to clear security settings", e)
                    findPreference<SwitchPreferenceCompat>("security_enabled")?.isChecked = true
                }
            }
        }
    }

    private fun showAuthenticationDialog(onAuthenticated: (Boolean) -> Unit) {
        when (securityPreferences.authMethod) {
            SecurityPreferences.AUTH_METHOD_BIOMETRIC -> {
                if (biometricHelper.isBiometricAvailable()) {
                    biometricHelper.showBiometricPrompt(
                        activity = requireActivity(),
                        onSuccess = { onAuthenticated(true) },
                        onError = { message ->
                            if (securityPreferences.passcode != null) {
                                showPasscodeAuthDialog(onAuthenticated)
                            } else {
                                onAuthenticated(false)
                            }
                        },
                        onFailed = {
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
        PasscodeDialog.newInstance(
            mode = PasscodeDialog.Mode.VERIFY,
            title = getString(R.string.authentication_required),
            message = getString(R.string.enter_passcode_to_continue)
        ).apply {
            setCallback(object : PasscodeDialog.PasscodeDialogCallback {
                override fun onPasscodeConfirmed(passcode: String) {
                    onAuthenticated(true)
                    dismiss()
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
            isEnabled = enabled
            value = securityPreferences.authMethod
        }

        findPreference<EditTextPreference>("passcode")?.apply {
            isEnabled = enabled
            summary = if (enabled) "Change passcode" else "Set up passcode first"
        }

        findPreference<SwitchPreferenceCompat>("allow_biometric")?.apply {
            isEnabled = enabled && biometricHelper.isBiometricAvailable()
            isChecked = securityPreferences.allowBiometric
            isVisible = biometricHelper.isBiometricAvailable()
        }
    }

    private fun showError(message: String, error: Exception) {
        Log.e(TAG, "$message: ${error.message}", error)
        Toast.makeText(context, "$message: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}