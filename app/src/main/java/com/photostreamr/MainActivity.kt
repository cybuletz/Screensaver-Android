package com.photostreamr

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.photostreamr.databinding.ActivityMainBinding
import com.photostreamr.ui.PhotoDisplayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.photostreamr.settings.SettingsButtonController
import com.photostreamr.utils.AppPreferences
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.net.Uri
import androidx.constraintlayout.widget.ConstraintLayout
import com.photostreamr.security.AppAuthManager
import com.photostreamr.security.BiometricHelper
import com.photostreamr.security.PasscodeDialog
import com.photostreamr.security.SecurityPreferences
import com.photostreamr.utils.NotificationHelper
import com.photostreamr.widgets.WidgetData
import com.photostreamr.widgets.WidgetManager
import com.photostreamr.widgets.WidgetState
import com.photostreamr.widgets.WidgetType
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.photostreamr.data.SecureStorage
import com.photostreamr.music.RadioManager
import com.photostreamr.music.RadioPreferences
import com.photostreamr.music.SpotifyManager
import com.photostreamr.music.SpotifyPreferences
import com.photostreamr.photos.PhotoManagerViewModel
import com.photostreamr.photos.PhotoUriManager
import com.photostreamr.utils.BrightnessManager
import com.photostreamr.utils.PreferenceKeys
import com.photostreamr.utils.ScreenOrientation
import com.photostreamr.ads.AdManager
import com.photostreamr.ads.ConsentManager
import com.photostreamr.version.AppVersionManager
import com.photostreamr.version.FeatureManager
import com.photostreamr.music.LocalMusicManager
import com.photostreamr.music.LocalMusicPreferences
import com.photostreamr.ui.BitmapMemoryManager
import com.photostreamr.ui.DiskCacheManager
import com.photostreamr.version.ProVersionPromptManager
import java.util.concurrent.atomic.AtomicBoolean
import com.photostreamr.sharing.SocialSharingManager
import com.photostreamr.sharing.ShareButtonController



