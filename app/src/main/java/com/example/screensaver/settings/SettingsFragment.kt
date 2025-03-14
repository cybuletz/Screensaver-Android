package com.example.screensaver.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.preference.*
import com.example.screensaver.R
import com.example.screensaver.shared.GooglePhotosManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
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
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceCategory
import androidx.preference.MultiSelectListPreference
import com.example.screensaver.PhotoRepository
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
import com.example.screensaver.music.SpotifyManager
import com.example.screensaver.music.SpotifyAuthManager
import com.example.screensaver.music.SpotifyPreferences
import com.example.screensaver.utils.BrightnessManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber


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

    @Inject
    lateinit var spotifyManager: SpotifyManager

    @Inject
    lateinit var spotifyPreferences: SpotifyPreferences

    @Inject
    lateinit var spotifyAuthManager: SpotifyAuthManager

    @Inject
    lateinit var brightnessManager: BrightnessManager

    private var widgetPreferenceFragment: WidgetPreferenceFragment? = null

    private val spotifyAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Timber.d("Spotify auth result received: resultCode=${result.resultCode}, data=${result.data?.data}")

        // Handle the auth response
        spotifyAuthManager.handleAuthResponse(
            SpotifyAuthManager.REQUEST_CODE,
            result.resultCode,
            result.data
        )

        // Bring our activity to front properly
        requireActivity().apply {
            val intent = Intent(this, this::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        }

        // Update UI based on auth state
        when (val state = spotifyAuthManager.authState.value) {
            is SpotifyAuthManager.AuthState.Authenticated -> {
                Timber.d("Spotify authentication successful")
                spotifyPreferences.setEnabled(true)
                spotifyManager.retry()
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = true
            }
            is SpotifyAuthManager.AuthState.Error -> {
                Timber.e("Spotify authentication failed: ${state.error.message}")
                spotifyPreferences.setEnabled(false)
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
                Toast.makeText(
                    requireContext(),
                    "Authentication failed: ${state.error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                Timber.d("Spotify authentication cancelled or unknown state")
                spotifyPreferences.setEnabled(false)
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
            }
        }
        updateSpotifyLoginSummary()
    }


    companion object {
        private const val TAG = "SettingsFragment"
        private const val REQUEST_SELECT_PHOTOS = 1001
        private const val SPOTIFY_AUTH_REQUEST_CODE = 1337
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
                    setupChargingPreference()

                    // Setup all preference click listeners
                    findPreference<Preference>("photo_sources_dialog")?.setOnPreferenceClickListener {
                        PhotoSourcesDialog.newInstance().show(childFragmentManager, "photo_sources")
                        true
                    }

                    findPreference<Preference>("clock_widget_settings")?.setOnPreferenceClickListener {
                        showWidgetDialog(WidgetType.CLOCK)
                        true
                    }

                    findPreference<Preference>("weather_widget_settings")?.setOnPreferenceClickListener {
                        showWidgetDialog(WidgetType.WEATHER)
                        true
                    }

                    findPreference<Preference>("music_widget_settings")?.setOnPreferenceClickListener {
                        showWidgetDialog(WidgetType.MUSIC)
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

                    findPreference<Preference>("display_settings")?.setOnPreferenceClickListener {
                        DisplaySettingsDialog.newInstance()
                            .show(childFragmentManager, "display_settings")
                        true
                    }

                    findPreference<Preference>("common_settings")?.setOnPreferenceClickListener {
                        PhotoShowSettingsDialog.newInstance()
                            .show(childFragmentManager, "common_settings")
                        true
                    }

                    findPreference<ListPreference>("cache_size")?.setOnPreferenceChangeListener { _, newValue ->
                        val size = (newValue as String).toInt()
                        photoCache.setMaxCachedPhotos(size)
                        true
                    }

                    // Update widget summaries based on their states
                    updateWidgetSummaries()

                    observeAppState()
                    observeAppDataState()
                    observeWidgetState()
                    setupSpotifyPreferences()

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

        findPreference<Preference>("music_widget_settings")?.apply {
            val enabled = spotifyPreferences.isEnabled()
            summary = if (enabled) {
                getString(R.string.pref_music_widget_enabled_summary)
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

    private fun restoreSettingsState() {
        val currentState = appDataManager.getCurrentState()

        // Restore only general settings
        findPreference<ListPreference>("display_mode_selection")?.value = currentState.displayMode

        // Restore widget states
        updateWidgetSummaries()

        // Restore cache settings
        findPreference<ListPreference>("cache_size")?.value =
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("cache_size", "10")

        Log.d(TAG, "General settings state restored")
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

    override fun onDestroyView() {
        widgetPreferenceFragment = null
        super.onDestroyView()
    }

    private fun setupSpotifyPreferences() {
        findPreference<SwitchPreferenceCompat>("spotify_enabled")?.apply {
            isChecked = spotifyPreferences.isEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    if (!spotifyManager.isSpotifyInstalled()) {
                        showSpotifyInstallDialog()
                        false
                    } else {
                        try {
                            val authIntent = spotifyAuthManager.getAuthIntent(requireActivity())
                            spotifyAuthLauncher.launch(authIntent)
                            false
                        } catch (e: Exception) {
                            Toast.makeText(
                                requireContext(),
                                "Error starting Spotify authentication",
                                Toast.LENGTH_LONG
                            ).show()
                            false
                        }
                    }
                } else {
                    spotifyPreferences.setEnabled(false)
                    spotifyPreferences.setConnectionState(false)
                    spotifyManager.disconnect()
                    true
                }
            }
        }

        findPreference<Preference>("spotify_login")?.apply {
            setOnPreferenceClickListener {
                if (spotifyAuthManager.authState.value is SpotifyAuthManager.AuthState.Authenticated) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Spotify Account")
                        .setMessage("Do you want to disconnect your Spotify account?")
                        .setPositiveButton("Disconnect") { _, _ ->
                            spotifyAuthManager.logout()
                            updateSpotifyLoginSummary()
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                        .show()
                } else {
                    try {
                        val authIntent = spotifyAuthManager.getAuthIntent(requireActivity())
                        spotifyAuthLauncher.launch(authIntent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Error starting Spotify authentication",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("spotify_autoplay")?.apply {
            isChecked = spotifyPreferences.isAutoplayEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                spotifyPreferences.setAutoplayEnabled(newValue as Boolean)
                true
            }
        }

        findPreference<Preference>("spotify_playlist")?.apply {
            setOnPreferenceClickListener {
                if (!spotifyPreferences.isEnabled()) {
                    Toast.makeText(
                        requireContext(),
                        "Please enable and connect to Spotify first",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnPreferenceClickListener true
                }

                if (spotifyManager.connectionState.value !is SpotifyManager.ConnectionState.Connected) {
                    Toast.makeText(
                        requireContext(),
                        "Connecting to Spotify...",
                        Toast.LENGTH_SHORT
                    ).show()
                    spotifyManager.retry()
                    return@setOnPreferenceClickListener true
                }

                val loadingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Loading Playlists")
                    .setMessage("Please wait...")
                    .setCancelable(false)
                    .create()
                loadingDialog.show()

                spotifyManager.getPlaylists(
                    callback = { playlists ->
                        loadingDialog.dismiss()
                        Timber.d("Available playlists: ${playlists.map { "${it.title} (${it.uri})"}}")

                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Select Playlist")
                            .setItems(playlists.map { it.title }.toTypedArray()) { _, which ->
                                val selectedPlaylist = playlists[which]
                                Timber.d("Selected playlist: ${selectedPlaylist.title} with URI: ${selectedPlaylist.uri}")

                                spotifyPreferences.setSelectedPlaylist(selectedPlaylist.uri)
                                // Just save the title for now
                                spotifyPreferences.setPlaylistSummary(selectedPlaylist.title)

                                summary = selectedPlaylist.title
                                Toast.makeText(
                                    requireContext(),
                                    "Selected: ${selectedPlaylist.title}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    },
                    errorCallback = { error ->
                        loadingDialog.dismiss()
                        Timber.e(error, "Failed to load playlists")
                        Toast.makeText(
                            requireContext(),
                            "Error loading playlists: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
                true
            }

            // Update initial preference description to show currently selected playlist
            spotifyPreferences.getPlaylistSummary()?.let { savedSummary ->
                setSummary(savedSummary)
            } ?: run {
                // If no summary is saved, try to get playlist info from Spotify
                spotifyPreferences.getSelectedPlaylist()?.let { uri ->
                    lifecycleScope.launch {
                        spotifyManager.getPlaylistInfo(
                            uri = uri,
                            callback = { playlist ->
                                val summary = playlist?.title ?: "Select playlist"
                                spotifyPreferences.setPlaylistSummary(summary)  // Save the summary
                                findPreference<Preference>("spotify_playlist")?.setSummary(summary)
                            },
                            errorCallback = {
                                findPreference<Preference>("spotify_playlist")?.setSummary("Select playlist")
                            }
                        )
                    }
                } ?: run {
                    setSummary("Select playlist")
                }
            }
        }

        // Keep auth state observation
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                spotifyAuthManager.authState.collect { state ->
                    updateSpotifyLoginSummary()
                    when (state) {
                        is SpotifyAuthManager.AuthState.Authenticated -> {
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = true
                            spotifyPreferences.setEnabled(true)
                            spotifyPreferences.setConnectionState(true)
                            spotifyManager.retry()
                            updateSpotifyLoginSummary()
                        }
                        is SpotifyAuthManager.AuthState.Error -> {
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
                            spotifyPreferences.setEnabled(false)
                            spotifyPreferences.setConnectionState(false)
                            Toast.makeText(
                                requireContext(),
                                "Authentication failed: ${state.error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> {
                            // Only update UI based on stored preference
                            val isEnabled = spotifyPreferences.isEnabled()
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = isEnabled
                            if (!isEnabled) {
                                spotifyPreferences.setConnectionState(false)
                            }
                        }
                    }
                }
            }
        }
        // Add a collector to update the preference description whenever the playlist changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                spotifyManager.connectionState.collect { state ->
                    when (state) {
                        is SpotifyManager.ConnectionState.Connected -> {
                            // First try to use the saved summary
                            val savedSummary = spotifyPreferences.getPlaylistSummary()
                            if (savedSummary != null) {
                                findPreference<Preference>("spotify_playlist")?.setSummary(savedSummary)
                            } else {
                                // Only fetch from Spotify if we don't have a saved summary
                                spotifyPreferences.getSelectedPlaylist()?.let { uri ->
                                    spotifyManager.getPlaylistInfo(
                                        uri = uri,
                                        callback = { playlist ->
                                            val summary = playlist?.title ?: "Select playlist"
                                            spotifyPreferences.setPlaylistSummary(summary)  // Save the summary
                                            findPreference<Preference>("spotify_playlist")?.setSummary(summary)
                                        },
                                        errorCallback = {
                                            findPreference<Preference>("spotify_playlist")?.setSummary("Select playlist")
                                        }
                                    )
                                }
                            }
                        }
                        else -> {
                            // Keep current summary from preferences
                            spotifyPreferences.getPlaylistSummary()?.let { savedSummary ->
                                findPreference<Preference>("spotify_playlist")?.setSummary(savedSummary)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showSpotifyInstallDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.spotify_not_installed_title)
            .setMessage(R.string.spotify_not_installed_message)
            .setPositiveButton(R.string.install) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.spotify.music")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.spotify.music")))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSpotifyLoginSummary() {
        findPreference<Preference>("spotify_login")?.apply {
            // First check if Spotify is enabled at all
            if (!spotifyPreferences.isEnabled()) {
                summary = "Not connected"
                isEnabled = true
                return@apply
            }

            // Then check the current connection state
            when (val state = spotifyManager.connectionState.value) {
                is SpotifyManager.ConnectionState.Connected -> {
                    // Get user info when connected
                    spotifyManager.getCurrentUser(
                        callback = { user ->
                            // Ensure we update UI on main thread
                            Handler(Looper.getMainLooper()).post {
                                // Double check we're still connected when callback returns
                                if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                                    val displayName = user?.displayName ?: user?.id
                                    if (displayName != null) {
                                        summary = "Connected as $displayName"
                                    } else {
                                        summary = "Connected to Spotify"
                                    }
                                    isEnabled = true
                                }
                            }
                        },
                        errorCallback = { error ->
                            Handler(Looper.getMainLooper()).post {
                                Timber.e(error, "Failed to get Spotify user info")
                                if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                                    summary = "Connected to Spotify"
                                    isEnabled = true
                                }
                            }
                        }
                    )
                    // Set temporary state while we fetch user info
                    summary = "Connected to Spotify"
                    isEnabled = true
                }
                is SpotifyManager.ConnectionState.Error -> {
                    summary = "Connection error: ${(state.error.message ?: "Unknown error")}"
                    isEnabled = true
                }
                is SpotifyManager.ConnectionState.Disconnected -> {
                    val authState = spotifyAuthManager.authState.value
                    summary = when {
                        authState is SpotifyAuthManager.AuthState.Authenticated && spotifyPreferences.wasConnected() -> {
                            "Disconnected - tap to reconnect"
                        }
                        authState is SpotifyAuthManager.AuthState.Authenticated -> {
                            "Tap to connect"
                        }
                        else -> {
                            "Not connected"
                        }
                    }
                    isEnabled = true
                }
            }

            Timber.d("""
            Spotify login summary updated:
            Connection state: ${spotifyManager.connectionState.value}
            Auth state: ${spotifyAuthManager.authState.value}
            Was connected: ${spotifyPreferences.wasConnected()}
            Is enabled: ${spotifyPreferences.isEnabled()}
            Current summary: $summary
        """.trimIndent())
        }
    }
}