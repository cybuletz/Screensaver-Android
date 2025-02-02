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
import androidx.navigation.fragment.findNavController
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import androidx.coordinatorlayout.widget.CoordinatorLayout
import android.view.Gravity
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.AppDataState
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.data.PhotoCache
import android.app.Activity
import com.example.screensaver.localphotos.LocalPhotoSelectionActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceCategory
import androidx.preference.MultiSelectListPreference
import com.example.screensaver.lock.LockScreenPhotoManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.screensaver.widgets.WidgetPreferenceFragment
import com.example.screensaver.widgets.WidgetState
import com.example.screensaver.widgets.WidgetType
import com.example.screensaver.widgets.WidgetManager
import com.example.screensaver.widgets.WidgetPosition

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var appDataManager: AppDataManager

    @Inject
    lateinit var secureStorage: SecureStorage

    @Inject
    lateinit var photoCache: PhotoCache

    @Inject
    lateinit var photoManager: LockScreenPhotoManager

    @Inject
    lateinit var widgetManager: WidgetManager

    private var devicePolicyManager: DevicePolicyManager? = null
    private lateinit var adminComponentName: ComponentName
    private var googleSignInClient: GoogleSignInClient? = null
    private val previewViewModel: PreviewViewModel by viewModels()

    private val collapsedCategories = mutableSetOf<String>()

    private var widgetPreferenceFragment: WidgetPreferenceFragment? = null

    companion object {
        private const val TAG = "SettingsFragment"
        private const val DEFAULT_DISPLAY_MODE = "dream_service"
        private const val LOCK_SCREEN_MODE = "lock_screen"
        private const val REQUEST_SELECT_PHOTOS = 1001
        const val EXTRA_PHOTO_SOURCE = "photo_source"
        const val SOURCE_GOOGLE_PHOTOS = "google_photos"
        const val SOURCE_LOCAL_PHOTOS = "local_photos"
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(android.Manifest.permission.READ_EXTERNAL_STORAGE, false) -> {
                launchAlbumSelection()  // Changed from showLocalAlbumDialog() to launchAlbumSelection()
            }
            permissions.getOrDefault(android.Manifest.permission.READ_MEDIA_IMAGES, false) -> {
                launchAlbumSelection()  // Changed from showLocalAlbumDialog() to launchAlbumSelection()
            }
            else -> {
                showError(
                    "Permission required",
                    Exception("Storage permission is required to access local photos")
                )
            }
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

    private fun setupLocalPhotoPreferences() {
        Log.d(TAG, "Setting up local photo preferences")

        val localPhotosCategory = findPreference<PreferenceCategory>("local_photos_settings")
        val selectLocalPhotos = findPreference<Preference>("select_local_photos")
        val photoFolders = findPreference<Preference>("photo_folders") // Changed from MultiSelectListPreference to Preference

        // Get current photo sources
        val currentSources = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")

        // Update visibility based on current sources
        localPhotosCategory?.isVisible = currentSources.contains("local")

        selectLocalPhotos?.apply {
            isEnabled = true // Always enable the selection button if visible
            setOnPreferenceClickListener {
                Log.d(TAG, "Local photos selection clicked")
                launchLocalPhotoSelection()
                true
            }
        }

        photoFolders?.apply {
            isEnabled = true
            setOnPreferenceClickListener {
                Log.d(TAG, "Photo folders clicked")
                checkAndRequestPermissions() // This will handle the permission check and launch the activity
                true
            }

            // Update summary with current selection
            val selectedAlbumIds = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getStringSet("selected_album_ids", emptySet()) ?: emptySet()
            summary = if (selectedAlbumIds.isEmpty()) {
                getString(R.string.pref_photo_folders_summary)
            } else {
                getString(R.string.photos_selected, selectedAlbumIds.size)
            }
        }
    }

    private fun launchAlbumSelection() {
        startActivity(Intent(requireContext(), AlbumSelectionActivity::class.java).apply {
            putExtra(EXTRA_PHOTO_SOURCE, SOURCE_LOCAL_PHOTOS)
        })
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchAlbumSelection()
            } else {
                storagePermissionLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES))
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchAlbumSelection()
            } else {
                storagePermissionLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    private fun launchLocalPhotoSelection() {
        Log.d(TAG, "Launching local photo selection")
        try {
            val intent = Intent(requireContext(), LocalPhotoSelectionActivity::class.java)
            // Pass currently selected photos to maintain state
            val selectedPhotos = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getStringSet("selected_local_photos", emptySet())
            intent.putExtra("selected_photos", ArrayList(selectedPhotos ?: emptySet()))
            startActivityForResult(intent, REQUEST_SELECT_PHOTOS)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching LocalPhotoSelectionActivity", e)
            showError("Failed to launch photo selection", e)
        }
    }

    private fun showError(message: String, error: Exception) {
        Log.e(TAG, "$message: ${error.message}", error)
        view?.let { v ->
            Snackbar.make(v, "$message: ${error.localizedMessage}", Snackbar.LENGTH_LONG)
                .setAction(R.string.details) {
                    showErrorDialog(error)
                }.show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SELECT_PHOTOS -> {
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "Local photos selection successful")
                    val selectedPhotos = data?.getStringArrayListExtra("selected_photos")
                    Log.d(TAG, "Selected photos: ${selectedPhotos?.size}")

                    if (selectedPhotos != null) {
                        // Save selected photos to preferences
                        PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putStringSet("selected_local_photos", selectedPhotos.toSet())
                            .apply()

                        updateLocalPhotosUI()

                        // Update app state
                        appDataManager.updateState { currentState ->
                            currentState.copy(
                                photoSources = currentState.photoSources + "local",
                                lastSyncTimestamp = System.currentTimeMillis()
                            )
                        }

                        // Force update of photo display
                        photoDisplayManager.updatePhotoSources()
                    }
                } else {
                    Log.d(TAG, "Local photos selection cancelled or failed")
                }
            }
        }
    }

    private fun updateLocalPhotosUI() {
        findPreference<Preference>("photo_folders")?.apply {
            val selectedPhotos = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getStringSet("selected_local_photos", emptySet()) ?: emptySet()
            summary = if (selectedPhotos.isEmpty()) {
                getString(R.string.pref_photo_folders_summary)
            } else {
                getString(R.string.photos_selected, selectedPhotos.size)
            }

            // Trigger photo display update
            photoDisplayManager.updatePhotoSources()
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

    private fun saveSettings() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .apply()

        // Force restart photo display
        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings() // Make sure this updates all settings
            startPhotoDisplay()
        }

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

        // First restore state
        restoreSettingsState()

        // Then initialize all components
        initializeDeviceAdmin()
        setupPhotoDisplayManager()
        setupPhotoSourcePreferences()
        setupGoogleSignIn()
        setupTestScreensaver()
        setupDisplayModeSelection()
        setupLockScreenPreview()
        setupLockScreenStatus()
        setupKioskModePreference()

        // Observe state changes
        observeAppState()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        Log.d(TAG, "Setting up preferences")

        lifecycleScope.launch(Dispatchers.IO) {
            // Load preferences in background
            withContext(Dispatchers.IO) {
                setPreferencesFromResource(R.xml.preferences, rootKey)
            }

            // Setup UI on main thread
            withContext(Dispatchers.Main) {
                try {
                    // First restore state
                    restoreSettingsState()

                    // Then initialize all components
                    initializeDeviceAdmin()
                    setupPhotoDisplayManager()
                    setupPhotoSourcePreferences()
                    setupGoogleSignIn()
                    setupTestScreensaver()
                    setupDisplayModeSelection()
                    setupLockScreenPreview()
                    setupLockScreenStatus()
                    setupKioskModePreference()
                    setupCacheSettings(preferenceScreen)
                    setupLocalPhotoPreferences()
                    setupChargingPreference()
                    setupWidgetPreferences()

                    // Set up position preference listener
                    findPreference<ListPreference>("clock_position")?.apply {
                        setOnPreferenceChangeListener { preference, newValue ->
                            handlePreferenceChange(preference, newValue)
                        }
                    }

                    // Observe state changes
                    observeAppState()
                    observeAppDataState()
                    observeWidgetState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up preferences", e)
                }
            }
        }
    }

    private fun handlePreferenceChange(preference: Preference, newValue: Any?): Boolean {
        return when (preference.key) {
            "clock_position" -> {
                val positionValue = newValue as? String ?: return false
                try {
                    val position = WidgetPosition.valueOf(positionValue)
                    widgetManager.updateClockPosition(position)
                    true
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid position value: $positionValue", e)
                    false
                }
            }
            else -> true
        }
    }

    private fun setupWidgetPreferences() {
        findPreference<PreferenceCategory>("widget_settings")?.let { category ->
            // Add widget preferences as a nested screen
            findPreference<Preference>("widget_configuration")?.setOnPreferenceClickListener {
                if (widgetPreferenceFragment == null) {
                    widgetPreferenceFragment = WidgetPreferenceFragment()
                }

                parentFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, widgetPreferenceFragment!!)
                    .addToBackStack("widget_settings")
                    .commit()

                true
            }
        }
    }

    private fun observeWidgetState() {
        lifecycleScope.launch {
            widgetManager.widgetStates.collect { states ->
                states[WidgetType.CLOCK]?.let { clockState ->
                    updateClockPreferencesVisibility(clockState.state is WidgetState.Active)
                }
            }
        }
    }

    private fun updateClockPreferencesVisibility(visible: Boolean) {
        findPreference<PreferenceCategory>("clock_settings")?.isVisible = visible
    }

    private fun observeAppDataState() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDataManager.observeState().collect { state ->
                updatePreferencesFromState(state)
            }
        }
    }

    private fun updatePreferencesFromState(state: AppDataState) {
        // Update display mode
        findPreference<ListPreference>("display_mode_selection")?.value = state.displayMode

        // Update photo sources
        findPreference<MultiSelectListPreference>("photo_source_selection")?.values = state.photoSources

        // Update Google Photos state
        val googlePhotosCategory = findPreference<PreferenceCategory>("google_photos_settings")
        val useGooglePhotos = findPreference<SwitchPreferenceCompat>("google_photos_enabled")
        val selectAlbums = findPreference<Preference>("select_albums")

        val isGooglePhotosSource = state.photoSources.contains("google_photos")
        googlePhotosCategory?.isVisible = isGooglePhotosSource
        useGooglePhotos?.isVisible = isGooglePhotosSource
        selectAlbums?.isVisible = isGooglePhotosSource

        if (isGooglePhotosSource) {
            val account = GoogleSignIn.getLastSignedInAccount(requireContext())
            val hasRequiredScope = account?.grantedScopes?.contains(
                Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
            ) == true

            useGooglePhotos?.isChecked = state.googlePhotosEnabled && hasRequiredScope
            selectAlbums?.isEnabled = state.googlePhotosEnabled && hasRequiredScope
        }

        Log.d(TAG, "Preferences updated from state - Google Photos enabled: ${state.googlePhotosEnabled}, Sources: ${state.photoSources}")
    }

    private fun setupPhotoDisplayManager() {
        // Connect transition effect and duration settings
        findPreference<ListPreference>("transition_effect")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val transitionTime = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getInt("transition_duration", 2)

                photoDisplayManager.updateSettings(
                    transitionDuration = transitionTime * 1000L
                )
                true
            }
        }

        // Connect transition time settings
        findPreference<SeekBarPreference>("transition_duration")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val transitionSeconds = (newValue as Int)
                photoDisplayManager.updateSettings(
                    transitionDuration = transitionSeconds * 1000L
                )
                summary = "$transitionSeconds seconds for transition animation"
                true
            }

            val currentValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt("transition_duration", 2)
            summary = "$currentValue seconds for transition animation"
        }

        // Connect photo change interval settings
        findPreference<SeekBarPreference>("photo_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val intervalSeconds = (newValue as Int)
                // Force restart the photo display with new interval
                photoDisplayManager.apply {
                    stopPhotoDisplay() // Stop current display
                    updateSettings(photoInterval = intervalSeconds * 1000L)
                    startPhotoDisplay() // Restart with new interval
                }
                Log.d(TAG, "Setting new photo interval to $intervalSeconds seconds")
                summary = "Display each photo for $intervalSeconds seconds"
                true
            }

            // Set initial summary and value
            val currentValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt("photo_interval", 15)
            summary = "Display each photo for $currentValue seconds"
        }

        // Initialize PhotoDisplayManager with current settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val transitionTime = prefs.getInt("transition_duration", 2)
        val photoInterval = prefs.getInt("photo_interval", 15)

        // Force stop and restart with correct settings
        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings(
                transitionDuration = transitionTime * 1000L,
                photoInterval = photoInterval * 1000L,
                isRandomOrder = prefs.getBoolean("random_order", true)
            )
            startPhotoDisplay()
        }

        Log.d(TAG, "PhotoDisplayManager initialized with interval: ${photoInterval} seconds")
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
        val localPhotosCategory = findPreference<PreferenceCategory>("local_photos_settings")
        val useGooglePhotos = findPreference<SwitchPreferenceCompat>("google_photos_enabled")
        val selectAlbums = findPreference<Preference>("select_albums")

        Log.d(TAG, "Setting up photo source preferences")

        // Get current state without default value
        val currentSources = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", null) ?: emptySet()
        Log.d(TAG, "Current photo sources: $currentSources")

        // Get current Google sign-in state
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        val hasRequiredScope = account?.grantedScopes?.contains(
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
        ) == true

        // Update UI states
        googlePhotosCategory?.isVisible = currentSources.contains("google_photos")
        localPhotosCategory?.isVisible = currentSources.contains("local")
        useGooglePhotos?.isChecked = account != null && hasRequiredScope
        selectAlbums?.apply {
            isEnabled = account != null && hasRequiredScope
            isVisible = currentSources.contains("google_photos")
        }

        // Handle changes to photo source selection
        photoSourceSelection?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selectedSources = newValue as Set<String>
            val googlePhotosEnabled = selectedSources.contains("google_photos")
            val localPhotosEnabled = selectedSources.contains("local")

            // Update UI immediately
            googlePhotosCategory?.isVisible = googlePhotosEnabled
            localPhotosCategory?.isVisible = localPhotosEnabled
            useGooglePhotos?.isVisible = googlePhotosEnabled
            selectAlbums?.isVisible = googlePhotosEnabled

            // If Google Photos was just added, reset the switch state
            if (googlePhotosEnabled && !currentSources.contains("google_photos")) {
                useGooglePhotos?.isChecked = false
                selectAlbums?.isEnabled = false
            }

            // Update app state
            appDataManager.updateState { it.copy(
                photoSources = selectedSources,
                googlePhotosEnabled = googlePhotosEnabled && useGooglePhotos?.isChecked == true
            )}

            Log.d(TAG, "Photo sources updated: $selectedSources")
            true
        }

        // Handle Google Sign-in
        useGooglePhotos?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                showGoogleSignInPrompt()
                false // Don't update the switch state yet, wait for sign-in result
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
                            useGooglePhotos?.isChecked = false
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

    private fun setupChargingPreference() {
        Log.d(TAG, "Setting up charging preference")
        findPreference<SwitchPreferenceCompat>("start_on_charge")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Log.d(TAG, "Charging preference changed to: $enabled")

                // Update the preference
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putBoolean("start_on_charge", enabled)
                    .apply()

                // Show feedback
                val message = if (enabled) {
                    "Auto-start on charging enabled"
                } else {
                    "Auto-start on charging disabled"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

                true
            }

            // Log current state
            val currentState = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("start_on_charge", false)
            Log.d(TAG, "Current charging preference state: $currentState")
        }
    }

    private fun showChargingFeatureDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.start_on_charge_dialog_title)
            .setMessage(R.string.start_on_charge_dialog_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                updateChargingPreference(true)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Reset the switch
                findPreference<SwitchPreferenceCompat>("start_on_charge")?.isChecked = false
            }
            .show()
    }

    private fun updateChargingPreference(enabled: Boolean) {
        try {
            // Update preference
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putBoolean("start_on_charge", enabled)
                .apply()

            // Update switch state
            findPreference<SwitchPreferenceCompat>("start_on_charge")?.isChecked = enabled

            // Show feedback
            val messageResId = if (enabled) {
                R.string.start_on_charge_enabled
            } else {
                R.string.start_on_charge_disabled
            }
            showFeedback(messageResId)

            Log.d(TAG, "Charging preference updated: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating charging preference", e)
            handleError("Failed to update charging preference", e)
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
                val expirationTime = System.currentTimeMillis() + (expiresIn * 1000)

                withContext(Dispatchers.Main) {
                    try {
                        secureStorage.saveGoogleCredentials(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expirationTime = expirationTime,
                            email = email
                        )
                        updateGooglePhotosState(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving credentials to SecureStorage", e)
                        updateGooglePhotosState(false)
                        showFeedback(R.string.sign_in_failed)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging auth code", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Failed to sign in: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateGooglePhotosState(false)
                }
            }
        }
    }

    private fun setupCacheSettings(screen: PreferenceScreen) {
        val cachePref = ListPreference(requireContext()).apply {
            key = "cache_size"
            title = getString(R.string.pref_cache_size_title)
            summary = getString(R.string.pref_cache_size_summary)
            entries = arrayOf("5", "10", "20", "30", "50")
            entryValues = arrayOf("5", "10", "20", "30", "50")
            setDefaultValue("10")

            setOnPreferenceChangeListener { _, newValue ->
                val size = (newValue as String).toInt()
                photoCache.setMaxCachedPhotos(size)
                true
            }
        }
        screen.addPreference(cachePref)
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

        // Get current selections
        val currentSources = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")
        val googlePhotosEnabled = currentSources.contains("google_photos")

        // Update Google Photos switch
        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
            isChecked = enabled
            isVisible = googlePhotosEnabled
        }

        // Update albums selection
        findPreference<Preference>("select_albums")?.apply {
            isEnabled = enabled && googlePhotosEnabled
            isVisible = googlePhotosEnabled
        }

        // Update category visibility
        findPreference<PreferenceCategory>("google_photos_settings")?.isVisible = googlePhotosEnabled

        // Update app state
        appDataManager.updateState { it.copy(
            googlePhotosEnabled = enabled && googlePhotosEnabled,
            lastSyncTimestamp = if (enabled) System.currentTimeMillis() else it.lastSyncTimestamp
        )}

        // If disabled, clear selected albums
        if (!enabled) {
            appDataManager.updateState { it.copy(selectedAlbums = emptySet()) }
        }

        Log.d(TAG, "Google Photos state updated - enabled: $enabled, source available: $googlePhotosEnabled")
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

    private fun restoreSettingsState() {
        val currentState = appDataManager.getCurrentState()

        Log.d(TAG, "Restoring settings state - Sources: ${currentState.photoSources}")

        // Restore photo source selection
        findPreference<MultiSelectListPreference>("photo_source_selection")?.values =
            currentState.photoSources

        // Restore Local Photos state
        findPreference<PreferenceCategory>("local_photos_settings")?.apply {
            isVisible = currentState.photoSources.contains("local")
            updateLocalPhotosUI() // Update UI to show selected photos count
        }

        // Restore Google Photos state
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        val hasRequiredScope = account?.grantedScopes?.contains(
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
        ) == true

        // Update Google Photos UI based on current state
        findPreference<PreferenceCategory>("google_photos_settings")?.isVisible =
            currentState.photoSources.contains("google_photos")

        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
            isChecked = currentState.googlePhotosEnabled && hasRequiredScope
            isVisible = currentState.photoSources.contains("google_photos")
        }

        findPreference<Preference>("select_albums")?.apply {
            isEnabled = currentState.googlePhotosEnabled && hasRequiredScope
            isVisible = currentState.photoSources.contains("google_photos")
        }

        // Restore display mode
        findPreference<ListPreference>("display_mode_selection")?.value = currentState.displayMode

        Log.d(TAG, "Settings state restored - Google Photos enabled: ${currentState.googlePhotosEnabled}, Local Photos visible: ${currentState.photoSources.contains("local")}")
    }

    private fun observeAppState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appDataManager.observeState().collect { state ->
                    updatePreferencesFromState(state)
                }
            }
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

    override fun onDestroyView() {
        widgetPreferenceFragment = null
        super.onDestroyView()
    }

}