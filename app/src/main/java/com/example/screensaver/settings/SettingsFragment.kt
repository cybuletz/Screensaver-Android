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
import com.example.screensaver.ui.PhotoDisplayManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import androidx.navigation.fragment.findNavController
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import androidx.coordinatorlayout.widget.CoordinatorLayout
import android.view.Gravity
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.AppDataState


@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var appDataManager: AppDataManager

    private var devicePolicyManager: DevicePolicyManager? = null
    private lateinit var adminComponentName: ComponentName
    private var googleSignInClient: GoogleSignInClient? = null
    private val previewViewModel: PreviewViewModel by viewModels()

    companion object {
        private const val TAG = "SettingsFragment"
        private const val DEFAULT_DISPLAY_MODE = "dream_service"
        private const val LOCK_SCREEN_MODE = "lock_screen"
    }

    class PreferenceFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
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

    private fun setupPreferences(container: FrameLayout) {
        childFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, PreferenceFragment())
            .commit()
    }

    private fun saveSettings() {
        // Save preferences
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .apply()

        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = super.onCreateView(inflater, container, savedInstanceState)

        // Create a CoordinatorLayout to wrap the preferences and FAB
        val coordinator = CoordinatorLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Add the preferences view
        coordinator.addView(view)

        // Add the FAB
        val fab = ExtendedFloatingActionButton(requireContext()).apply {
            id = View.generateViewId()
            text = getString(R.string.save_settings)
            setIconResource(R.drawable.ic_save)
            layoutParams = CoordinatorLayout.LayoutParams(
                CoordinatorLayout.LayoutParams.WRAP_CONTENT,
                CoordinatorLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
            }
            setOnClickListener {
                saveSettings()
                findNavController().navigateUp()
            }
        }
        coordinator.addView(fab)

        return coordinator
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeDeviceAdmin()
        setupPhotoDisplayManager()
        setupPhotoSourcePreferences()
        setupGoogleSignIn()
        setupTestScreensaver()
        setupDisplayModeSelection()
        setupLockScreenPreview()
        setupLockScreenStatus()
        setupKioskModePreference()
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
                observeAppDataState() // New function
            }
        }
    }

    private fun observeAppDataState() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDataManager.observeState().collect { state ->
                updatePreferencesFromState(state)
            }
        }
    }

    private fun updatePreferencesFromState(state: AppDataState) {
        findPreference<ListPreference>("display_mode_selection")?.value = state.displayMode
        findPreference<SwitchPreferenceCompat>("show_clock")?.isChecked = state.showClock
        findPreference<SwitchPreferenceCompat>("show_date")?.isChecked = state.showDate

        // Update transition settings
        findPreference<ListPreference>("transition_effect")?.value = state.transitionAnimation
        findPreference<SeekBarPreference>("transition_interval")?.value = state.transitionInterval

        // Update photo source settings
        val photoSourceSelection = findPreference<MultiSelectListPreference>("photo_source_selection")
        photoSourceSelection?.values = state.photoSources

        // Update Google Photos state
        val useGooglePhotos = findPreference<SwitchPreferenceCompat>("google_photos_enabled")
        useGooglePhotos?.isChecked = state.googlePhotosEnabled

        // Update screensaver settings
        findPreference<SwitchPreferenceCompat>("random_order")?.isChecked = state.randomOrder
        findPreference<ListPreference>("photo_scale")?.value = state.photoScale
    }

    private fun setupPhotoDisplayManager() {
        // Connect transition settings
        findPreference<ListPreference>("transition_effect")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    transitionDuration = when(newValue as String) {
                        "fade" -> 1000L
                        "slide" -> 500L
                        "zoom" -> 1500L
                        else -> 1000L
                    }
                )
                true
            }
        }

        // Connect refresh interval settings
        findPreference<ListPreference>("refresh_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    photoInterval = (newValue.toString().toLong() * 1000)
                )
                true
            }
        }

        // Connect clock display setting
        findPreference<SwitchPreferenceCompat>("lock_screen_clock")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    showClock = newValue as Boolean
                )
                true
            }
        }

        // Connect date display setting
        findPreference<SwitchPreferenceCompat>("lock_screen_date")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    showDate = newValue as Boolean
                )
                true
            }
        }

        // Connect random order setting
        findPreference<SwitchPreferenceCompat>("random_order")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    isRandomOrder = newValue as Boolean
                )
                true
            }
        }

        // Initialize PhotoDisplayManager with current settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        photoDisplayManager.updateSettings(
            transitionDuration = when(prefs.getString("transition_effect", "fade")) {
                "fade" -> 1000L
                "slide" -> 500L
                "zoom" -> 1500L
                else -> 1000L
            },
            photoInterval = (prefs.getString("refresh_interval", "300")?.toLong() ?: 300L) * 1000,
            showClock = prefs.getBoolean("lock_screen_clock", true),
            showDate = prefs.getBoolean("lock_screen_date", true),
            isRandomOrder = prefs.getBoolean("random_order", true)
        )
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
                val mode = newValue as String
                appDataManager.updateState { it.copy(displayMode = mode) }
                when (mode) {
                    "dream_service" -> {
                        lifecycleScope.launch {
                            disableLockScreenMode()
                            enableDreamService()
                        }
                        true
                    }
                    "lock_screen" -> {
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
            appDataManager.updateState { it.copy(
                photoSources = selectedSources,
                googlePhotosEnabled = selectedSources.contains("google_photos")
            )}
            true
        }

        // Handle Google Sign-in
        useGooglePhotos?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                showGoogleSignInPrompt()
                false
            } else {
                appDataManager.updateState { it.copy(googlePhotosEnabled = false) }
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

            // Update state first
            appDataManager.updateState { it.copy(
                googlePhotosEnabled = true,
                lastSyncTimestamp = System.currentTimeMillis()
            )}

            // Then handle auth code exchange
            account?.serverAuthCode?.let { authCode ->
                account.email?.let { email ->
                    exchangeAuthCode(authCode, email)
                }
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed", e)
            appDataManager.updateState { it.copy(googlePhotosEnabled = false) }
            showFeedback(R.string.sign_in_failed)
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

            // Move the viewLifecycleOwner observation to a separate method
            observePreviewState(this)
        }
    }

    private fun observePreviewState(preference: Preference) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                previewViewModel.previewState.collect { state ->
                    when (state) {
                        is PreviewState.Cooldown -> {
                            preference.isEnabled = false
                            preference.summary = getString(
                                R.string.preview_cooldown_message,
                                state.remainingSeconds
                            )
                        }
                        is PreviewState.Error -> {
                            preference.isEnabled = false
                            preference.summary = state.message
                        }
                        is PreviewState.Initial -> {
                            preference.isEnabled = true
                            preference.summary = getString(
                                R.string.preview_available_message,
                                previewViewModel.getRemainingPreviews()
                            )
                        }
                        is PreviewState.Available -> {
                            preference.isEnabled = true
                            preference.summary = getString(
                                R.string.preview_available_message,
                                state.remainingPreviews
                            )
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
                view?.let { v ->
                    Snackbar.make(v,
                        getString(R.string.preview_cooldown_message, timeUntilNext / 1000),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
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