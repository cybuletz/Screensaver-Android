package com.photostreamr.settings

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.preference.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.photostreamr.ui.PhotoDisplayManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.navigation.fragment.findNavController
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import androidx.coordinatorlayout.widget.CoordinatorLayout
import android.view.Gravity
import com.photostreamr.data.AppDataManager
import com.photostreamr.data.AppDataState
import com.photostreamr.data.SecureStorage
import com.photostreamr.data.PhotoCache
import android.app.Activity
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceCategory
import androidx.preference.MultiSelectListPreference
import com.photostreamr.PhotoRepository
import com.photostreamr.widgets.WidgetManager
import androidx.preference.SwitchPreferenceCompat
import com.photostreamr.photos.PhotoManagerActivity
import com.photostreamr.utils.AppPreferences
import com.photostreamr.security.AppAuthManager
import com.photostreamr.security.BiometricHelper
import com.photostreamr.security.SecurityPreferenceDialog
import com.photostreamr.security.SecurityPreferences
import com.photostreamr.models.MediaItem
import com.photostreamr.PhotoRepository.PhotoAddMode
import com.photostreamr.music.MusicSourcesDialog
import com.photostreamr.music.SpotifyPreferences
import com.photostreamr.utils.BrightnessManager
import com.google.android.material.color.MaterialColors
import com.photostreamr.ads.AdManager
import com.photostreamr.version.AppVersionManager
import com.photostreamr.version.FeatureManager
import com.photostreamr.version.ProVersionPromptDialog
import android.widget.FrameLayout
import com.photostreamr.widgets.WidgetPreferenceFragment
import com.photostreamr.R
import com.photostreamr.tutorial.HelpDialogFragment
import com.photostreamr.tutorial.TutorialManager
import com.photostreamr.tutorial.TutorialOverlayFragment
import com.photostreamr.tutorial.TutorialType

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat(), TutorialOverlayFragment.TutorialCallback {

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
    lateinit var tutorialManager: TutorialManager

    @Inject
    lateinit var adManager: AdManager

    private lateinit var adContainer: FrameLayout

    private var widgetPreferenceFragment: WidgetPreferenceFragment? = null

    private var contentLayoutId: Int = View.NO_ID

    companion object {
        private const val TAG = "SettingsFragment"
        private const val REQUEST_SELECT_PHOTOS = 1001
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

        // Store contentLayout ID for later use
        contentLayoutId = View.generateViewId()

        // Create a LinearLayout to hold the title and preferences
        val contentLayout = LinearLayout(requireContext()).apply {
            id = contentLayoutId  // Use the stored ID
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Only add padding if not pro version
            setPadding(0, if (!appVersionManager.isProVersion())
                resources.getDimensionPixelSize(R.dimen.ad_height_with_margin) else 0, 0, 0)
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

        // Create a new container for the ad at the top
        if (!appVersionManager.isProVersion()) {
            adContainer = FrameLayout(requireContext()).apply {
                id = View.generateViewId()
                layoutParams = CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP
                }
                visibility = View.GONE
                elevation = 10f  // Ensure ad stays on top
            }
            // Add ad container only if not pro version
            coordinator.addView(adContainer)
        }

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

        // Initialize ad manager for settings only if not pro version
        if (!appVersionManager.isProVersion()) {
            adManager.setupSettingsFragmentAd(adContainer)
        }

        return coordinator
    }

    private fun checkFeatureAccess(feature: FeatureManager.Feature): Boolean {
        if (!featureManager.isFeatureAvailable(feature)) {
            if (featureManager.showProVersionPrompt(feature)) {
                // Use the version WITH the feature parameter for feature-specific prompts
                ProVersionPromptDialog.newInstance(feature)
                    .show(childFragmentManager, "feature_pro_prompt")
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

        // Add version state observation
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                appVersionManager.versionState.collect { state ->
                    when (state) {
                        is AppVersionManager.VersionState.Pro -> {
                            // Immediately remove ads and adjust layout
                            if (::adContainer.isInitialized) {
                                adContainer.removeAllViews()
                                adContainer.visibility = View.GONE
                                // Destroy the ad
                                adManager.destroyAds()
                            }
                            view.findViewById<LinearLayout>(contentLayoutId)?.apply {
                                setPadding(0, 0, 0, 0)
                                // Force layout update
                                requestLayout()
                            }
                        }
                        is AppVersionManager.VersionState.Free -> {
                            if (::adContainer.isInitialized) {
                                adContainer.visibility = View.VISIBLE
                                // Reinitialize ads
                                adManager.setupSettingsFragmentAd(adContainer)
                            }
                            view.findViewById<LinearLayout>(contentLayoutId)?.apply {
                                setPadding(0, resources.getDimensionPixelSize(R.dimen.ad_height_with_margin), 0, 0)
                                // Force layout update
                                requestLayout()
                            }
                        }
                    }
                }
            }
        }
        // Delay showing the tutorial to ensure the UI is fully rendered
        view.postDelayed({
            if (tutorialManager.isFirstLogin() && tutorialManager.shouldShowTutorial(TutorialType.SETTINGS)) {
                Log.d(TAG, "First login detected, showing tutorial")
                showTutorial()
            }
        }, 300)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        Log.d(TAG, "Setting up preferences")

        // Load preferences immediately on main thread
        setPreferencesFromResource(R.xml.preferences, rootKey)
        preferenceScreen.isIconSpaceReserved = true

        // Hide upgrade button if user already has PRO
        findPreference<Preference>("upgrade_to_pro")?.isVisible = !appVersionManager.isProVersion()

        // Then launch coroutine for background operations
        lifecycleScope.launch {
            try {
                // Observe PRO version state changes to update button visibility
                launch {
                    appVersionManager.versionState.collect { state ->
                        findPreference<Preference>("upgrade_to_pro")?.isVisible = state !is AppVersionManager.VersionState.Pro
                    }
                }

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
            "music_sources" -> {
                if (checkFeatureAccess(FeatureManager.Feature.MUSIC)) {
                    // Original music settings code
                    MusicSourcesDialog.newInstance()
                        .show(childFragmentManager, "music_sources")
                }
                true
            }
            "widgets_settings" -> {
                if (checkFeatureAccess(FeatureManager.Feature.WIDGETS)) {
                    try {
                        Log.d("SettingsFragment", "Showing WidgetsSettingsDialog")
                        val dialog = WidgetsSettingsDialog.newInstance()
                        dialog.show(childFragmentManager, "widgets_settings")
                        true
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Error showing widgets settings dialog", e)
                        false
                    }
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
            "upgrade_to_pro" -> {
                try {
                    Log.d("SettingsFragment", "Showing generic Pro version dialog")
                    // Use the parameterless newInstance() method here
                    ProVersionPromptDialog.newInstance()
                        .show(childFragmentManager, "pro_version_prompt")
                    true
                } catch (e: Exception) {
                    Log.e("SettingsFragment", "Error showing pro dialog", e)
                    false
                }
            }
            "help_preferences" -> {
                showHelpDialog()
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    override fun onResume() {
        super.onResume()
        adManager.loadSettingsAd()
    }

    override fun onStop() {
        super.onStop()
        widgetManager.reinitializeWeatherWidget()
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

    private fun updateWidgetsPreferenceSummary() {
        findPreference<Preference>("widgets_settings")?.apply {
            // Count how many widgets are enabled
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val clockEnabled = prefs.getBoolean("show_clock", false)
            val weatherEnabled = prefs.getBoolean("show_weather", false)
            val musicEnabled = prefs.getBoolean("show_music", false)

            val enabledCount = listOf(clockEnabled, weatherEnabled, musicEnabled).count { it }

            summary = if (enabledCount > 0) {
                getString(R.string.widgets_enabled_summary, enabledCount)
            } else {
                getString(R.string.widgets_settings_summary)
            }
        }
    }

    private fun observeWidgetState() {
        lifecycleScope.launch {
            widgetManager.widgetStates.collect { states ->
                updateWidgetsPreferenceSummary()
            }
        }
    }

    override fun scrollToPreference(preferenceKey: String) {
        Log.d(TAG, "Scrolling to preference: $preferenceKey")
        // Get the preference
        val preference = findPreference<Preference>(preferenceKey)
        if (preference != null) {
            // This will make sure the preference is visible
            scrollToPreference(preference)
        }
    }

    override fun getTargetView(viewId: Int): View? {
        Log.d(TAG, "Looking for preference with viewId: $viewId")

        val prefKey = when (viewId) {
            TutorialManager.ID_MANAGE_PHOTOS -> "manage_photos"
            TutorialManager.ID_MUSIC_SOURCES -> "music_sources"
            TutorialManager.ID_COMMON_SETTINGS -> "common_settings"
            TutorialManager.ID_WIDGETS_SETTINGS -> "widgets_settings"
            TutorialManager.ID_DISPLAY_SETTINGS -> "display_settings"
            TutorialManager.ID_SECURITY_PREFERENCES -> "security_preferences"
            else -> ""
        }

        Log.d(TAG, "Mapped to preference key: $prefKey")

        if (prefKey.isNotEmpty()) {
            val preference = findPreference<Preference>(prefKey)
            Log.d(TAG, "Found preference: ${preference?.title}")

            // Get the RecyclerView that holds the preferences
            val recyclerView = view?.findViewById<androidx.recyclerview.widget.RecyclerView>(
                androidx.preference.R.id.recycler_view
            )

            if (recyclerView != null) {
                // Add debug information for view finding
                Log.d(TAG, "RecyclerView found with ${recyclerView.childCount} visible children")

                // Look through all visible items in the RecyclerView
                for (i in 0 until recyclerView.childCount) {
                    val itemView = recyclerView.getChildAt(i)
                    val titleView = itemView.findViewById<TextView>(android.R.id.title)

                    Log.d(TAG, "Checking child $i: ${titleView?.text}")

                    if (titleView != null && preference != null &&
                        titleView.text.toString() == preference.title.toString()) {
                        Log.d(TAG, "Found exact match for: ${preference.title}")
                        return itemView
                    }
                }

                // If we couldn't find it by title, try to find by position based on preference order
                val allPreferences = preferenceScreen?.preferenceCount ?: 0
                var targetPosition = -1

                // Find position of the target preference in the hierarchy
                for (i in 0 until allPreferences) {
                    val pref = preferenceScreen?.getPreference(i)
                    if (pref?.key == prefKey) {
                        targetPosition = i
                        break
                    }
                }

                if (targetPosition >= 0 && targetPosition < recyclerView.childCount) {
                    val itemView = recyclerView.getChildAt(targetPosition)
                    Log.d(TAG, "Found preference by position: $targetPosition")
                    return itemView
                }

                // As a last resort, just return the first preference item that has a title
                for (i in 0 until recyclerView.childCount) {
                    val itemView = recyclerView.getChildAt(i)
                    if (itemView.findViewById<TextView>(android.R.id.title) != null) {
                        Log.d(TAG, "Returning first titled child at position $i as fallback")
                        return itemView
                    }
                }
            } else {
                Log.e(TAG, "RecyclerView not found!")
            }
        }

        // If all else fails, return the entire preferences view
        Log.w(TAG, "Falling back to entire view as target")
        return view
    }

    override fun onTutorialClosed() {
        // Do nothing for now, but you can add logic here if needed
    }

    private fun showTutorial() {
        val tutorialFragment = TutorialOverlayFragment.newInstance(TutorialType.SETTINGS)
        tutorialFragment.setCallback(this)
        tutorialFragment.show(childFragmentManager, "tutorial_overlay")
    }

    private fun showHelpDialog() {
        HelpDialogFragment.newInstance()
            .show(childFragmentManager, "help_dialog")
    }

    override fun onDestroyView() {
        widgetPreferenceFragment = null
        super.onDestroyView()
    }

}