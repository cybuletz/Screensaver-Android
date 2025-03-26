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
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
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
import com.example.screensaver.music.MusicSourcesDialog
import com.example.screensaver.music.SpotifyManager
import com.example.screensaver.music.SpotifyAuthManager
import com.example.screensaver.music.SpotifyPreferences
import com.example.screensaver.utils.BrightnessManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import com.google.android.material.color.MaterialColors
import com.example.screensaver.ads.AdManager
import com.example.screensaver.version.AppVersionManager
import com.example.screensaver.version.FeatureManager
import com.example.screensaver.version.ProVersionPromptDialog
import android.widget.FrameLayout


@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

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
    lateinit var spotifyPreferences: SpotifyPreferences

    @Inject
    lateinit var brightnessManager: BrightnessManager

    @Inject
    lateinit var appVersionManager: AppVersionManager

    @Inject
    lateinit var featureManager: FeatureManager

    @Inject
    lateinit var adManager: AdManager

    private lateinit var adContainer: FrameLayout

    private var widgetPreferenceFragment: WidgetPreferenceFragment? = null

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

        // Create a LinearLayout to hold the title and preferences
        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Create the title TextView
        val titleText = TextView(requireContext()).apply {
            text = getString(R.string.settings_title)
            textSize = 32f
            setTypeface(null, Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setPadding(80, 84, 24, 20)
        }

        // Create a CoordinatorLayout to wrap everything
        val coordinator = CoordinatorLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
        }

        val rootView = view as CoordinatorLayout
        adContainer = FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            visibility = View.GONE
        }
        rootView.addView(adContainer)
        coordinator.addView(adContainer)

        // Initialize ad manager for settings
        adManager.setupSettingsFragmentAd(adContainer)

        // Add the preferences view with Material You styling
        view.apply {
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, android.R.color.transparent))
            setPadding(
                resources.getDimensionPixelSize(R.dimen.settings_card_margin),
                resources.getDimensionPixelSize(R.dimen.settings_card_margin),
                resources.getDimensionPixelSize(R.dimen.settings_card_margin),
                resources.getDimensionPixelSize(R.dimen.settings_card_margin)
            )
        }

        // Add views to layout
        contentLayout.addView(titleText)
        contentLayout.addView(view)
        coordinator.addView(contentLayout)

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
            elevation = resources.getDimension(R.dimen.settings_card_elevation)
            setOnClickListener {
                saveSettings()
                findNavController().navigateUp()
            }
        }
        coordinator.addView(fab)

        return coordinator

    }

    private fun checkFeatureAccess(feature: FeatureManager.Feature): Boolean {
        if (!featureManager.isFeatureAvailable(feature)) {
            if (featureManager.showProVersionPrompt(feature)) {
                showProVersionPrompt(feature)
            }
            return false
        }
        return true
    }

    private fun showProVersionPrompt(feature: FeatureManager.Feature) {
        ProVersionPromptDialog.newInstance(feature)
            .show(childFragmentManager, "pro_version_prompt")
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

        // Load preferences immediately on main thread
        setPreferencesFromResource(R.xml.preferences, rootKey)
        preferenceScreen.isIconSpaceReserved = true

        // Then launch coroutine for background operations
        lifecycleScope.launch {
            try {
                // Background operations
                withContext(Dispatchers.IO) {
                    // Any heavy loading operations can go here
                }

                // UI operations on main thread
                withContext(Dispatchers.Main) {
                    // First restore state
                    restoreSettingsState()

                    // Then initialize all components
                    setupPhotoDisplayManager()
                    setupChargingPreference()

                    // Setup non-click preferences
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

                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up preferences", e)
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        Log.d("SettingsFragment", "onPreferenceTreeClick: ${preference.key}")

        return when (preference.key) {
            "display_settings" -> {
                try {
                    Log.d("SettingsFragment", "Showing DisplaySettingsDialog")
                    val dialog = DisplaySettingsDialog.newInstance()
                    dialog.show(childFragmentManager, "display_settings")
                    true
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Error showing display settings dialog", e)
                    false
                }
            }
            "common_settings" -> {
                try {
                    Log.d("SettingsFragment", "Showing PhotoShowSettingsDialog")
                    val dialog = PhotoShowSettingsDialog.newInstance()
                    dialog.show(childFragmentManager, "common_settings")
                    true
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Error showing common settings dialog", e)
                    false
                }
            }
            "manage_photos" -> {
                try {
                    Log.d("SettingsFragment", "Starting PhotoManagerActivity")
                    startActivity(Intent(requireContext(), PhotoManagerActivity::class.java))
                    true
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Error starting PhotoManagerActivity", e)
                    false
                }
            }
            "spotify_preferences", "radio_preferences" -> {
                if (checkFeatureAccess(FeatureManager.Feature.MUSIC)) {
                    // Original music settings code
                    MusicSourcesDialog.newInstance()
                        .show(childFragmentManager, "music_sources")
                }
                true
            }
            "clock_widget_settings", "weather_widget_settings", "music_widget_settings" -> {
                if (checkFeatureAccess(FeatureManager.Feature.WIDGETS)) {
                    // Original widget code
                    val widgetType = when (preference.key) {
                        "clock_widget_settings" -> WidgetType.CLOCK
                        "weather_widget_settings" -> WidgetType.WEATHER
                        "music_widget_settings" -> WidgetType.MUSIC
                        else -> WidgetType.CLOCK
                    }
                    showWidgetDialog(widgetType)
                }
                true
            }
            "security_preferences" -> {
                if (checkFeatureAccess(FeatureManager.Feature.SECURITY)) {
                    // Original security code
                    SecurityPreferenceDialog.newInstance()
                        .show(childFragmentManager, "security_settings")
                }
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun setupAdIntervalPreference() {
        findPreference<SeekBarPreference>("ad_interval_minutes")?.apply {
            isVisible = !appVersionManager.isProVersion()

            setOnPreferenceChangeListener { _, newValue ->
                val intervalMinutes = newValue as Int
                val intervalMillis = intervalMinutes * 60 * 1000L
                appVersionManager.setAdInterval(intervalMillis)
                summary = "Show ads every $intervalMinutes minutes"
                true
            }

            // Set initial summary
            val currentIntervalMillis = appVersionManager.getAdInterval()
            val currentIntervalMinutes = (currentIntervalMillis / (60 * 1000)).toInt()
            summary = "Show ads every $currentIntervalMinutes minutes"
            value = currentIntervalMinutes
        }
    }

    override fun onResume() {
        super.onResume()
        adManager.loadSettingsAd()
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

}