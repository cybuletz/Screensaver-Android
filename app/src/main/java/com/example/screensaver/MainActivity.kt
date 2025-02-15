package com.example.screensaver

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
import com.example.screensaver.databinding.ActivityMainBinding
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.lock.PhotoLockScreenService
import com.example.screensaver.ui.PhotoDisplayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.example.screensaver.ui.SettingsButtonController
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.shared.GooglePhotosManager
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.Uri
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.screensaver.security.AppAuthManager
import com.example.screensaver.security.BiometricHelper
import com.example.screensaver.security.PasscodeDialog
import com.example.screensaver.security.SecurityPreferences
import com.example.screensaver.utils.NotificationHelper
import com.example.screensaver.widgets.WidgetData
import com.example.screensaver.widgets.WidgetManager
import com.example.screensaver.widgets.WidgetState
import com.example.screensaver.widgets.WidgetType
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.view.KeyEvent


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private var _navController: NavController? = null
    private val navController get() = _navController!!
    private lateinit var settingsButtonController: SettingsButtonController
    private var isPhotoTransitionInProgress = false

    @Inject
    lateinit var photoManager: LockScreenPhotoManager

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

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

    private var isDestroyed = false
    private var lastBackPressTime: Long = 0
    private val doubleBackPressInterval = 2000L

    private var isAuthenticating = false

    private val PREF_FIRST_LAUNCH = "first_launch"

    private var backPressStartTime = 0L
    private val BACK_PRESS_DURATION = 1000L

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

    private fun continueWithAuthenticated() {
        if (navController.currentDestination?.id == R.id.mainFragment) {
            photoDisplayManager.startPhotoDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()

        // Add these flags to prevent screenshots and recent apps preview
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (securityPreferences.isSecurityEnabled) {
            // Request lock task mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startLockTask()
            }
        }

        try {
            // Handle start from charging receiver
            if (intent?.getBooleanExtra("start_screensaver", false) == true) {
                Log.d(TAG, "Started from charging receiver at ${intent?.getLongExtra("timestamp", 0L)}")
            }

            ensureBinding()
            observeSecurityState()
            observeSecurityPreferences()

            // Initialize Widgets first
            initializeWidgetSystem()

            setupFullScreen()
            setupFirstLaunchUI()
            setupNavigation()
            setupSettingsButton()
            setupTouchListener()
            initializePhotoDisplayManager()
            startLockScreenService()
            initializePhotos()
            preventUnauthorizedClosure() // Add security closure prevention

            // Update photo sources and start display
            photoDisplayManager.updatePhotoSources()

            // Check initial charging state
            checkInitialChargingState()

            // Start photo display if on main fragment
            if (navController.currentDestination?.id == R.id.mainFragment) {
                lifecycleScope.launch {
                    delay(500) // Small delay to ensure everything is initialized
                    photoDisplayManager.startPhotoDisplay()
                }
            }

            // If started from charging, ensure we're in proper state
            if (intent?.getBooleanExtra("start_screensaver", false) == true) {
                lifecycleScope.launch {
                    try {
                        // Ensure we're on main fragment
                        if (navController.currentDestination?.id != R.id.mainFragment) {
                            navController.navigate(R.id.mainFragment)
                        }
                        delay(500) // Give time for navigation
                        setupFullScreen() // Ensure fullscreen
                        photoDisplayManager.startPhotoDisplay()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling charging start", e)
                    }
                }
            }

            // Observe widget states
            observeWidgetStates()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Error initializing app")
            finish()
        }
    }

    private fun initializeWidgetSystem() {
        Log.d(TAG, "Starting widget system initialization")
        try {
            ensureBinding()
            binding.screensaverContainer?.post {
                try {
                    Log.d(TAG, "Container posted callback executing")
                    binding.screensaverContainer?.let { container ->
                        if (container is ConstraintLayout) {
                            Log.d(TAG, "Setting up widgets in ConstraintLayout")

                            // Set up both widgets
                            widgetManager.setupClockWidget(container)
                            widgetManager.setupWeatherWidget(container)

                            // Immediately show widgets based on preferences
                            val showClock = preferences.isShowClock()
                            if (showClock) {
                                widgetManager.showWidget(WidgetType.CLOCK)
                            }

                            val showWeather = preferences.getBoolean("show_weather", false)
                            if (showWeather) {
                                widgetManager.showWidget(WidgetType.WEATHER)
                            }

                            Log.d(TAG, "Widgets initialized - Clock: $showClock, Weather: $showWeather")
                        } else {
                            Log.e(TAG, "Container is not a ConstraintLayout, it is: ${container.javaClass.simpleName}")
                        }
                    } ?: Log.e(TAG, "screensaverContainer is null")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in widget system initialization", e)
                }
            } ?: Log.e(TAG, "Could not post to screensaverContainer")
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
                    }
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

    private fun initializePhotos() {
        lifecycleScope.launch {
            try {
                val selectedAlbums = preferences.getSelectedAlbumIds()
                if (selectedAlbums.isNotEmpty()) {
                    Log.d(TAG, "Found ${selectedAlbums.size} saved albums, loading photos...")

                    withContext(Dispatchers.IO) {
                        try {
                            // First initialize GooglePhotosManager
                            if (googlePhotosManager.initialize()) {
                                // Load photos with error handling
                                val photos = try {
                                    googlePhotosManager.loadPhotos()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error loading photos", e)
                                    null
                                }

                                if (photos != null && photos.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        try {
                                            // Clear existing photos and add new ones
                                            photoManager.clearPhotos()
                                            photoManager.addPhotos(photos)

                                            // Start photo display if we're on the main fragment
                                            if (!isDestroyed &&
                                                navController.currentDestination?.id == R.id.mainFragment) {
                                                photoDisplayManager.startPhotoDisplay()
                                            } else {
                                                Log.d(TAG, "Skipping photo display - activity destroyed or not on main fragment")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error updating UI with photos", e)
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "No photos found in selected albums")
                                }
                            } else {
                                Log.e(TAG, "Failed to initialize GooglePhotosManager")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in photo initialization", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in initializePhotos", e)
            }
        }
    }

    private fun setupTouchListener() {
        Log.d(TAG, "Setting up touch listeners")

        binding.root.setOnClickListener {
            Log.d(TAG, "Root view clicked, current destination: ${navController.currentDestination?.id}")
            if (navController.currentDestination?.id == R.id.mainFragment) {
                Log.d(TAG, "Showing settings button")
                settingsButtonController.show()
            }
        }

        binding.screensaverContainer.setOnClickListener {
            Log.d(TAG, "Screensaver container clicked, current destination: ${navController.currentDestination?.id}")
            if (navController.currentDestination?.id == R.id.mainFragment) {
                Log.d(TAG, "Showing settings button")
                settingsButtonController.show()
            }
        }
    }

    private fun setupSettingsButton() {
        try {
            val settingsFab = binding.settingsButton.settingsFab
            settingsFab.apply {
                visibility = View.VISIBLE
                alpha = 0f
                elevation = 6f
                translationZ = 6f

                setOnClickListener {
                    if (navController.currentDestination?.id == R.id.mainFragment) {
                        if (securityPreferences.isSecurityEnabled) {
                            // Always check security when enabled
                            checkSecurityWithCallback {
                                // After successful authentication
                                authManager.setAuthenticated(true) // Keep authenticated state
                                navigateToSettings()
                            }
                        } else {
                            navigateToSettings()
                        }
                    }
                }
            }

            settingsButtonController = SettingsButtonController(settingsFab)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up settings button", e)
        }
    }

    private fun navigateToSettings() {
        PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
            .edit()
            .putBoolean(PREF_FIRST_LAUNCH, false)
            .apply()

        navController.navigate(R.id.action_mainFragment_to_settingsFragment)
        settingsButtonController.hide()
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

    private fun observeSecurityPreferences() {
        lifecycleScope.launch {
            securityPreferences.securityEnabledFlow.collect { isEnabled ->
                if (isEnabled) {
                    setupSecurityLock()
                } else {
                    // Instead of disabling security lock, we need to:
                    // 1. Reset authentication state
                    authManager.resetAuthenticationState()
                    // 2. Stop lock task mode if it's running
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            stopLockTask()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error stopping lock task", e)
                        }
                    }
                    // 3. Clear secure flags
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    private fun setupSecurityLock() {
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

        // Prevent recent apps preview
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Lock to current task when security is enabled
        if (securityPreferences.isSecurityEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startLockTask()
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

        if (isCharging && startOnCharge) {
            lifecycleScope.launch {
                try {
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
                Log.d(TAG, "Initializing photo display manager")
                val views = PhotoDisplayManager.Views(
                    primaryView = binding.photoPreview,
                    overlayView = binding.photoPreviewOverlay,
                    locationView = binding.locationOverlay,
                    loadingIndicator = binding.loadingIndicator,
                    loadingMessage = binding.loadingMessage,
                    container = binding.screensaverContainer,
                    overlayMessageContainer = binding.overlayMessageContainer,
                    overlayMessageText = binding.overlayMessageText,
                    backgroundLoadingIndicator = binding.backgroundLoadingIndicator
                )

                // Initialize PhotoDisplayManager
                photoDisplayManager.initialize(views, lifecycleScope)

                // Update settings
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)

                // Get the interval from PhotoDisplayManager's constant
                val intervalInt = prefs.getInt(PhotoDisplayManager.PREF_KEY_INTERVAL,
                    PhotoDisplayManager.DEFAULT_INTERVAL_SECONDS)

                photoDisplayManager.updateSettings(
                    isRandomOrder = prefs.getBoolean("random_order", false)
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing photo display manager", e)
                binding.loadingIndicator.visibility = View.VISIBLE
                binding.loadingMessage.apply {
                    text = e.message
                    visibility = View.VISIBLE
                }
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

    override fun onBackPressed() {
        when (navController.currentDestination?.id) {
            R.id.settingsFragment -> {
                navController.navigateUp()
            }
            R.id.mainFragment -> {
                if (securityPreferences.isSecurityEnabled) {
                    checkSecurityWithCallback {
                        handleBackPress()
                    }
                } else {
                    handleBackPress()
                }
            }
            else -> super.onBackPressed()
        }
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
            Log.d(TAG, "Albums saved with count: $photoCount, timestamp: $timestamp")

            if (photoCount > 0) {
                lifecycleScope.launch {
                    try {
                        // Navigate to settings instead of starting slideshow
                        if (navController.currentDestination?.id != R.id.settingsFragment) {
                            Log.d(TAG, "Navigating to settings fragment")
                            navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error navigating to settings", e)
                        showToast("Error navigating to settings")
                    }
                }
            } else {
                Log.w(TAG, "Received albums_saved but count is 0")
            }
            return
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

                        // Initialize PhotoDisplayManager with views
                        val views = PhotoDisplayManager.Views(
                            primaryView = binding.photoPreview,
                            overlayView = binding.photoPreviewOverlay,
                            locationView = binding.locationOverlay,
                            loadingIndicator = binding.loadingIndicator,
                            loadingMessage = binding.loadingMessage,
                            container = binding.screensaverContainer,
                            overlayMessageContainer = binding.overlayMessageContainer,
                            overlayMessageText = binding.overlayMessageText,
                            backgroundLoadingIndicator = binding.backgroundLoadingIndicator
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
            setupFullScreen()
            if (securityPreferences.isSecurityEnabled && !authManager.isAuthenticated()) {
                checkSecurityWithCallback {
                    continueWithAuthenticated()
                }
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

    private fun startLockScreenService() {
        try {
            // Always use startForegroundService on Android O and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, PhotoLockScreenService::class.java))
            } else {
                startService(Intent(this, PhotoLockScreenService::class.java))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting lock screen service", e)
        }
    }

    private fun updateLockScreenService(action: String? = null) {
        try {
            Intent(this, PhotoLockScreenService::class.java).also { intent ->
                action?.let { intent.action = it }
                // Always use startForegroundService on Android O and above
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating lock screen service", e)
        }
    }

    private fun ensureBinding() {
        if (_binding == null) {
            Log.d(TAG, "Binding was null, reinitializing")
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        }
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
                        binding.screensaverContainer?.post {
                            try {
                                binding.screensaverContainer?.let { container ->
                                    if (container is ConstraintLayout) {
                                        val showClock = preferences.isShowClock()
                                        if (showClock) {
                                            widgetManager.reinitializeClockWidget(container)
                                            widgetManager.showWidget(WidgetType.CLOCK)
                                        }

                                        val showWeather = preferences.getBoolean("show_weather", false)
                                        if (showWeather) {
                                            widgetManager.reinitializeWeatherWidget(container)
                                            widgetManager.showWidget(WidgetType.WEATHER)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in navigation post callback", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupNavigation", e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isDestroyed) {
            photoDisplayManager.stopPhotoDisplay()
        }
        if (!isAuthenticating && !isChangingConfigurations) {
            moveTaskToBack(true)  // Force app to background if losing focus
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
        // Always reset auth state when stopping the activity
        authManager.resetAuthenticationState()
    }

    override fun onResume() {
        super.onResume()
        setupFullScreen()

        // Always check security on resume if enabled
        if (securityPreferences.isSecurityEnabled && !authManager.isAuthenticated()) {
            checkSecurityWithCallback {
                // Only continue after successful authentication
                updateLockScreenService("CHECK_KIOSK_MODE")
                if (!isDestroyed && photoManager.getPhotoCount() > 0 &&
                    navController.currentDestination?.id == R.id.mainFragment) {
                    photoDisplayManager.startPhotoDisplay()
                }
            }
        } else {
            // No security needed, continue normally
            updateLockScreenService("CHECK_KIOSK_MODE")
            if (!isDestroyed && photoManager.getPhotoCount() > 0 &&
                navController.currentDestination?.id == R.id.mainFragment) {
                photoDisplayManager.startPhotoDisplay()
            }
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
        photoDisplayManager.handleLowMemory()
    }

    override fun onDestroy() {
        try {
            isDestroyed = true

            // Cancel any ongoing photo operations
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        googlePhotosManager.cleanup()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cleaning up GooglePhotosManager", e)
                    }
                }
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
            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
            super.onDestroy()
        }
    }
}