@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private var _navController: NavController? = null
    private val navController get() = _navController!!
    private lateinit var settingsButtonController: SettingsButtonController
    private val photoManagerViewModel: PhotoManagerViewModel by viewModels()

    @Inject
    lateinit var photoRepository: PhotoRepository

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var widgetManager: WidgetManager

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var authManager: AppAuthManager

    @Inject
    lateinit var securityPreferences: SecurityPreferences

    @Inject
    lateinit var biometricHelper: BiometricHelper

    @Inject
    lateinit var secureStorage: SecureStorage

    @Inject
    lateinit var spotifyManager: SpotifyManager

    @Inject
    lateinit var spotifyPreferences: SpotifyPreferences

    @Inject
    lateinit var radioPreferences: RadioPreferences

    @Inject
    lateinit var radioManager: RadioManager

    @Inject
    lateinit var localMusicManager: LocalMusicManager

    @Inject
    lateinit var localMusicPreferences: LocalMusicPreferences

    @Inject
    lateinit var brightnessManager: BrightnessManager

    @Inject
    lateinit var photoUriManager: PhotoUriManager

    @Inject
    lateinit var appVersionManager: AppVersionManager

    @Inject
    lateinit var featureManager: FeatureManager

    @Inject
    lateinit var adManager: AdManager

    @Inject
    lateinit var consentManager: ConsentManager

    @Inject
    lateinit var proVersionPromptManager: ProVersionPromptManager

    @Inject
    lateinit var bitmapMemoryManager: BitmapMemoryManager

    @Inject
    lateinit var diskCacheManager: DiskCacheManager

    @Inject
    lateinit var socialSharingManager: SocialSharingManager

    private lateinit var shareButtonController: ShareButtonController

    private var isDestroyed = false

    private var isAuthenticating = false

    private val PREF_FIRST_LAUNCH = "first_launch"
    private val FULL_SCREEN_AD_CHECK_INTERVAL = 60000L

    private var currentActivity: Activity? = null

    private var photoDisplayInitiated = false
    private var photoDisplayLaunched = AtomicBoolean(false)

    private var shareButtonTapHandler = Handler(Looper.getMainLooper())
    private var shareButtonTapCount = 0


    private val viewLifecycleOwner: LifecycleOwner?
        get() = try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            navHostFragment?.viewLifecycleOwner
        } catch (e: Exception) {
            Log.e(TAG, "Error getting viewLifecycleOwner", e)
            null
        }

    companion object {
        private const val TAG = "MainActivity"
        private const val MENU_CLEAR_CACHE = Menu.FIRST + 1
        private const val PHOTO_TRANSITION_DELAY = 500L
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val SECURITY_CHECK_DELAY = 500L
    }

    private fun continueWithAuthenticated() {
        if (navController.currentDestination?.id == R.id.mainFragment) {
            photoDisplayManager.startPhotoDisplay()
        }
    }

    private fun checkForAds() {
        // For MainActivity, simply ensure the banner is loaded
        if (!appVersionManager.isProVersion() && appVersionManager.shouldShowAd()) {
            appVersionManager.updateLastAdShownTime()
        }
    }

    private fun setupFullScreenInterstitialTimer() {
        if (appVersionManager.isProVersion()) {
            return
        }

        // Schedule periodic checks for full screen interstitial
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (!isDestroyed && !appVersionManager.isProVersion()) {
                    // Check if it's time to show a full screen interstitial
                    adManager.checkAndShowFullScreenInterstitial(this@MainActivity)
                }

                // Schedule the next check
                handler.postDelayed(this, FULL_SCREEN_AD_CHECK_INTERVAL)
            }
        }

        // Start the periodic check
        handler.postDelayed(runnable, FULL_SCREEN_AD_CHECK_INTERVAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this

        // Add this near the beginning to capture all log output
        Log.i(TAG, "=== MainActivity onCreate started ===")

        PreferenceManager.setDefaultValues(this, R.xml.photoshow_settings_preferences, true)
        PreferenceManager.setDefaultValues(this, R.xml.display_preferences, true)

        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if this is a cold start (savedInstanceState is null) and security is enabled
        if (savedInstanceState == null && securityPreferences.isSecurityEnabled) {
            Log.d(TAG, "Cold start detected - removing security settings")
            securityPreferences.isSecurityEnabled = false
            secureStorage.clearSecurityCredentials()
            Log.d(TAG, "Security settings have been removed")
        }
        // Check if security should be removed on minimize state change
        else if (secureStorage.shouldRemoveSecurityOnMinimize()) {
            Log.d(TAG, "Removing security settings on minimize")
            securityPreferences.isSecurityEnabled = false
            secureStorage.clearSecurityCredentials()
            Log.d(TAG, "Security settings have been removed")
        }

        enableFullScreen()

        // Add version state observation
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appVersionManager.versionState.collect { state ->
                    when (state) {
                        is AppVersionManager.VersionState.Pro -> {
                            // Destroy all ads immediately
                            adManager.destroyAds()
                        }
                        is AppVersionManager.VersionState.Free -> {
                            // We're not setting up banner ads anymore
                        }
                    }
                }
            }
        }

        // Initialize consent management before ad initialization
        initializeConsent()

        // Initialize ad manager - only for interstitial ads now
        try {
            adManager.initialize() // No parameters
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ad manager", e)
        }

        if (securityPreferences.isSecurityEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startLockTask()
            }
        }

        try {
            if (intent?.getBooleanExtra("start_screensaver", false) == true) {
                Log.d(TAG, "Started from charging receiver at ${intent?.getLongExtra("timestamp", 0L)}")
            }

            ensureBinding()
            observeSecurityState()
            observeSecurityPreferences()
            initializeWidgetSystem()
            setupFullScreen()
            setupFirstLaunchUI()
            setupNavigation()
            setupSettingsButton()
            setupTouchListener()
            initializePhotoDisplayManager()

            // with centralized photo initialization
            initializePhotosOnce()

            preventUnauthorizedClosure()

            observeWidgetStates()
            setupKeepScreenOnObserver()
            updateOrientation()
            initializeMusicSources()

            if (brightnessManager.isCustomBrightnessEnabled()) {
                brightnessManager.startMonitoring(window)
            }

            // Start automatic recovery systems for long-running passive use
            startAutomaticRecoverySystems()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Error initializing app")
            finish()
        }

        Log.i(TAG, "=== MainActivity onCreate completed ===")
    }

    private fun initializePhotosOnce() {
        // Skip if already launched
        if (photoDisplayLaunched.getAndSet(true)) {
            Log.w(TAG, "Photo display has already been launched, ignoring additional attempts")
            return
        }

        lifecycleScope.launch {
            try {
                // Validate photos first
                photoRepository.validateStoredPhotos()

                // Update photo sources
                photoDisplayManager.updatePhotoSources()

                // Allow some time for the UI to settle
                delay(750)

                // Check charging and start preferences
                val startFromCharging = intent?.getBooleanExtra("start_screensaver", false) == true

                // Start photo display if needed
                if (navController.currentDestination?.id == R.id.mainFragment) {
                    Log.i(TAG, "Starting photo display (from main initialization)")
                    photoDisplayManager.startPhotoDisplay()
                } else if (startFromCharging) {
                    Log.i(TAG, "Navigating to main and starting display (from charging)")
                    try {
                        navController.navigate(R.id.mainFragment)
                        delay(500)
                        setupFullScreen()
                        photoDisplayManager.startPhotoDisplay()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting from charging", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in initializePhotosOnce", e)
            }
        }
    }

    // Start automatic recovery systems for long-running passive use
    private fun startAutomaticRecoverySystems() {
        // Start automatic ad system reinitialization
        adManager.startAutomaticReinitializer()

        // Start photo slideshow recovery monitoring
        photoDisplayManager.startAutomaticRecoveryMonitoring()

        Log.d(TAG, "Automatic recovery systems started for passive long-running use")
    }

    private fun ensureBinding() {
        if (_binding == null) {
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        }
    }

    private fun checkFeatureAccess(feature: FeatureManager.Feature): Boolean {
        return featureManager.isFeatureAvailable(feature)
    }

    private fun initializeMusicSources() {
        if (!checkFeatureAccess(FeatureManager.Feature.MUSIC)) {
            return
        }

        val musicSource = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("music_source", "spotify") ?: "spotify"

        Log.d(TAG, "Initializing music source: $musicSource")

        when (musicSource) {
            "spotify" -> {
                if (spotifyPreferences.isEnabled() && spotifyManager.isSpotifyInstalled()) {
                    spotifyManager.checkAndRefreshTokenIfNeeded()
                }
            }
            "radio" -> {
                if (radioPreferences.isEnabled()) {
                    radioManager.initializeState()
                    lifecycleScope.launch {
                        delay(100)
                        radioManager.tryAutoResume()
                        Log.d(TAG, "Called radio auto-resume")
                    }
                }
            }
            "local" -> {  // Add this case
                if (localMusicPreferences.isEnabled()) {
                    localMusicManager.initialize()
                    // Auto-resume if needed
                    if (localMusicPreferences.wasPlaying() && localMusicPreferences.isAutoplayEnabled()) {
                        lifecycleScope.launch {
                            delay(100)
                            localMusicManager.resume()
                            Log.d(TAG, "Auto-resumed local music playback")
                        }
                    }
                }
            }
        }
    }

    private fun initializeWidgetSystem() {
        if (!checkFeatureAccess(FeatureManager.Feature.WIDGETS)) {
            return
        }
        Log.d(TAG, "Starting widget system initialization")
        try {
            ensureBinding()
            // Use widgets_layer instead of screensaverContainer
            binding.screensaverContainer?.findViewById<ConstraintLayout>(R.id.widgets_layer)?.post {
                try {
                    Log.d(TAG, "Container posted callback executing")
                    binding.screensaverContainer?.findViewById<ConstraintLayout>(R.id.widgets_layer)?.let { container ->
                        if (container is ConstraintLayout) {
                            Log.d(TAG, "Setting up widgets in ConstraintLayout")

                            // Set up all widgets with proper logging
                            widgetManager.setupClockWidget(container)
                            widgetManager.setupWeatherWidget(container)

                            // Setup music widget with proper logging
                            Log.d(TAG, "Setting up music widget")
                            widgetManager.setupMusicWidget(container)

                            // Show widgets based on preferences with proper checks
                            val showClock = preferences.isShowClock()
                            if (showClock) {
                                Log.d(TAG, "Showing clock widget")
                                widgetManager.showWidget(WidgetType.CLOCK)
                            }

                            val showWeather = preferences.getBoolean("show_weather", false)
                            if (showWeather) {
                                Log.d(TAG, "Showing weather widget")
                                widgetManager.showWidget(WidgetType.WEATHER)
                            }

                            // Check both Spotify and general music preference
                            val showMusic = spotifyPreferences.isEnabled() ||
                                    preferences.getBoolean("show_music_controls", false)
                            if (showMusic) {
                                Log.d(TAG, "Music is enabled, showing music widget")
                                // Force update music config first
                                widgetManager.updateMusicConfig()
                                // Then show the widget
                                widgetManager.showWidget(WidgetType.MUSIC)
                            } else {
                                Log.d(TAG, "Music is not enabled")
                            }

                            Log.d(TAG, """
                            Widgets initialized:
                            - Clock: $showClock
                            - Weather: $showWeather
                            - Music: $showMusic (Spotify: ${spotifyPreferences.isEnabled()})
                        """.trimIndent())
                        } else {
                            Log.e(TAG, "Container is not a ConstraintLayout, it is: ${container.javaClass.simpleName}")
                        }
                    } ?: Log.e(TAG, "widgets_layer is null")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in widget system initialization", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing widget system", e)
        }
    }

    private fun observeWidgetStates() {
        lifecycleScope.launch {
            widgetManager.widgetStates.collect { states ->
                states.forEach { (type, data) ->
                    when (type) {
                        WidgetType.CLOCK -> handleClockWidgetState(data)
                        WidgetType.WEATHER -> handleWeatherWidgetState(data)
                        WidgetType.MUSIC -> handleMusicWidgetState(data)
                    }
                }
            }
        }
    }

    private fun observeSecurityState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (securityPreferences.isSecurityEnabled && !authManager.isAuthenticated()) {
                    checkSecurityWithCallback {
                        // Continue with normal app flow
                        continueWithAuthenticated()
                    }
                }
            }
        }
    }

    private fun observeSecurityPreferences() {
        lifecycleScope.launch {
            securityPreferences.securityEnabledFlow.collect { isEnabled ->
                if (isEnabled) {
                    setupSecurityLock()
                } else {
                    clearSecurityState()
                }
            }
        }
    }

    private fun handleWeatherWidgetState(data: WidgetData) {
        when (data.state) {
            is WidgetState.Error -> {
                Log.e(TAG, "Weather widget error: ${(data.state as WidgetState.Error).message}")
                showToast("Error with weather widget")
            }
            is WidgetState.Active -> {
                Log.d(TAG, "Weather widget is active")
            }
            is WidgetState.Hidden -> {
                Log.d(TAG, "Weather widget is hidden")
            }
            is WidgetState.Loading -> {
                Log.d(TAG, "Weather widget is loading")
            }
            else -> {
                Log.d(TAG, "Weather widget in unknown state: ${data.state}")
            }
        }
    }

    private fun handleClockWidgetState(data: WidgetData) {
        when (data.state) {
            is WidgetState.Error -> {
                Log.e(TAG, "Clock widget error: ${(data.state as WidgetState.Error).message}")
                showToast("Error with clock widget")
            }
            is WidgetState.Active -> {
                Log.d(TAG, "Clock widget is active")
            }
            is WidgetState.Hidden -> {
                Log.d(TAG, "Clock widget is hidden")
            }
            is WidgetState.Loading -> {
                Log.d(TAG, "Clock widget is loading")
            }
            else -> {
                Log.d(TAG, "Clock widget in unknown state: ${data.state}")
            }
        }
    }

    private fun handleMusicWidgetState(data: WidgetData) {
        when (data.state) {
            is WidgetState.Error -> {
                Log.e(TAG, "Music widget error: ${(data.state as WidgetState.Error).message}")
                showToast("Error with music widget")
            }
            is WidgetState.Active -> {
                Log.d(TAG, "Music widget is active")
            }
            is WidgetState.Hidden -> {
                Log.d(TAG, "Music widget is hidden")
            }
            is WidgetState.Loading -> {
                Log.d(TAG, "Music widget is loading")
            }
            else -> {
                Log.d(TAG, "Music widget in unknown state: ${data.state}")
            }
        }
    }

    private fun setupFirstLaunchUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true)

        Log.d(TAG, "Setting up first launch UI - isFirstLaunch: $isFirstLaunch")

        if (isFirstLaunch) {
            // Hide info container (which contains clock and date)
            binding.infoContainer.apply {
                visibility = View.GONE
                Log.d(TAG, "Info container visibility set to GONE")
            }

            // Show setup message
            binding.initialSetupMessage.apply {
                visibility = View.VISIBLE
                bringToFront()
                Log.d(TAG, "Initial setup message visibility set to VISIBLE")
            }

            // Show legal links
            binding.legalLinksContainer.apply {
                visibility = View.VISIBLE
                bringToFront()
                Log.d(TAG, "Legal links container visibility set to VISIBLE")
            }

            // Setup click listeners for links
            binding.termsLink.setOnClickListener {
                Log.d(TAG, "Terms link clicked")
                openUrl("https://photostreamr.cybu.site/terms")
            }

            binding.privacyLink.setOnClickListener {
                Log.d(TAG, "Privacy link clicked")
                openUrl("https://photostreamr.cybu.site/privacy")
            }
        } else {
            binding.infoContainer.visibility = View.VISIBLE
            binding.initialSetupMessage.visibility = View.GONE
            binding.legalLinksContainer.visibility = View.GONE
            Log.d(TAG, "Not first launch - normal UI setup complete")
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening URL: $url", e)
            showToast("Could not open link")
        }
    }

    private fun setupTouchListener() {
        Log.d(TAG, "Setting up touch listeners")

        binding.root.setOnClickListener {
            Log.d(TAG, "Root view clicked, current destination: ${navController.currentDestination?.id}")
            if (navController.currentDestination?.id == R.id.mainFragment) {
                // Pause slideshow when showing buttons
                if (shouldShowShareButton()) {
                    photoDisplayManager.pauseSlideshow()
                }
                showActionButtons()
            }
        }

        binding.screensaverContainer.setOnClickListener {
            Log.d(TAG, "Screensaver container clicked, current destination: ${navController.currentDestination?.id}")
            if (navController.currentDestination?.id == R.id.mainFragment) {
                // Pause slideshow when showing buttons
                if (shouldShowShareButton()) {
                    photoDisplayManager.pauseSlideshow()
                }
                showActionButtons()
            }
        }
    }

    /**
     * Show both settings and share buttons (with conditions)
     */
    private fun showActionButtons() {
        Log.d(TAG, "Checking if action buttons should be shown")

        // Always show settings button
        settingsButtonController.show()

        // Only show share button if slideshow is active and has photos
        if (shouldShowShareButton()) {
            Log.d(TAG, "Showing share button - slideshow is active")
            shareButtonController.show()
        } else {
            Log.d(TAG, "Not showing share button - no active slideshow")
        }

        // Auto-hide both buttons after 3 seconds (like settings button behavior)
        Handler(Looper.getMainLooper()).postDelayed({
            hideActionButtons()
        }, 3000)
    }

    /**
     * Check if share button should be shown
     */
    private fun shouldShowShareButton(): Boolean {
        // Check if we're on the main fragment
        if (navController.currentDestination?.id != R.id.mainFragment) {
            return false
        }

        // Check if photo display manager is active and has photos
        val hasActiveSlideshow = photoDisplayManager.isScreensaverActive()
        val hasPhotos = try {
            photoRepository.getPhotoCount() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking photo count", e)
            false
        }

        // Check if not showing default photo (you might need to add this method to PhotoDisplayManager)
        val notShowingDefault = !isShowingDefaultPhoto()

        return hasActiveSlideshow && hasPhotos && notShowingDefault
    }

    /**
     * Check if currently showing default photo
     */
    private fun isShowingDefaultPhoto(): Boolean {
        // You can implement this by checking if the current photo URL is null or default
        // This is a simplified check - you might want to add a method to PhotoDisplayManager
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true)
            isFirstLaunch || !photoDisplayManager.isScreensaverActive()
        } catch (e: Exception) {
            true // Default to not showing share if we can't determine
        }
    }

    /**
     * Hide both settings and share buttons
     */
    private fun hideActionButtons() {
        settingsButtonController.hide()
        shareButtonController.hide()

        // Resume slideshow when buttons are hidden
        if (photoDisplayManager.isScreensaverActive()) {
            photoDisplayManager.resumeSlideshow()
        }
    }

    /**
     * Handle share button click
     */
    private fun handleShareButtonClick() {
        try {
            Log.d(TAG, "Share button clicked")

            // Hide both buttons immediately
            hideActionButtons()

            // Check if we should still allow sharing
            if (!shouldShowShareButton()) {
                Log.e(TAG, "Share not available - no active slideshow")
                showToast(getString(R.string.photo_not_available))
                return
            }

            // Get shareable view and elements to hide
            val shareableView = photoDisplayManager.getCurrentShareableView()
            if (shareableView == null) {
                Log.e(TAG, "No shareable view available")
                showToast(getString(R.string.photo_not_available))
                return
            }

            // Get UI elements that should be hidden during capture
            val elementsToHide = mutableListOf<View>().apply {
                // Hide both settings and share buttons
                add(binding.settingsButton.settingsFab)
                add(binding.shareButton)
                // Add other overlay elements
                addAll(photoDisplayManager.getOverlayElementsToHide())
            }

            // Start sharing process
            lifecycleScope.launch {
                try {
                    val success = socialSharingManager.shareCurrentView(shareableView, elementsToHide)
                    if (!success) {
                        showToast(getString(R.string.error_sharing_photo))
                    }

                    // Resume slideshow after sharing is done
                    photoDisplayManager.resumeSlideshow()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during sharing process", e)
                    showToast(getString(R.string.error_sharing_photo))
                    // Resume slideshow even on error
                    photoDisplayManager.resumeSlideshow()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling share button click", e)
            showToast(getString(R.string.error_sharing_photo))
            // Resume slideshow even on error
            photoDisplayManager.resumeSlideshow()
        }
    }

    private fun setupSettingsButton() {
        try {
            val settingsFab = binding.settingsButton.settingsFab
            val shareButton = binding.shareButton

            // Setup settings button (keep existing logic unchanged)
            settingsFab.apply {
                visibility = View.VISIBLE
                alpha = 0f
                elevation = 6f
                translationZ = 6f

                setOnClickListener {
                    if (navController.currentDestination?.id == R.id.mainFragment) {
                        if (securityPreferences.isSecurityEnabled) {
                            checkSecurityWithCallback {
                                authManager.setAuthenticated(true)
                                navigateToSettings()
                            }
                        } else {
                            navigateToSettings()
                        }
                    }
                }
            }

            // Create settings button controller (keep existing)
            settingsButtonController = SettingsButtonController(settingsFab)

            // Create share button controller
            shareButtonController = ShareButtonController(shareButton)

            // Setup share button click listener
            shareButtonController.setOnClickListener {
                if (navController.currentDestination?.id == R.id.mainFragment) {
                    handleShareButtonClick()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up action buttons", e)
        }
    }

    private fun navigateToSettings() {
        PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            .edit()
            .putBoolean(PREF_FIRST_LAUNCH, false)
            .apply()

        // Preload interstitial ads right before navigating to settings
        if (!appVersionManager.isProVersion()) {
            adManager.preloadInterstitialForSettings()
        }

        navController.navigate(R.id.action_mainFragment_to_settingsFragment)
        settingsButtonController.hide()
    }

    private fun initializeConsent() {
        // Request consent info
        consentManager.requestConsentInfo(this) { consentRequired ->
            // Setup observer to react to consent changes
            setupConsentObserver()

            if (consentRequired) {
                Log.d(TAG, "Consent is required, showing consent form")
                // The consent form will be shown automatically by the ConsentManager
            } else {
                Log.d(TAG, "Consent not required or already obtained")
                // Initialize ad manager - it will check consent status internally
                adManager.initialize()

                // Setup the consent observer to react to any consent state changes
                setupConsentObserver()
            }
        }
    }

    private fun setupConsentObserver() {
        // Setup the ad manager to observe consent state changes
        adManager.setupConsentObserver(this)
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        if (!biometricHelper.isBiometricAvailable()) {
            // Fall back to passcode if biometric not available
            if (securityPreferences.passcode != null) {
                showPasscodePrompt(onSuccess)
            } else {
                isAuthenticating = false
                showToast(getString(R.string.biometric_auth_failed))
            }
            return
        }

        biometricHelper.showBiometricPrompt(
            activity = this,
            onSuccess = {
                isAuthenticating = false
                authManager.setAuthenticated(true)
                onSuccess()
            },
            onError = { message ->
                // Fall back to passcode on error
                if (securityPreferences.passcode != null) {
                    showPasscodePrompt(onSuccess)
                } else {
                    isAuthenticating = false
                    showToast(getString(R.string.biometric_auth_failed))
                }
            },
            onFailed = {
                // Fall back to passcode on failure
                if (securityPreferences.passcode != null) {
                    showPasscodePrompt(onSuccess)
                } else {
                    isAuthenticating = false
                    showToast(getString(R.string.biometric_auth_failed))
                }
            }
        )
    }

    private fun showPasscodePrompt(onSuccess: () -> Unit) {
        PasscodeDialog.newInstance(
            mode = PasscodeDialog.Mode.VERIFY,
            title = getString(R.string.enter_passcode),
            message = getString(R.string.enter_passcode_to_continue)
        ).apply {
            isCancelable = false
            setCallback(object : PasscodeDialog.PasscodeDialogCallback {
                override fun onPasscodeConfirmed(passcode: String) {
                    if (authManager.authenticateWithPasscode(passcode)) {
                        isAuthenticating = false
                        authManager.setAuthenticated(true)
                        dismiss()
                        onSuccess()
                    }
                }

                override fun onError(message: String) {
                    isAuthenticating = false
                }

                override fun onDismiss() {
                    isAuthenticating = false
                }
            })
        }.show(supportFragmentManager, "verify_passcode")
    }

    private fun setupSecurityLock() {
        if (!checkFeatureAccess(FeatureManager.Feature.SECURITY)) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startLockTask()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun setupFullScreen() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)


        // Lock to current task when security is enabled
        if (securityPreferences.isSecurityEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startLockTask()
            }
        }
    }

    private fun enableFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        // Add a listener to maintain fullscreen when system UI visibility changes
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                // The system bars are visible. Make any desired adjustments.
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        }
    }

    private fun updateKeepScreenOn() {
        val keepScreenOn = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("keep_screen_on", true)

        val isSpotifyPlaying = spotifyManager.playbackState.value.let { state ->
            state is SpotifyManager.PlaybackState.Playing && state.isPlaying
        }

        val isRadioPlaying = radioManager.playbackState.value.let { state ->
            state is RadioManager.PlaybackState.Playing &&
                    (state as RadioManager.PlaybackState.Playing).isPlaying
        }

        val shouldKeepScreenOn = keepScreenOn && (
                photoDisplayManager.isScreensaverActive() ||
                        isSpotifyPlaying ||
                        isRadioPlaying
                )

        Log.d(TAG, "Screen keep-on updated: $shouldKeepScreenOn (preference: $keepScreenOn, spotify: $isSpotifyPlaying, radio: $isRadioPlaying)")

        if (shouldKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateOrientation() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val orientationValue = prefs.getString(PreferenceKeys.SCREEN_ORIENTATION, "SYSTEM") ?: "SYSTEM"

        try {
            val orientation = ScreenOrientation.valueOf(orientationValue)
            requestedOrientation = orientation.androidValue
            Log.d(TAG, "Screen orientation set to: $orientationValue")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting orientation", e)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun setupKeepScreenOnObserver() {
        // Initial state
        updateKeepScreenOn()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Combine both Spotify and Radio playback states
                launch {
                    spotifyManager.playbackState.collect { state ->
                        updateKeepScreenOn()
                    }
                }

                launch {
                    radioManager.playbackState.collect { state ->
                        updateKeepScreenOn()
                    }
                }
            }
        }

        // Observe preference changes
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener { _, key ->
                if (key == "keep_screen_on") {
                    updateKeepScreenOn()
                }
            }
    }

    private fun checkInitialChargingState() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val startOnCharge = prefs.getBoolean("start_on_charge", false)

        if (startOnCharge) {
            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            Log.d(TAG, "Initial charging state check - startOnCharge: $startOnCharge, isCharging: $isCharging")

            if (isCharging) {
                handleChargingState(true)
            }
        }
    }

    fun handleChargingState(isCharging: Boolean) {
        if (isDestroyed) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val startOnCharge = prefs.getBoolean("start_on_charge", false)

        Log.d(TAG, "Handling charging state: charging=$isCharging, startOnCharge=$startOnCharge")

        if (isCharging && startOnCharge && !photoDisplayLaunched.get()) {
            lifecycleScope.launch {
                try {
                    // Set the flag first to prevent race conditions
                    photoDisplayLaunched.set(true)

                    // Navigate to main fragment if needed
                    if (navController.currentDestination?.id != R.id.mainFragment) {
                        navController.navigate(R.id.mainFragment)
                    }

                    // Ensure fullscreen
                    setupFullScreen()

                    // Start photo display
                    photoDisplayManager.startPhotoDisplay()
                    Log.d(TAG, "Started photo display due to charging")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting photo display on charging", e)
                }
            }
        }
    }

    private fun initializePhotoDisplayManager() {
        lifecycleScope.launch {
            try {
                // Initialize views
                val views = PhotoDisplayManager.Views(
                    primaryView = binding.photoPreview,
                    overlayView = binding.photoPreviewOverlay,
                    locationView = binding.locationOverlay,
                    loadingIndicator = binding.loadingIndicator,
                    loadingMessage = binding.loadingMessage,
                    container = binding.screensaverContainer,
                    overlayMessageContainer = binding.overlayMessageContainer,
                    overlayMessageText = binding.overlayMessageText,
                    backgroundLoadingIndicator = binding.backgroundLoadingIndicator,
                    topLetterboxView = binding.topLetterboxView,
                    bottomLetterboxView = binding.bottomLetterboxView,
                    leftLetterboxView = binding.leftLetterboxView,
                    rightLetterboxView = binding.rightLetterboxView
                )

                // Initialize PhotoDisplayManager
                photoDisplayManager.initialize(views, lifecycleScope)

                // First validate stored photos to ensure we have valid permissions
                photoRepository.validateStoredPhotos()

                // Observe virtual albums from PhotoManagerViewModel
                lifecycleScope.launch {
                    photoManagerViewModel.virtualAlbums.collect { albums ->
                        val selectedAlbums = albums.filter { it.isSelected }

                        // Get photo URIs from selected albums
                        val photos = if (selectedAlbums.isEmpty()) {
                            // No virtual albums selected, use all photos from repository
                            photoRepository.getAllPhotos().map { Uri.parse(it.baseUrl) }
                        } else {
                            // Use photos from selected virtual albums
                            selectedAlbums.flatMap { album ->
                                album.photoUris.map { Uri.parse(it) }
                            }
                        }

                        // Before updating display, validate URIs
                        val validPhotos = photos.filter { uri ->
                            photoUriManager.hasValidPermission(uri)
                        }

                        if (validPhotos.size < photos.size) {
                            Log.w(TAG, "Found ${photos.size - validPhotos.size} invalid URIs, they will be skipped")
                        }

                        photoDisplayManager.updatePhotoSources(validPhotos)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing photo display manager", e)
            }
        }
    }

    private fun handleBackPress() {
        if (securityPreferences.isSecurityEnabled) {
            // First stop any lock task mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    stopLockTask()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping lock task", e)
                }
            }
            // Reset authentication state and move to back
            authManager.resetAuthenticationState()
            moveTaskToBack(true)
        } else {
            moveTaskToBack(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            navController.currentDestination?.id == R.id.settingsFragment -> {
                navController.navigateUp()
                super.onBackPressed()
            }
            securityPreferences.isSecurityEnabled -> {
                // Only check security when enabled
                checkSecurityWithCallback {
                    // Only after successful authentication
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            stopLockTask()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping lock task", e)
                        }
                    }
                    moveTaskToBack(true)
                    super.onBackPressed()
                }
            }
            else -> {
                // No security, just minimize immediately
                moveTaskToBack(true)
                super.onBackPressed()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun preventUnauthorizedClosure() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                navController.currentDestination?.id == R.id.settingsFragment -> {
                    navController.navigateUp()
                }
                navController.currentDestination?.id == R.id.mainFragment -> {
                    if (securityPreferences.isSecurityEnabled) {
                        checkSecurityWithCallback {
                            handleBackPress()
                        }
                    } else {
                        handleBackPress()
                    }
                }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (securityPreferences.isSecurityEnabled && !authManager.isAuthenticated()) {
            checkSecurityWithCallback {
                // Continue with normal app flow
                continueWithAuthenticated()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent?.getBooleanExtra("albums_saved", false) == true) {
            val photoCount = intent.getIntExtra("photo_count", 0)
            val timestamp = intent.getLongExtra("timestamp", 0L)
            val forceReload = intent.getBooleanExtra("force_reload", false)

            Log.d(TAG, "Albums saved with count: $photoCount, timestamp: $timestamp")

            if (photoCount > 0) {
                lifecycleScope.launch {
                    try {
                        photoDisplayManager.stopPhotoDisplay()

                        if (forceReload) {
                            withContext(Dispatchers.IO) {
                                // Use PhotoRepository instead
                                val photos = photoRepository.getAllPhotos()
                                if (photos.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        photoDisplayManager.startPhotoDisplay()
                                    }
                                }
                            }
                        }

                        if (navController.currentDestination?.id != R.id.settingsFragment) {
                            navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling new photos", e)
                        showToast(getString(R.string.error_loading_photos))
                    }
                }
            }
        }

        // Handle legacy "photos_ready" intent for backward compatibility
        if (intent?.getBooleanExtra("photos_ready", false) == true) {
            val photoCount = intent.getIntExtra("photo_count", 0)
            val timestamp = intent.getLongExtra("timestamp", 0L)
            Log.d(TAG, "Photos ready with count: $photoCount, timestamp: $timestamp")

            if (photoCount > 0) {
                lifecycleScope.launch {
                    try {
                        // First navigate to settings fragment
                        if (navController.currentDestination?.id != R.id.settingsFragment) {
                            Log.d(TAG, "Navigating to settings fragment")
                            navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                        }

                        // Stop current display
                        photoDisplayManager.stopPhotoDisplay()

                        // Update visibility settings
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                        val intervalInt = prefs.getInt("photo_interval", 10000)

                        // Initialize PhotoDisplayManager with views - Updated to include the new letterbox views
                        val views = PhotoDisplayManager.Views(
                            primaryView = binding.photoPreview,
                            overlayView = binding.photoPreviewOverlay,
                            locationView = binding.locationOverlay,
                            loadingIndicator = binding.loadingIndicator,
                            loadingMessage = binding.loadingMessage,
                            container = binding.screensaverContainer,
                            overlayMessageContainer = binding.overlayMessageContainer,
                            overlayMessageText = binding.overlayMessageText,
                            backgroundLoadingIndicator = binding.backgroundLoadingIndicator,
                            topLetterboxView = binding.topLetterboxView,
                            bottomLetterboxView = binding.bottomLetterboxView,
                            leftLetterboxView = binding.leftLetterboxView,
                            rightLetterboxView = binding.rightLetterboxView
                        )

                        // Re-initialize PhotoDisplayManager
                        photoDisplayManager.initialize(views, lifecycleScope)

                        // Update PhotoDisplayManager settings
                        photoDisplayManager.updateSettings(
                            isRandomOrder = prefs.getBoolean("random_order", false)
                        )

                        // Let the settings screen handle starting the slideshow
                        Log.d(TAG, "Ready for settings configuration with clock")

                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling photos ready", e)
                        showToast("Error preparing display")
                    }
                }
            } else {
                Log.w(TAG, "Received photos_ready but count is 0")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (securityPreferences.isSecurityEnabled) {
                setupSecurityLock()
                if (!authManager.isAuthenticated()) {
                    checkSecurityWithCallback {
                        continueWithAuthenticated()
                    }
                }
            } else {
                enableFullScreen()  // Maintain fullscreen even without security
            }

            // Add brightness management after security checks
            if (brightnessManager.isCustomBrightnessEnabled()) {
                brightnessManager.startMonitoring(window)
            }
        }
    }

    private fun handleNavigationVisibility(destinationId: Int) {
        val isMainScreen = destinationId == R.id.mainFragment
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true)

        // Update visibility based on both navigation and first launch state
        binding.screensaverContainer.visibility = if (isMainScreen) View.VISIBLE else View.GONE

        // Keep setup message and legal links visible only on first launch
        binding.initialSetupMessage.visibility = if (isFirstLaunch) View.VISIBLE else View.GONE
        binding.legalLinksContainer.visibility = if (isFirstLaunch) View.VISIBLE else View.GONE
    }

    private fun checkSecurityWithCallback(onSuccess: () -> Unit) {
        if (!securityPreferences.isSecurityEnabled) {
            onSuccess()
            return
        }

        isAuthenticating = true

        // Check biometric first if enabled
        if (securityPreferences.allowBiometric) {
            showBiometricPrompt {
                isAuthenticating = false
                authManager.setAuthenticated(true)  // Keep authenticated after success
                onSuccess()
            }
        } else {
            // Biometric not enabled, use passcode
            if (securityPreferences.passcode != null) {
                showPasscodePrompt {
                    isAuthenticating = false
                    authManager.setAuthenticated(true)  // Keep authenticated after success
                    onSuccess()
                }
            } else {
                isAuthenticating = false
                showToast(getString(R.string.no_auth_method))
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Don't reset auth state for orientation changes
        isAuthenticating = true
        // Reset after config change is complete
        lifecycleScope.launch {
            delay(500)
            isAuthenticating = false
        }

        binding.screensaverContainer?.let { container ->
            if (container is ConstraintLayout) {
                if (spotifyPreferences.isEnabled()) {
                    widgetManager.updateMusicConfig()
                }
            }
        }
    }


    private fun setupNavigation() {
        try {
            ensureBinding()

            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            _navController = navHostFragment.navController

            navController.addOnDestinationChangedListener { _, destination, _ ->
                Log.d(TAG, "Navigation destination changed to: ${destination.id}")
                handleNavigationVisibility(destination.id)

                // Handle ad container visibility based on destination
                when (destination.id) {
                    R.id.settingsFragment -> {
                        if (securityPreferences.isSecurityEnabled && !authManager.isAuthenticated()) {
                            navController.navigateUp()
                            checkSecurityWithCallback {
                                navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                            }
                        }
                    }
                    R.id.mainFragment -> {
                        Log.d(TAG, "Returned to main fragment, updating widgets")
                        ensureBinding()
                        binding.screensaverContainer?.findViewById<ConstraintLayout>(R.id.widgets_layer)?.let { widgetsLayer ->
                            // Store the widgets_layer reference in WidgetManager
                            widgetManager.setContainer(widgetsLayer)

                            Log.d(TAG, "Found widgets_layer, setting up widgets")

                            // Set up all widgets first
                            widgetManager.setupClockWidget(widgetsLayer)
                            widgetManager.setupWeatherWidget(widgetsLayer)
                            widgetManager.setupMusicWidget(widgetsLayer)

                            // Then show them based on preferences
                            if (preferences.isShowClock()) {
                                widgetManager.showWidget(WidgetType.CLOCK)
                            }

                            if (preferences.getBoolean("show_weather", false)) {
                                widgetManager.showWidget(WidgetType.WEATHER)
                            }

                            val showMusic = spotifyPreferences.isEnabled() ||
                                    preferences.getBoolean("show_music_controls", false)
                            if (showMusic) {
                                Log.d(TAG, "Reinitializing music widget")
                                widgetManager.updateMusicConfig()
                                widgetManager.showWidget(WidgetType.MUSIC)
                            }
                        } ?: Log.e(TAG, "Could not find widgets_layer")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupNavigation", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // Make sure ads are properly paused
        try {
            adManager.pauseAds()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing ads", e)
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        brightnessManager.stopMonitoring()

        if (secureStorage.shouldRemoveSecurityOnMinimize()) {
            Log.d(TAG, "Removing security settings on minimize")
            securityPreferences.isSecurityEnabled = false
            secureStorage.clearSecurityCredentials()
            secureStorage.setRemoveSecurityOnMinimize(false)
        }

        // Only disconnect if not changing configurations
        if (!isChangingConfigurations) {
            when (PreferenceManager.getDefaultSharedPreferences(this)
                .getString("music_source", "spotify")) {
                "spotify" -> spotifyManager.disconnect()
                "radio" -> {
                    // First store state in preferences
                    val currentState = radioManager.playbackState.value
                    val currentStation = radioManager.currentStation.value

                    if (currentState is RadioManager.PlaybackState.Playing && currentStation != null) {
                        radioPreferences.setWasPlaying(currentState.isPlaying)
                        radioPreferences.setLastStation(currentStation)
                        Log.d(TAG, "Stored radio state: playing=${currentState.isPlaying}, station=${currentStation.name}")
                    } else if (currentStation != null) {
                        // Even if not playing, store the station
                        radioPreferences.setWasPlaying(false)
                        radioPreferences.setLastStation(currentStation)
                        Log.d(TAG, "Stored radio station: ${currentStation.name}")
                    }

                    // Now disconnect
                    radioManager.disconnect()
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (securityPreferences.isSecurityEnabled && !isAuthenticating) {
            moveTaskToBack(true)  // Force app to background on home button
        }
    }

    override fun onStop() {
        super.onStop()
        photoDisplayLaunched.set(false)

        authManager.resetAuthenticationState()
    }

    override fun onResume() {
        super.onResume()
        currentActivity = this
        try {
            adManager.resumeAds()
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming ads", e)
        }

        setupFullScreen()
        updateKeepScreenOn()
        initializeMusicSources()

        // Check for showing ads based on app state
        if (navController.currentDestination?.id == R.id.mainFragment) {
            // Only show interstitial ads when on main screen, not settings
            checkForAds()
        }

        // Handle music sources auto-resume
        when (PreferenceManager.getDefaultSharedPreferences(this).getString("music_source", "spotify")) {
            "spotify" -> {
                if (spotifyPreferences.isEnabled() && spotifyManager.isSpotifyInstalled()) {
                    spotifyManager.checkAndRefreshTokenIfNeeded()
                }
            }
            "radio" -> {
                if (radioPreferences.isEnabled()) {
                    // First initialize the state to ensure proper UI
                    radioManager.initializeState()
                    // Then try auto-resume if appropriate
                    lifecycleScope.launch {
                        delay(100) // Small delay to ensure UI is ready
                        radioManager.tryAutoResume()
                    }
                }
            }
        }

        // Always check security on resume if enabled
        if (securityPreferences.isSecurityEnabled && !authManager.isAuthenticated()) {
            checkSecurityWithCallback {
                // Only continue after successful authentication
                if (!isDestroyed && photoRepository.getAllPhotos().isNotEmpty() &&
                    navController.currentDestination?.id == R.id.mainFragment) {
                    photoDisplayManager.startPhotoDisplay()
                }
            }
        } else {
            // No security needed, continue normally
            if (!isDestroyed && photoRepository.getAllPhotos().isNotEmpty() &&
                navController.currentDestination?.id == R.id.mainFragment) {
                photoDisplayManager.startPhotoDisplay()
            }
        }

        if (brightnessManager.isCustomBrightnessEnabled()) {
            // Small delay to ensure window is ready
            Handler(Looper.getMainLooper()).postDelayed({
                brightnessManager.startMonitoring(window)
            }, 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, update weather
                    widgetManager.updateWeatherConfig()
                }
            }
        }
    }

    private fun showToast(message: String) {
        if (!isDestroyed) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.add(Menu.NONE, MENU_CLEAR_CACHE, Menu.NONE, "Clear Photo Cache")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CLEAR_CACHE -> {
                photoDisplayManager.clearPhotoCache()
                showToast("Photo cache cleared")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()

        // Access the SmartPhotoLayoutManager through the PhotoDisplayManager
        if (::photoDisplayManager.isInitialized) {
            // Call a new method we'll add to PhotoDisplayManager
            photoDisplayManager.onLowMemory()
        }
    }

    override fun onDestroy() {
        try {
            isDestroyed = true

            try {
                adManager.destroyAds()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying ads", e)
            }

            viewLifecycleOwner?.lifecycleScope?.launch(Dispatchers.Main) {
                withContext(NonCancellable) {
                    photoDisplayManager.cleanup()
                }
            }

            if (::settingsButtonController.isInitialized) {
                settingsButtonController.cleanup()
            }

            _binding = null
            _navController = null
            widgetManager.cleanup()
            currentActivity = null

            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
            currentActivity = null
            super.onDestroy()
        }
    }

    fun clearSecurityState() {
        try {
            // Stop lock task mode if active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    stopLockTask()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping lock task", e)
                }
            }

            // Clear ALL security-related window flags
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

            // Reset window attributes for display cutout mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }

            // Reset to default immersive mode without security
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

            // Maintain basic fullscreen experience
            enableFullScreen()

            // Reset any authentication state
            authManager.resetAuthenticationState()

            // Clear any pending security operations
            secureStorage.setRemoveSecurityOnMinimize(false)

            Log.d(TAG, "Security state completely cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing security state", e)
        }
    }
}