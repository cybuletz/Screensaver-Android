package com.example.screensaver

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import com.example.screensaver.databinding.ActivityMainBinding
import com.example.screensaver.kiosk.KioskActivity
import com.example.screensaver.kiosk.KioskPolicyManager
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.lock.PhotoLockScreenService
import com.example.screensaver.ui.PhotoDisplayManager
import com.example.screensaver.receivers.ChargingReceiver
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.example.screensaver.ui.SettingsButtonController
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.widget.ClockWidgetManager
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.BatteryManager
import android.net.Uri


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
    lateinit var kioskPolicyManager: KioskPolicyManager

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var clockWidgetManager: ClockWidgetManager

    private var isDestroyed = false
    private var lastBackPressTime: Long = 0
    private val doubleBackPressInterval = 2000L

    private val PREF_FIRST_LAUNCH = "first_launch"

    private val viewLifecycleOwner: LifecycleOwner?
        get() = try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            // Add a check for the Fragment's view lifecycle state
            navHostFragment?.let {
                if (it.view != null && it.viewLifecycleOwnerLiveData.value != null) {
                    it.viewLifecycleOwner
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting viewLifecycleOwner", e)
            null
        }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            checkKioskMode()
        } else {
            disableKioskMode()
            showToast("Device admin access is required for kiosk mode")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KIOSK_PERMISSION_REQUEST_CODE = 1001
        private const val MENU_CLEAR_CACHE = Menu.FIRST + 1
        private const val PHOTO_TRANSITION_DELAY = 500L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableFullScreen()

        try {
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupFullScreen()
            setupSettingsButton()  // Move this before navigation setup
            setupNavigation()
            setupNavigationHandling()
            setupFirstLaunchUI()
            initializePhotoDisplayManager()

            // Only start these if not first launch
            if (!PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(PREF_FIRST_LAUNCH, true)) {
                startLockScreenService()
                initializePhotos()
                photoDisplayManager.updatePhotoSources()
            }

            checkInitialChargingState()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Error initializing app")
            finish()
        }
    }

    private fun setupFirstLaunchUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true)

        Log.d(TAG, "Setting up first launch UI - isFirstLaunch: $isFirstLaunch")

        binding.termsPrivacyContainer.apply {
            visibility = if (isFirstLaunch) View.VISIBLE else View.GONE
        }

        binding.acceptButton.setOnClickListener {
            // Save first launch complete
            prefs.edit()
                .putBoolean(PREF_FIRST_LAUNCH, false)
                .apply()

            // Hide Terms & Privacy
            binding.termsPrivacyContainer.visibility = View.GONE

            // Navigate to settings to set up Google Photos
            navController.navigate(R.id.action_mainFragment_to_settingsFragment)
        }

        // Add URL handlers for terms and privacy
        binding.termsButton.setOnClickListener {
            openUrl("https://photostreamr.cybu.site/terms")
        }
        binding.privacyButton.setOnClickListener {
            openUrl("https://photostreamr.cybu.site/privacy")
        }
    }

    private fun initializeWidgetDefaults() {
        // Set default preferences (widget hidden)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean("show_clock", false)
            .putBoolean("show_date", false)
            .putString("widget_position", ClockWidgetManager.POSITION_BOTTOM_LEFT)
            .apply()

        // Initialize widget with defaults
        clockWidgetManager.updateWidgetVisibility(
            binding.infoContainer,
            binding.clockOverlay,
            binding.dateOverlay
        )
    }

    private fun initializeWidgetFromSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val showClock = prefs.getBoolean("show_clock", false)
        val showDate = prefs.getBoolean("show_date", false)
        val position = prefs.getString("widget_position", ClockWidgetManager.POSITION_BOTTOM_LEFT)

        // Update PhotoDisplayManager settings
        photoDisplayManager.updateSettings(
            showClock = showClock,
            showDate = showDate,
            widgetPosition = position
        )

        // Initialize widget with saved settings
        clockWidgetManager.updateWidgetVisibility(
            binding.infoContainer,
            binding.clockOverlay,
            binding.dateOverlay
        )
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

        // Using a single touch listener for the screensaver container
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
            Log.d(TAG, "Setting up settings button")
            val settingsFab = binding.settingsButton.settingsFab

            // Ensure proper initial state
            settingsFab.apply {
                visibility = View.VISIBLE
                alpha = 0f
                elevation = 6f
                translationZ = 6f

                // Add click listener directly to the FAB
                setOnClickListener {
                    Log.d(TAG, "Settings button clicked")
                    if (navController.currentDestination?.id == R.id.mainFragment) {
                        navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                        settingsButtonController.hide()
                    }
                }
            }

            // Create the controller after setting up the button
            settingsButtonController = SettingsButtonController(settingsFab)

            // Log initial state
            Log.d(TAG, "Settings button initial state - visibility: ${settingsFab.visibility}, alpha: ${settingsFab.alpha}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up settings button", e)
        }
    }

    private fun updatePhotoDisplayState(shouldStart: Boolean) {
        if (isDestroyed || isPhotoTransitionInProgress) return

        lifecycleScope.launch {
            try {
                if (shouldStart) {
                    photoDisplayManager.startPhotoDisplay()
                } else {
                    photoDisplayManager.stopPhotoDisplay()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating photo display state", e)
            }
        }
    }

    private fun setupNavigationHandling() {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.mainFragment -> {
                    binding.screensaverContainer.visibility = View.VISIBLE
                    if (::settingsButtonController.isInitialized) {
                        settingsButtonController.show()
                    }
                }
                R.id.settingsFragment -> {
                    binding.screensaverContainer.visibility = View.GONE
                    if (::settingsButtonController.isInitialized) {
                        settingsButtonController.hide()
                    }
                }
            }
        }
    }

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
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
                    clockView = binding.clockOverlay,
                    dateView = binding.dateOverlay,
                    locationView = binding.locationOverlay,
                    loadingIndicator = binding.loadingIndicator,
                    loadingMessage = binding.loadingMessage,
                    container = binding.screensaverContainer,
                    infoContainer = binding.infoContainer,  // Add this line
                    overlayMessageContainer = binding.overlayMessageContainer,
                    overlayMessageText = binding.overlayMessageText,
                    backgroundLoadingIndicator = binding.backgroundLoadingIndicator
                )

                // Initialize PhotoDisplayManager
                photoDisplayManager.initialize(views, lifecycleScope)

                setupTouchListener()

                // Get saved settings
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                photoDisplayManager.updateSettings(
                    photoInterval = prefs.getInt("photo_interval", 10000).toLong(),
                    showClock = prefs.getBoolean("show_clock", false),
                    showDate = prefs.getBoolean("show_date", false),
                    isRandomOrder = prefs.getBoolean("random_order", false),
                    widgetPosition = prefs.getString("widget_position", ClockWidgetManager.POSITION_BOTTOM_LEFT)
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

    private fun showFirstLaunchUI() {
        binding.termsPrivacyContainer.visibility = View.VISIBLE
        binding.termsButton.setOnClickListener {
            openUrl("https://photostreamr.cybu.site/terms")
        }
        binding.privacyButton.setOnClickListener {
            openUrl("https://photostreamr.cybu.site/privacy")
        }
        binding.acceptButton.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putBoolean(PREF_FIRST_LAUNCH, false)
                .apply()
            binding.termsPrivacyContainer.visibility = View.GONE
        }
    }

    override fun onBackPressed() {
        when (navController.currentDestination?.id) {
            R.id.settingsFragment -> {
                navController.navigateUp()
            }
            R.id.mainFragment -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime > doubleBackPressInterval) {
                    lastBackPressTime = currentTime
                    Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                } else {
                    super.onBackPressed()
                }
            }
            else -> super.onBackPressed()
        }
    }

    private fun ensurePhotoDisplay() {
        if (!isDestroyed && photoManager.getPhotoCount() > 0 &&
            navController.currentDestination?.id == R.id.mainFragment) {
            lifecycleScope.launch {
                try {
                    photoDisplayManager.startPhotoDisplay()
                } catch (e: Exception) {
                    Log.e(TAG, "Error ensuring photo display", e)
                }
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
                        val showClock = prefs.getBoolean("show_clock", true)
                        val showDate = prefs.getBoolean("show_date", true)
                        val intervalInt = prefs.getInt("photo_interval", 10000)

                        // Ensure proper visibility of clock and date views
                        binding.clockOverlay.apply {
                            visibility = if (showClock) View.VISIBLE else View.GONE
                            alpha = 1f
                            bringToFront()
                        }

                        binding.dateOverlay.apply {
                            visibility = if (showDate) View.VISIBLE else View.GONE
                            alpha = 1f
                            bringToFront()
                        }

                        // Initialize PhotoDisplayManager with views
                        val views = PhotoDisplayManager.Views(
                            primaryView = binding.photoPreview,
                            overlayView = binding.photoPreviewOverlay,
                            clockView = binding.clockOverlay,
                            dateView = binding.dateOverlay,
                            locationView = binding.locationOverlay,
                            loadingIndicator = binding.loadingIndicator,
                            loadingMessage = binding.loadingMessage,
                            container = binding.screensaverContainer,
                            infoContainer = binding.infoContainer,
                            overlayMessageContainer = binding.overlayMessageContainer,
                            overlayMessageText = binding.overlayMessageText,
                            backgroundLoadingIndicator = binding.backgroundLoadingIndicator
                        )

                        // Re-initialize PhotoDisplayManager
                        photoDisplayManager.initialize(views, lifecycleScope)

                        // Update PhotoDisplayManager settings
                        photoDisplayManager.updateSettings(
                            showClock = showClock,
                            showDate = showDate,
                            photoInterval = intervalInt.toLong(),
                            isRandomOrder = prefs.getBoolean("random_order", false)
                        )

                        // Let the settings screen handle starting the slideshow
                        Log.d(TAG, "Ready for settings configuration with clock: $showClock, date: $showDate, interval: $intervalInt")

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

    private fun ensureClockAndDateVisibility() {
        binding.clockOverlay.apply {
            visibility = View.VISIBLE
            alpha = 1f
            bringToFront()
        }
        binding.dateOverlay.apply {
            visibility = View.VISIBLE
            alpha = 1f
            bringToFront()
        }
        binding.infoContainer.apply {
            visibility = View.VISIBLE
            alpha = 1f
            bringToFront()
            elevation = 12f
            translationZ = 8f
        }
        Log.d(TAG, "Enforced clock/date visibility - clock: ${binding.clockOverlay.visibility}, date: ${binding.dateOverlay.visibility}")
    }

    private fun updateViewVisibility(view: View, visible: Boolean) {
        if (view.visibility == View.VISIBLE && !visible) {
            view.animate()
                .alpha(0f)
                .withEndAction { view.visibility = View.GONE }
                .duration = 250
        } else if (view.visibility != View.VISIBLE && visible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .duration = 250
        }
    }

    private fun handleNavigationVisibility(destinationId: Int) {
        val isMainScreen = destinationId == R.id.mainFragment
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true)

        binding.screensaverContainer.visibility = if (isMainScreen) View.VISIBLE else View.GONE
        binding.clockOverlay.visibility = if (isMainScreen && !isFirstLaunch) View.VISIBLE else View.GONE
        binding.dateOverlay.visibility = if (isMainScreen && !isFirstLaunch) View.VISIBLE else View.GONE
        binding.termsPrivacyContainer.visibility = if (isFirstLaunch) View.VISIBLE else View.GONE
    }

    private fun checkKioskMode(): Boolean {
        if (isDestroyed) return false

        val isKioskEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("kiosk_mode_enabled", false)

        if (isKioskEnabled) {
            Log.d(TAG, "Kiosk mode is enabled, checking permissions")

            if (!kioskPolicyManager.isDeviceAdmin()) {
                requestDeviceAdmin()
                return false
            }

            if (checkSelfPermission(android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCK_TASK)
                != PackageManager.PERMISSION_GRANTED) {

                if (shouldShowRequestPermissionRationale(
                        android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCK_TASK
                    )) {
                    showPermissionRationale()
                } else {
                    requestKioskPermissions()
                }
                return false
            }

            if (!kioskPolicyManager.isKioskModeAllowed()) {
                showToast("Kiosk mode is not allowed on this device")
                disableKioskMode()
                return false
            }

            startKioskMode()
            return true
        }
        return false
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, kioskPolicyManager.getAdminComponent())
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Device admin access is required for kiosk mode."
            )
        }
        deviceAdminLauncher.launch(intent)
    }

    private fun startKioskMode() {
        lifecycleScope.launch {
            try {
                kioskPolicyManager.setKioskPolicies(true)

                Intent(this@MainActivity, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(this)
                }

                updateLockScreenService("CHECK_KIOSK_MODE")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting kiosk mode", e)
                showToast("Error starting kiosk mode")
                disableKioskMode()
            }
        }
    }

    private fun startLockScreenService() {
        try {
            updateLockScreenService()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting lock screen service", e)
        }
    }

    private fun updateLockScreenService(action: String? = null) {
        Intent(this, PhotoLockScreenService::class.java).also { intent ->
            action?.let { intent.action = it }
            startService(intent)
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        _navController = navHostFragment.navController

        navController.addOnDestinationChangedListener { _, destination, _ ->
            handleNavigationVisibility(destination.id)
        }
    }

    private fun createPhotoDisplayViews(): PhotoDisplayManager.Views {
        return PhotoDisplayManager.Views(
            primaryView = binding.photoPreview,
            overlayView = binding.photoPreviewOverlay,
            clockView = binding.clockOverlay,
            dateView = binding.dateOverlay,
            locationView = binding.locationOverlay,
            loadingIndicator = binding.loadingIndicator,
            loadingMessage = binding.loadingMessage,
            container = binding.screensaverContainer,
            infoContainer = binding.infoContainer,
            overlayMessageContainer = binding.overlayMessageContainer,
            overlayMessageText = binding.overlayMessageText,
            backgroundLoadingIndicator = binding.backgroundLoadingIndicator
        )
    }

    private fun setupMenu() {
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (isDestroyed) return false

                return when (menuItem.itemId) {
                    R.id.action_settings -> {
                        try {
                            navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Error navigating to settings", e)
                            false
                        }
                    }
                    R.id.action_refresh -> {
                        refreshMainFragment()
                        true
                    }
                    else -> false
                }
            }
        }, this, Lifecycle.State.RESUMED)
    }

    private fun refreshMainFragment() {
        try {
            photoDisplayManager.startPhotoDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing display", e)
        }
    }

    private fun showPermissionRationale() {
        if (isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Kiosk mode requires special permissions to function properly.")
            .setPositiveButton("OK") { _, _ -> requestKioskPermissions() }
            .setNegativeButton("Cancel") { _, _ -> disableKioskMode() }
            .show()
    }

    private fun startPhotoDisplay() {
        Log.d(TAG, "Starting photo display from onNewIntent")
        if (isDestroyed || isPhotoTransitionInProgress) {
            Log.d(TAG, "Skipping photo display start - activity destroyed or transition in progress")
            return
        }

        lifecycleScope.launch {
            try {
                // Ensure we have photos before proceeding
                val photoCount = photoManager.getPhotoCount()
                if (photoCount <= 0) {
                    Log.w(TAG, "No photos available to display")
                    return@launch
                }

                // Initialize PhotoDisplayManager if needed
                if (!photoDisplayManager.isInitialized()) {
                    val views = PhotoDisplayManager.Views(
                        primaryView = binding.photoPreview,
                        overlayView = binding.photoPreviewOverlay,
                        clockView = binding.clockOverlay,
                        dateView = binding.dateOverlay,
                        locationView = binding.locationOverlay,
                        loadingIndicator = binding.loadingIndicator,
                        loadingMessage = binding.loadingMessage,
                        container = binding.screensaverContainer,
                        infoContainer = binding.infoContainer,
                        overlayMessageContainer = binding.overlayMessageContainer,
                        overlayMessageText = binding.overlayMessageText,
                        backgroundLoadingIndicator = binding.backgroundLoadingIndicator
                    )

                    photoDisplayManager.initialize(views, lifecycleScope)

                    // Update settings
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    photoDisplayManager.updateSettings(
                        showClock = prefs.getBoolean("show_clock", true),
                        showDate = prefs.getBoolean("show_date", true),
                        photoInterval = prefs.getInt("photo_interval", 10000).toLong(), // Changed to getInt
                        isRandomOrder = prefs.getBoolean("random_order", false)
                    )
                }

                // Ensure cleanup before starting new display
                withContext(Dispatchers.IO) {
                    photoDisplayManager.clearPhotoCache()
                    delay(100) // Small delay to ensure cleanup is complete
                }

                // Ensure clock and date visibility
                ensureClockAndDateVisibility()

                // Start the photo display
                photoDisplayManager.startPhotoDisplay()
                Log.d(TAG, "Photo display started with $photoCount photos")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting photo display", e)
                showToast("Error starting photo display")
            }
        }
    }


    private fun requestKioskPermissions() {
        requestPermissions(
            arrayOf(
                android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCK_TASK
            ),
            KIOSK_PERMISSION_REQUEST_CODE
        )
    }

    private fun disableKioskMode() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean("kiosk_mode_enabled", false)
            .apply()
    }

    override fun onPause() {
        super.onPause()
        if (!isDestroyed) {
            photoDisplayManager.stopPhotoDisplay()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == KIOSK_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkKioskMode()
            } else {
                disableKioskMode()
                showToast("Kiosk mode requires additional permissions")
            }
        }
    }

    private fun handlePhotosUpdate() {
        if (isPhotoTransitionInProgress) {
            Log.d(TAG, "Photo update already in progress, skipping")
            return
        }

        isPhotoTransitionInProgress = true
        lifecycleScope.launch {
            try {
                // Stop current display
                photoDisplayManager.stopPhotoDisplay()

                // Allow time for cleanup
                delay(PHOTO_TRANSITION_DELAY)

                // Start new display
                startPhotoDisplay()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling photo update", e)
                showToast("Error updating photos")
            } finally {
                isPhotoTransitionInProgress = false
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

    override fun onResume() {
        super.onResume()
        setupFullScreen()
        if (checkKioskMode()) {
            return
        }

        updateLockScreenService("CHECK_KIOSK_MODE")

        // Check if we should start photo display
        if (!isDestroyed && photoManager.getPhotoCount() > 0 &&
            navController.currentDestination?.id == R.id.mainFragment) {
            photoDisplayManager.startPhotoDisplay()
        }
    }

    // Case 1: When user logs out
    private fun handleLogout() {
        photoDisplayManager.cleanup(clearCache = true) // Clear cache on logout
        // ... other logout logic
    }

    // Case 2: When user changes photo sources
    private fun handlePhotoSourceChange() {
        photoDisplayManager.clearPhotoCache() // Clear only cache
        initializePhotos() // Reinitialize with new source
    }

    // Case 3: Add a menu option for users to manually clear cache
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

    // Case 4: Handle cache cleanup on low memory
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

            // Existing cleanup code
            if (PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("kiosk_mode_enabled", false)) {
                startKioskMode()
            }

            // Safely handle viewLifecycleOwner cleanup
            viewLifecycleOwner?.let { lifecycleOwner ->
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    withContext(NonCancellable) {
                        photoDisplayManager.cleanup()
                    }
                }
            } ?: run {
                // Fallback cleanup if viewLifecycleOwner is not available
                lifecycleScope.launch(Dispatchers.Main) {
                    withContext(NonCancellable) {
                        photoDisplayManager.cleanup()
                    }
                }
            }

            if (::settingsButtonController.isInitialized) {
                settingsButtonController.cleanup()
            }

            _binding = null
            _navController = null

            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
            super.onDestroy()
        }
    }
}