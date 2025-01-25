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
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.preference.*
import com.example.screensaver.R
import com.example.screensaver.AlbumSelectionActivity
import com.example.screensaver.lock.PhotoLockDeviceAdmin
import com.example.screensaver.lock.PhotoLockScreenService
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.preview.PreviewActivity
import com.example.screensaver.preview.PreviewViewModel
import com.example.screensaver.preview.PreviewState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import android.content.pm.PackageManager
import com.example.screensaver.kiosk.KioskActivity
import androidx.fragment.app.viewModels

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    private var devicePolicyManager: DevicePolicyManager? = null
    private lateinit var adminComponentName: ComponentName
    private var googleSignInClient: GoogleSignInClient? = null
    private val previewViewModel: PreviewViewModel by viewModels()

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
            withContext(Dispatchers.IO) {
                setPreferencesFromResource(R.xml.preferences, rootKey)
            }
            withContext(Dispatchers.Main) {
                initializeDeviceAdmin()
                setupPhotoSourcePreferences()
                setupGoogleSignIn()
                setupTestScreensaver()
                setupDisplayModeSelection()
                setupLockScreenPreview()
                setupLockScreenStatus()
                setupKioskModePreference()
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
                "lock_screen" -> showLockScreenPreview()
            }
            true
        }
    }

    private fun setupPhotoSourcePreferences() {
        val photoSourceSelection = findPreference<MultiSelectListPreference>("photo_source_selection")
        val googlePhotosCategory = findPreference<PreferenceCategory>("google_photos_settings")
        val useGooglePhotos = findPreference<SwitchPreferenceCompat>("google_photos_enabled")
        val selectAlbums = findPreference<Preference>("select_albums")

        Log.d(TAG, "Setting up photo source preferences")

        // Set initial visibility based on selected sources
        val currentSources = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")
        Log.d(TAG, "Current photo sources: $currentSources")

        // Get current Google sign-in state
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        val hasRequiredScope = account?.grantedScopes?.contains(
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
        ) == true

        // Update UI states
        googlePhotosCategory?.isVisible = currentSources.contains("google_photos")
        useGooglePhotos?.isChecked = account != null && hasRequiredScope
        selectAlbums?.apply {
            isEnabled = account != null && hasRequiredScope
            isVisible = true
        }

        // Handle changes to photo source selection
        photoSourceSelection?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selectedSources = newValue as Set<String>
            Log.d(TAG, "Photo sources changed to: $selectedSources")
            googlePhotosCategory?.isVisible = selectedSources.contains("google_photos")
            if (!selectedSources.contains("google_photos")) {
                signOutCompletely()
            }
            true
        }

        // Handle Google Sign-in
        useGooglePhotos?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            Log.d(TAG, "Google Photos sign-in state changing to: $enabled")
            if (enabled) {
                showGoogleSignInPrompt()
                false // Don't update the switch until sign-in is successful
            } else {
                signOutCompletely()
                true
            }
        }

        // Handle album selection
        selectAlbums?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val currentAccount = GoogleSignIn.getLastSignedInAccount(requireContext())
                    withContext(Dispatchers.Main) {
                        if (currentAccount != null) {
                            if (googlePhotosManager.initialize()) {
                                startActivity(Intent(requireContext(), AlbumSelectionActivity::class.java))
                            } else {
                                Toast.makeText(context, "Failed to initialize Google Photos", Toast.LENGTH_SHORT).show()
                            }
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
    }

    private fun setupKioskModePreference() {
        findPreference<SwitchPreferenceCompat>("kiosk_mode_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                handleKioskModeChange(newValue as Boolean)
                true
            }
        }

        findPreference<SeekBarPreference>("kiosk_exit_delay")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putInt("kiosk_exit_delay", newValue as Int)
                    .apply()
                true
            }
        }
    }

    private fun handleKioskModeChange(enabled: Boolean) {
        if (enabled) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_kiosk_mode_enabled_title)
                .setMessage(R.string.kiosk_mode_warning)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    enableKioskMode()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    // Reset the switch
                    findPreference<SwitchPreferenceCompat>("kiosk_mode_enabled")?.isChecked = false
                }
                .show()
        } else {
            disableKioskMode()
        }
    }

    private fun enableKioskMode() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .putBoolean("kiosk_mode_enabled", true)
            .apply()

        // Launch kiosk activity
        KioskActivity.start(requireContext())

        // Show feedback to user
        showFeedback(R.string.kiosk_mode_enabled)
    }

    private fun disableKioskMode() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .putBoolean("kiosk_mode_enabled", false)
            .apply()

        // Show feedback to user
        showFeedback(R.string.kiosk_mode_disabled)
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

            findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
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
                    if (signInIntent != null) {
                        signInLauncher.launch(signInIntent)
                    } else {
                        Log.e(TAG, "Sign-in intent is null")
                        Toast.makeText(context, "Error preparing sign in", Toast.LENGTH_SHORT).show()
                        updateGooglePhotosState(false)
                    }
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
            Log.d(TAG, "Sign in result received: ${completedTask.result.id}")

            // First update UI state
            updateGooglePhotosState(true)

            // Then handle auth code exchange
            account?.serverAuthCode?.let { authCode ->
                account.email?.let { email ->
                    exchangeAuthCode(authCode, email)
                    // Notify service of authentication update
                    Intent(requireContext(), PhotoLockScreenService::class.java).also { intent ->
                        intent.action = "AUTH_UPDATED"
                        requireContext().startService(intent)
                    }
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


    private fun showKioskModeConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.enable_kiosk_mode)
            .setMessage(R.string.kiosk_mode_confirmation)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                enableKioskMode()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Reset the switch
                findPreference<SwitchPreferenceCompat>("kiosk_mode_enabled")?.isChecked = false
            }
            .show()
    }

    private fun exchangeAuthCode(authCode: String, email: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = withContext(Dispatchers.IO) {
                    (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    }
                }

                val clientId = getString(R.string.google_oauth_client_id)
                val clientSecret = getString(R.string.google_oauth_client_secret)
                val postData = buildString {
                    append("code=").append(URLEncoder.encode(authCode, "UTF-8"))
                    append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                    append("&client_secret=").append(URLEncoder.encode(clientSecret, "UTF-8"))
                    append("&redirect_uri=").append(URLEncoder.encode("http://localhost", "UTF-8"))
                    append("&grant_type=authorization_code")
                }

                withContext(Dispatchers.IO) {
                    connection.outputStream.use {
                        it.write(postData.toByteArray())
                    }
                }

                val response = withContext(Dispatchers.IO) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }

                val jsonResponse = JSONObject(response)
                val accessToken = jsonResponse.getString("access_token")
                val refreshToken = jsonResponse.getString("refresh_token")
                val expiresIn = jsonResponse.getLong("expires_in")

                withContext(Dispatchers.Main) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString("access_token", accessToken)
                        .putString("refresh_token", refreshToken)
                        .putLong("token_expiration", System.currentTimeMillis() + (expiresIn * 1000))
                        .apply()

                    updateGooglePhotosState(true)  // Changed from updateSignInState
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging auth code", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to sign in: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateGooglePhotosState(false)  // Changed from updateSignInState
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
        Log.d(TAG, "Updating Google Photos state to: $enabled")
        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = enabled
        findPreference<Preference>("select_albums")?.apply {
            isEnabled = enabled
            isVisible = true
        }

        // Also update visibility of the Google Photos category based on photo source selection
        val currentSources = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")
        findPreference<PreferenceCategory>("google_photos_settings")?.isVisible =
            currentSources.contains("google_photos")
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

            // Observe preview state
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    previewViewModel.previewState.collect { state ->
                        when (state) {
                            is PreviewState.Cooldown -> {
                                isEnabled = false
                                summary = getString(R.string.preview_cooldown_message,
                                    state.remainingSeconds)
                            }
                            is PreviewState.Error -> {
                                isEnabled = false
                                summary = state.message
                            }
                            else -> {
                                isEnabled = true
                                summary = getString(R.string.preview_available_message,
                                    previewViewModel.getRemainingPreviews())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showLockScreenPreview() {
        try {
            if (previewViewModel.getRemainingPreviews() > 0) {
                startActivity(Intent(requireContext(), PreviewActivity::class.java))
            } else {
                val timeUntilNext = previewViewModel.getTimeUntilNextPreviewAllowed()
                showFeedback(getString(R.string.preview_cooldown_message, timeUntilNext / 1000))
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

    private fun showKioskModeConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.kiosk_mode_confirmation)
            .setMessage(getString(R.string.kiosk_mode_confirmation_message))
            .setPositiveButton(getString(R.string.confirm)) { dialog, _ ->
                enableKioskMode()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

}