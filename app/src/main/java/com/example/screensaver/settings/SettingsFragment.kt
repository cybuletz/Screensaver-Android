package com.example.screensaver.settings

import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import android.content.pm.PackageManager
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
import com.example.screensaver.PhotoRepository
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.screensaver.widgets.WidgetPreferenceFragment
import com.example.screensaver.widgets.WidgetState
import com.example.screensaver.widgets.WidgetType
import com.example.screensaver.widgets.WidgetManager
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.photos.PhotoManagerActivity
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.security.AppAuthManager
import com.example.screensaver.security.BiometricHelper
import com.example.screensaver.security.SecurityPreferenceDialog
import com.example.screensaver.security.SecurityPreferences
import com.example.screensaver.widgets.WidgetPreferenceDialog
import com.example.screensaver.models.MediaItem
import com.example.screensaver.PhotoRepository.PhotoAddMode

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
    lateinit var photoManager: PhotoRepository

    @Inject
    lateinit var widgetManager: WidgetManager

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var authManager: AppAuthManager

    @Inject
    lateinit var securityPreferences: SecurityPreferences

    @Inject
    lateinit var biometricHelper: BiometricHelper

    private var googleSignInClient: GoogleSignInClient? = null

    private var widgetPreferenceFragment: WidgetPreferenceFragment? = null

    companion object {
        private const val TAG = "SettingsFragment"
        private const val REQUEST_SELECT_PHOTOS = 1001
        const val EXTRA_PHOTO_SOURCE = "photo_source"
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

                        // Update UI
                        updateLocalPhotosUI()

                        // Add to PhotoManager
                        val mediaItems = selectedPhotos.map { uri ->
                            MediaItem(
                                id = uri,
                                albumId = "local_picked",
                                baseUrl = uri,
                                mimeType = "image/*",
                                width = 0,
                                height = 0,
                                description = null,
                                createdAt = System.currentTimeMillis(),
                                loadState = MediaItem.LoadState.IDLE
                            )
                        }

                        photoManager.addPhotos(mediaItems, PhotoAddMode.APPEND)

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
    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

        // Force restart widgets
        widgetManager.reinitializeWeatherWidget()

        // Force restart photo display
        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings()
            startPhotoDisplay()
        }

        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
        setupPhotoDisplayManager()
        setupPhotoSourcePreferences()
        setupGoogleSignIn()
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
                    setupPhotoDisplayManager()
                    setupPhotoSourcePreferences()
                    setupGoogleSignIn()
                    setupCacheSettings(preferenceScreen)
                    setupLocalPhotoPreferences()
                    setupChargingPreference()

                    // Setup widget configuration buttons
                    findPreference<Preference>("clock_widget_settings")?.setOnPreferenceClickListener {
                        showWidgetDialog(WidgetType.CLOCK)
                        true
                    }

                    findPreference<Preference>("weather_widget_settings")?.setOnPreferenceClickListener {
                        showWidgetDialog(WidgetType.WEATHER)
                        true
                    }

                    findPreference<Preference>("security_preferences")?.setOnPreferenceClickListener {
                        SecurityPreferenceDialog.newInstance()
                            .show(childFragmentManager, "security_settings")
                        true
                    }

                    findPreference<Preference>("manage_photos")?.setOnPreferenceClickListener {
                        startActivity(Intent(requireContext(), PhotoManagerActivity::class.java))
                        true
                    }

                    // Update widget summaries based on their states
                    updateWidgetSummaries()

                    observeAppState()
                    observeAppDataState()
                    observeWidgetState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up preferences", e)
                }
            }
        }
    }

    private fun showWidgetDialog(widgetType: WidgetType) {
        WidgetPreferenceDialog.newInstance(widgetType)
            .show(childFragmentManager, "widget_settings")
    }

    private fun updateWidgetSummaries() {
        findPreference<Preference>("clock_widget_settings")?.apply {
            val enabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("show_clock", false)
            summary = if (enabled) {
                getString(R.string.pref_clock_widget_enabled_summary)
            } else {
                getString(R.string.pref_widget_settings_summary)
            }
        }

        findPreference<Preference>("weather_widget_settings")?.apply {
            val enabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("show_weather", false)
            summary = if (enabled) {
                getString(R.string.pref_show_weather_summary)
            } else {
                getString(R.string.pref_widget_settings_summary)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        widgetManager.reinitializeWeatherWidget()
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
                //Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
                Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
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

        // Connect photo change interval settings - simplified to only update UI
        findPreference<SeekBarPreference>("photo_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val intervalSeconds = (newValue as Int)
                // Only update summary - PhotoDisplayManager will read from preferences directly
                summary = "Display each photo for $intervalSeconds seconds"
                // Restart display to pick up new interval
                photoDisplayManager.apply {
                    stopPhotoDisplay()
                    startPhotoDisplay()
                }
                true
            }

            // Set initial summary
            val currentValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt(PhotoDisplayManager.PREF_KEY_INTERVAL, PhotoDisplayManager.DEFAULT_INTERVAL_SECONDS)
            summary = "Display each photo for $currentValue seconds"
        }

        // Initialize PhotoDisplayManager with current settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val transitionTime = prefs.getInt("transition_duration", 2)

        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings(
                transitionDuration = transitionTime * 1000L,
                isRandomOrder = prefs.getBoolean("random_order", true)
            )
            startPhotoDisplay()
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
            //Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
            Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
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

    private fun setupGoogleSignIn() {
        try {
            Log.d(TAG, "Setting up Google Sign In")
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                //.requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestScopes(Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
                .requestIdToken(getString(R.string.google_oauth_client_id))
                .build()

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                val hasRequiredScope = account?.grantedScopes?.contains(
                    //Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
                    Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
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
                //.requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestScopes(Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))
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

    private fun restoreSettingsState() {
        val currentState = appDataManager.getCurrentState()

        Log.d(TAG, "Restoring settings state - Sources: ${currentState.photoSources}")

        // Check Google Photos state
        val hasGoogleCredentials = secureStorage.getGoogleCredentials() != null
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        val hasRequiredScope = account?.grantedScopes?.contains(
            Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
        ) == true

        // Google Photos is active if we have credentials and scope, albums not required initially
        val hasGooglePhotosActive = hasGoogleCredentials && hasRequiredScope

        // Update sources list
        val updatedSources = currentState.photoSources.toMutableSet()
        if (hasGooglePhotosActive && !updatedSources.contains("google_photos")) {
            Log.d(TAG, "Adding Google Photos to sources due to active authentication")
            updatedSources.add("google_photos")
        }

        // Update state if needed
        if (hasGooglePhotosActive && !currentState.googlePhotosEnabled) {
            Log.d(TAG, "Updating app state to reflect active Google Photos")
            appDataManager.updateState { it.copy(
                photoSources = updatedSources,
                googlePhotosEnabled = true
            )}
        }

        // Update UI elements
        findPreference<MultiSelectListPreference>("photo_source_selection")?.apply {
            values = updatedSources
            Log.d(TAG, "Updated photo sources to: $updatedSources")
        }

        // Update Local Photos UI
        findPreference<PreferenceCategory>("local_photos_settings")?.apply {
            isVisible = updatedSources.contains("local")
            updateLocalPhotosUI()
        }

        // Update Google Photos UI
        findPreference<PreferenceCategory>("google_photos_settings")?.apply {
            isVisible = updatedSources.contains("google_photos")
            Log.d(TAG, "Google Photos category visibility: ${isVisible}")
        }

        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
            isChecked = hasGooglePhotosActive
            isVisible = updatedSources.contains("google_photos")
            Log.d(TAG, "Google Photos enabled state: ${isChecked}, visible: ${isVisible}")
        }

        findPreference<Preference>("select_albums")?.apply {
            isEnabled = hasGooglePhotosActive
            isVisible = updatedSources.contains("google_photos")
            // Update summary to show selected album count
            val selectedAlbums = appPreferences.getSelectedAlbumIds()
            if (isVisible && selectedAlbums.isNotEmpty()) {
                val albumCount = selectedAlbums.size
                summary = resources.getQuantityString(
                    R.plurals.selected_albums_count,
                    albumCount,
                    albumCount
                )
            }
        }

        // Restore display mode
        findPreference<ListPreference>("display_mode_selection")?.value = currentState.displayMode

        Log.d(TAG, """Settings state restored:
        - Google Photos enabled: $hasGooglePhotosActive
        - Has credentials: $hasGoogleCredentials
        - Required scope: $hasRequiredScope
        - Sources: $updatedSources""".trimIndent())
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

    override fun onDestroyView() {
        widgetPreferenceFragment = null
        super.onDestroyView()
    }

}