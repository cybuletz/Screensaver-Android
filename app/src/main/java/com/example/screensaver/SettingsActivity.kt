package com.example.screensaver

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.example.screensaver.lock.PhotoLockActivity
import com.example.screensaver.lock.PhotoLockDeviceAdmin
import com.example.screensaver.lock.PhotoLockScreenService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val TAG = "SettingsFragment"
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            setupPreferences()
        }

        private fun setupPreferences() {
            setupDisplayModePreference()
            setupLockScreenPreview()
            setupLockScreenStatus()
            updateLockScreenStatus()
        }

        private fun setupDisplayModePreference() {
            findPreference<ListPreference>("display_mode_selection")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    when (newValue as String) {
                        "dream_service" -> {
                            disableLockScreenMode()
                            enableDreamService()
                        }
                        "lock_screen" -> {
                            enableLockScreenMode()
                            disableDreamService()
                        }
                    }
                    true
                }
            }
        }

        private fun enableLockScreenMode() {
            try {
                val deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val componentName = ComponentName(requireContext(), PhotoLockDeviceAdmin::class.java)

                if (!deviceManager.isAdminActive(componentName)) {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            getString(R.string.device_admin_explanation))
                    }
                    startActivity(intent)
                }

                Intent(requireContext(), PhotoLockScreenService::class.java).also { intent ->
                    requireContext().startService(intent)
                    showFeedback(R.string.lock_screen_enabled)
                }
            } catch (e: Exception) {
                showError(R.string.lock_screen_enable_error, e)
            } finally {
                updateLockScreenStatus()
            }
        }

        private fun disableLockScreenMode() {
            val deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(requireContext(), PhotoLockDeviceAdmin::class.java)

            if (deviceManager.isAdminActive(componentName)) {
                deviceManager.removeActiveAdmin(componentName)
            }

            Intent(requireContext(), PhotoLockScreenService::class.java).also { intent ->
                requireContext().stopService(intent)
            }
        }

        private fun enableDreamService() {
            try {
                val dreamComponent = ComponentName(requireContext(), PhotoDreamService::class.java)

                // Open system dream settings
                val intent = Intent(Settings.ACTION_DREAM_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)

                showFeedback(R.string.dream_service_settings)
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling dream service", e)
                showError(R.string.dream_service_error, e)
            }
        }

        private fun disableDreamService() {
            try {
                // Open system dream settings to let user disable it
                val intent = Intent(Settings.ACTION_DREAM_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)

                showFeedback(R.string.dream_service_disable)
            } catch (e: Exception) {
                Log.e(TAG, "Error disabling dream service", e)
                showError(R.string.dream_service_error, e)
            }
        }

        private fun setupLockScreenPreview() {
            findPreference<Preference>("preview_lock_screen")?.apply {
                setOnPreferenceClickListener {
                    showLockScreenPreview()
                    true
                }
            }
        }

        private fun showLockScreenPreview() {
            Intent(requireContext(), PhotoLockActivity::class.java).apply {
                putExtra("preview_mode", true)
                startActivity(this)
            }
        }

        private fun setupLockScreenStatus() {
            findPreference<Preference>("lock_screen_status")?.let { pref ->
                pref.isVisible = isLockScreenModeSelected()
            }
        }

        private fun updateLockScreenStatus() {
            findPreference<Preference>("lock_screen_status")?.let { pref ->
                val deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val componentName = ComponentName(requireContext(), PhotoLockDeviceAdmin::class.java)
                val isAdminActive = deviceManager.isAdminActive(componentName)
                val isServiceRunning = isServiceRunning(PhotoLockScreenService::class.java)

                when {
                    !isLockScreenModeSelected() -> {
                        pref.summary = getString(R.string.lock_screen_not_selected)
                        pref.isVisible = false
                    }
                    !isAdminActive -> {
                        pref.summary = getString(R.string.lock_screen_admin_required)
                        pref.isVisible = true
                    }
                    !isServiceRunning -> {
                        pref.summary = getString(R.string.lock_screen_service_not_running)
                        pref.isVisible = true
                    }
                    else -> {
                        pref.summary = getString(R.string.lock_screen_active)
                        pref.isVisible = true
                    }
                }
            }
        }

        private fun isLockScreenModeSelected(): Boolean {
            return PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("display_mode_selection", "dream_service") == "lock_screen"
        }

        private fun isServiceRunning(serviceClass: Class<*>): Boolean {
            val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == serviceClass.name }
        }

        private fun showFeedback(@StringRes messageResId: Int) {
            view?.let { v ->
                Snackbar.make(v, messageResId, Snackbar.LENGTH_LONG).show()
            }
        }

        private fun showError(@StringRes messageResId: Int, error: Exception) {
            Log.e(TAG, "Error: ${error.message}", error)
            view?.let { v ->
                Snackbar.make(
                    v,
                    getString(messageResId) + ": " + error.localizedMessage,
                    Snackbar.LENGTH_LONG
                ).setAction("Details") {
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
    }
}