package com.example.screensaver.settings

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.example.screensaver.R
import com.example.screensaver.AlbumSelectionActivity
import com.example.screensaver.lock.PhotoLockActivity
import com.example.screensaver.lock.PhotoLockDeviceAdmin
import com.example.screensaver.lock.PhotoLockScreenService
import com.example.screensaver.shared.GooglePhotosManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    private var devicePolicyManager: DevicePolicyManager? = null
    private lateinit var adminComponentName: ComponentName
    private var googleSignInClient: GoogleSignInClient? = null

    companion object {
        private const val TAG = "SettingsFragment"
        private const val DEFAULT_DISPLAY_MODE = "dream_service"
        private const val LOCK_SCREEN_MODE = "lock_screen"
    }

    // Device Admin result launcher
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

    // Google Sign-in result launcher
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Sign in result received: ${result.resultCode}")
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                handleSignInResult(GoogleSignIn.getSignedInAccountFromIntent(result.data))
            }
            android.app.Activity.RESULT_CANCELED -> {
                Log.w(TAG, "Sign in cancelled by user")
                Toast.makeText(context, "Sign in cancelled", Toast.LENGTH_SHORT).show()
                updateGooglePhotosState(false)
            }
            else -> {
                Log.e(TAG, "Sign in failed with result code: ${result.resultCode}")
                Toast.makeText(context, "Sign in failed", Toast.LENGTH_SHORT).show()
                updateGooglePhotosState(false)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Load preferences in background
            withContext(Dispatchers.IO) {
                setPreferencesFromResource(R.xml.preferences, rootKey)
            }
            // Setup UI on main thread
            withContext(Dispatchers.Main) {
                initializeDeviceAdmin()
                setupPhotoSourcePreferences()
                setupGoogleSignIn()
                setupTestScreensaver()
                setupDisplayModeSelection()
                setupLockScreenPreview()
                setupLockScreenStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateLockScreenStatus()
    }

    private fun initializeDeviceAdmin() {
        devicePolicyManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        adminComponentName = ComponentName(requireContext(), PhotoLockDeviceAdmin::class.java)
    }

    private fun setupDisplayModeSelection() {
        findPreference<ListPreference>("display_mode_selection")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                when (newValue as String) {
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
        }
    }

    private fun setupTestScreensaver() {
        findPreference<Preference>("test_screensaver")?.setOnPreferenceClickListener {
            val displayMode = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("display_mode_selection", "dream_service")

            when (displayMode) {
                "dream_service" -> startScreensaver()
                "lock_screen" -> {
                    val intent = Intent(requireContext(), PhotoLockActivity::class.java)
                    intent.putExtra("preview_mode", true)
                    startActivity(intent)
                }
            }
            true
        }
    }

    private fun setupPhotoSourcePreferences() {
        val photoSourceSelection = findPreference<MultiSelectListPreference>("photo_source_selection")
        val googlePhotosCategory = findPreference<PreferenceCategory>("google_photos_settings")
        val useGooglePhotos = findPreference<SwitchPreferenceCompat>("use_google_photos")
        val selectAlbums = findPreference<Preference>("select_albums")

        Log.d(TAG, "Setting up photo source preferences")

        val currentSources = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")
        Log.d(TAG, "Current photo sources: $currentSources")

        googlePhotosCategory?.isVisible = currentSources.contains("google_photos")

        useGooglePhotos?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                showGoogleSignInPrompt()
                false
            } else {
                signOutCompletely()
                true
            }
        }

        selectAlbums?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                    withContext(Dispatchers.Main) {
                        if (account != null) {
                            startActivity(Intent(requireContext(), AlbumSelectionActivity::class.java))
                        } else {
                            Toast.makeText(context, "Please sign in with Google first", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        handleError("Error launching album selection", e)
                    }
                }
            }
            true
        }

        photoSourceSelection?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selectedSources = newValue as Set<String>
            googlePhotosCategory?.isVisible = selectedSources.contains("google_photos")
            if (!selectedSources.contains("google_photos")) {
                signOutCompletely()
            }
            true
        }
    }

    private fun setupGoogleSignIn() {
        try {
            Log.d(TAG, "Setting up Google Sign In")
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
                .requestIdToken(getString(R.string.google_oauth_client_id))
                .build()

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            findPreference<SwitchPreference>("use_google_photos")?.apply {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                val hasRequiredScope = account?.grantedScopes?.contains(
                    Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
                ) == true
                isChecked = account != null && hasRequiredScope
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupGoogleSignIn", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showGoogleSignInPrompt() {
        try {
            Log.d(TAG, "Starting Google Sign-in prompt")
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
                .requestIdToken(getString(R.string.google_oauth_client_id))
                .build()

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            Log.d(TAG, "Signing out previous account")
            googleSignInClient?.signOut()?.addOnCompleteListener { signOutTask ->
                if (signOutTask.isSuccessful) {
                    Log.d(TAG, "Previous sign-out successful, launching sign-in")
                    val signInIntent = googleSignInClient?.signInIntent
                    signInLauncher.launch(signInIntent)
                } else {
                    Log.e(TAG, "Error clearing previous sign in", signOutTask.exception)
                    Toast.makeText(context, "Error preparing sign in", Toast.LENGTH_SHORT).show()
                    updateGooglePhotosState(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showGoogleSignInPrompt", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            updateGooglePhotosState(false)
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            account?.serverAuthCode?.let { authCode ->
                account.email?.let { email ->
                    exchangeAuthCode(authCode, email)
                }
            } ?: run {
                Log.e(TAG, "No server auth code received")
                Toast.makeText(context, "Failed to get auth code", Toast.LENGTH_SHORT).show()
                updateGooglePhotosState(false)
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed", e)
            Toast.makeText(context, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            updateGooglePhotosState(false)
        }
    }

    private fun exchangeAuthCode(authCode: String, accountEmail: String) {
        lifecycleScope.launch {
            try {
                val tokenEndpoint = "https://oauth2.googleapis.com/token"
                val clientId = getString(R.string.google_oauth_client_id)
                val clientSecret = getString(R.string.google_oauth_client_secret)

                val connection = URL(tokenEndpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = StringBuilder()
                    .append("grant_type=authorization_code")
                    .append("&code=").append(authCode)
                    .append("&client_id=").append(clientId)
                    .append("&client_secret=").append(clientSecret)
                    .append("&redirect_uri=").append("http://localhost")
                    .toString()

                connection.outputStream.use { it.write(postData.toByteArray()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)

                    // Save tokens with correct keys
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString("access_token", jsonResponse.getString("access_token"))
                        .putString("refresh_token", jsonResponse.getString("refresh_token")) // Make sure this key matches
                        .putLong("token_expiration", System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000))
                        .putString("google_account", accountEmail)
                        .apply()

                    updateGooglePhotosState(true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Signed in as $accountEmail", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    Log.e(TAG, "Failed to exchange auth code: $error")
                    updateGooglePhotosState(false)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to complete sign in", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging auth code", e)
                updateGooglePhotosState(false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun signOutCompletely() {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .remove("google_account")
                .remove("access_token")
                .remove("refresh_token")  // Make sure this key matches
                .remove("token_expiration")
                .apply()

            val account = GoogleSignIn.getLastSignedInAccount(requireContext())
            if (account != null) {
                googleSignInClient?.revokeAccess()?.addOnCompleteListener {
                    updateGooglePhotosState(false)
                    Toast.makeText(context, "Signed out completely", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateGooglePhotosState(enabled: Boolean) {
        findPreference<SwitchPreferenceCompat>("use_google_photos")?.isChecked = enabled
        findPreference<Preference>("select_albums")?.isVisible = enabled
    }

    // Lock Screen Related Methods
    private fun enableLockScreenMode(): Boolean {
        return try {
            if (!isDeviceAdminActive()) {
                requestDeviceAdmin()
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
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_explanation)
            )
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

    private fun startScreensaver() {
        try {
            val dreamComponent = requireContext().packageManager
                .resolveService(
                    Intent("android.service.dreams.DreamService")
                        .setPackage(requireContext().packageName),
                    PackageManager.MATCH_DEFAULT_ONLY
                )?.serviceInfo?.name

            if (dreamComponent != null) {
                val intent = Intent(Settings.ACTION_DREAM_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    requireContext(),
                    "Please select and test the screensaver from system settings",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Screensaver service not found",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screensaver: ${e.message}")
            Toast.makeText(
                requireContext(),
                "Failed to start screensaver: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
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
}