package com.example.screensaver

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.example.screensaver.lock.PhotoLockActivity
import com.example.screensaver.lock.PhotoLockDeviceAdmin
import com.example.screensaver.lock.PhotoLockScreenService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import androidx.appcompat.widget.Toolbar
import com.example.screensaver.settings.SettingsFragment

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Set up toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private var devicePolicyManager: DevicePolicyManager? = null
        private lateinit var adminComponentName: ComponentName

        private val deviceAdminLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            updateLockScreenStatus()
            if (isDeviceAdminActive()) {
                startLockScreenService()
            } else {
                showFeedback(R.string.lock_screen_admin_denied)
                resetDisplayModePreference()
            }
        }

        companion object {
            private const val TAG = "SettingsFragment"
            private const val DEFAULT_DISPLAY_MODE = "dream_service"
            private const val LOCK_SCREEN_MODE = "lock_screen"
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            initializeComponents()
            setupPreferences()
        }

        override fun onResume() {
            super.onResume()
            updateLockScreenStatus()
        }

        private fun initializeComponents() {
            devicePolicyManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            adminComponentName = ComponentName(requireContext(), PhotoLockDeviceAdmin::class.java)
        }

        private fun setupPreferences() {
            setupDisplayModePreference()
            setupLockScreenPreview()
            setupLockScreenStatus()
            setupIntervalPreference()
            setupTransitionPreference()
        }

        private fun setupDisplayModePreference() {
            findPreference<ListPreference>("display_mode_selection")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    handleDisplayModeChange(newValue as String)
                }
            }
        }

        private fun handleDisplayModeChange(newMode: String): Boolean {
            return when (newMode) {
                DEFAULT_DISPLAY_MODE -> {
                    lifecycleScope.launch {
                        disableLockScreenMode()
                        enableDreamService()
                    }
                    true
                }
                LOCK_SCREEN_MODE -> {
                    lifecycleScope.launch {
                        if (enableLockScreenMode()) {
                            disableDreamService()
                        } else {
                            resetDisplayModePreference()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        private fun enableLockScreenMode(): Boolean {
            return try {
                if (!isDeviceAdminActive()) {
                    requestDeviceAdmin()
                    // Since requestDeviceAdmin launches an activity for result,
                    // we return false here and handle the result in deviceAdminLauncher
                    false
                } else {
                    startLockScreenService()
                    true
                }
            } catch (e: Exception) {
                handleError("Error enabling lock screen mode", e)
                false
            }
        }

        private fun requestDeviceAdmin() {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.device_admin_explanation))
            }
            deviceAdminLauncher.launch(intent)
        }

        private fun disableLockScreenMode() {
            try {
                if (isDeviceAdminActive()) {
                    devicePolicyManager?.removeActiveAdmin(adminComponentName)
                }
                stopLockScreenService()
                showFeedback(R.string.lock_screen_disabled)
            } catch (e: Exception) {
                handleError("Error disabling lock screen mode", e)
            }
        }

        private fun startLockScreenService(): Boolean {
            return try {
                Intent(requireContext(), PhotoLockScreenService::class.java).also { intent ->
                    requireContext().startService(intent)
                    showFeedback(R.string.lock_screen_enabled)
                }
                true
            } catch (e: Exception) {
                handleError("Error starting lock screen service", e)
                false
            }
        }

        private fun stopLockScreenService() {
            try {
                Intent(requireContext(), PhotoLockScreenService::class.java).also { intent ->
                    requireContext().stopService(intent)
                }
            } catch (e: Exception) {
                handleError("Error stopping lock screen service", e)
            }
        }

        private fun enableDreamService() {
            try {
                startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                showFeedback(R.string.dream_service_settings)
            } catch (e: Exception) {
                handleError("Error enabling dream service", e)
            }
        }

        private fun disableDreamService() {
            try {
                startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                showFeedback(R.string.dream_service_disable)
            } catch (e: Exception) {
                handleError("Error disabling dream service", e)
            }
        }

        private fun setupLockScreenPreview() {
            findPreference<Preference>("preview_lock_screen")?.apply {
                isVisible = isLockScreenModeSelected()
                setOnPreferenceClickListener {
                    showLockScreenPreview()
                    true
                }
            }
        }

        private fun showLockScreenPreview() {
            try {
                Intent(requireContext(), PhotoLockActivity::class.java).apply {
                    putExtra("preview_mode", true)
                    startActivity(this)
                }
            } catch (e: Exception) {
                handleError("Error showing preview", e)
            }
        }

        private fun setupLockScreenStatus() {
            findPreference<Preference>("lock_screen_status")?.isVisible = isLockScreenModeSelected()
        }

        private fun updateLockScreenStatus() {
            findPreference<Preference>("lock_screen_status")?.apply {
                val status = when {
                    !isLockScreenModeSelected() -> {
                        isVisible = false
                        R.string.lock_screen_not_selected
                    }
                    !isDeviceAdminActive() -> {
                        isVisible = true
                        R.string.lock_screen_admin_required
                    }
                    !isServiceRunning() -> {
                        isVisible = true
                        R.string.lock_screen_service_not_running
                    }
                    else -> {
                        isVisible = true
                        R.string.lock_screen_active
                    }
                }
                summary = getString(status)
            }
        }

        private fun isDeviceAdminActive(): Boolean =
            devicePolicyManager?.isAdminActive(adminComponentName) == true

        private fun isServiceRunning(): Boolean {
            val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == PhotoLockScreenService::class.java.name }
        }

        private fun isLockScreenModeSelected(): Boolean =
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("display_mode_selection", DEFAULT_DISPLAY_MODE) == LOCK_SCREEN_MODE

        private fun resetDisplayModePreference() {
            findPreference<ListPreference>("display_mode_selection")?.value = DEFAULT_DISPLAY_MODE
        }

        private fun showFeedback(@StringRes messageResId: Int) {
            view?.let { v ->
                Snackbar.make(v, messageResId, Snackbar.LENGTH_LONG).show()
            }
        }

        private fun handleError(message: String, error: Exception) {
            Log.e(TAG, "$message: ${error.message}", error)
            view?.let { v ->
                // Create the error message without using String.format
                val errorMessage = "${getString(R.string.generic_error)}: ${error.localizedMessage}"
                Snackbar.make(v, errorMessage, Snackbar.LENGTH_LONG)
                    .setAction(R.string.details) {
                        showErrorDialog(error)
                    }.show()
            }
        }

        private fun showErrorDialog(error: Exception) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.error_dialog_title)
                .setMessage(error.stackTraceToString())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun setupIntervalPreference() {
            // Add interval preference setup here
        }

        private fun setupTransitionPreference() {
            // Add transition preference setup here
        }
    }
}