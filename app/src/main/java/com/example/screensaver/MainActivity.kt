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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.example.screensaver.ui.SettingsButtonController



@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private var _navController: NavController? = null
    private val navController get() = _navController!!
    private lateinit var settingsButtonController: SettingsButtonController

    @Inject
    lateinit var photoManager: LockScreenPhotoManager

    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    private var isDestroyed = false
    private var lastBackPressTime: Long = 0
    private val doubleBackPressInterval = 2000L

    private val viewLifecycleOwner: LifecycleOwner?
        get() = try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            navHostFragment?.viewLifecycleOwner
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
            if (checkKioskMode()) {
                return
            }

            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupFullScreen()
            setupNavigation()
            setupSettingsButton()
            initializeClockAndDate()
            setupTouchListener()
            initializePhotoDisplayManager()
            startLockScreenService()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Error initializing app")
            finish()
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
            Log.d(TAG, "Setting up settings button")
            val settingsFab = binding.settingsButton.settingsFab

            // Ensure proper initial state
            settingsFab.apply {
                visibility = View.VISIBLE
                alpha = 0f
                elevation = 6f
                translationZ = 6f

                // Add click listener
                setOnClickListener {
                    Log.d(TAG, "Settings button clicked")
                    if (navController.currentDestination?.id == R.id.mainFragment) {
                        navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                        settingsButtonController.hide()
                    }
                }
            }

            settingsButtonController = SettingsButtonController(settingsFab)

            // Log initial state
            Log.d(TAG, "Settings button initial state - visibility: ${settingsFab.visibility}, alpha: ${settingsFab.alpha}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up settings button", e)
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
                    overlayMessageContainer = binding.overlayMessageContainer,
                    overlayMessageText = binding.overlayMessageText
                )

                // Initialize PhotoDisplayManager
                photoDisplayManager.initialize(views, lifecycleScope)

                // Update settings
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                photoDisplayManager.updateSettings(
                    showClock = prefs.getBoolean("show_clock", true),
                    showDate = prefs.getBoolean("show_date", true),
                    photoInterval = prefs.getString("photo_interval", "10000")?.toLongOrNull() ?: 10000L,
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

    private fun initializeClockAndDate() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val showClock = prefs.getBoolean("lock_screen_clock", true)
        val showDate = prefs.getBoolean("lock_screen_date", true)

        binding.clockOverlay.visibility = if (showClock) View.VISIBLE else View.GONE
        binding.dateOverlay.visibility = if (showDate) View.VISIBLE else View.GONE

        // Update PhotoDisplayManager settings
        photoDisplayManager.updateSettings(
            showClock = showClock,
            showDate = showDate
        )
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("photos_ready", false) == true) {
            startPhotoDisplay()  // You'll need to implement this method
        }
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
        Log.d(TAG, "Navigation destination: $destinationId, isMainScreen: $isMainScreen")

        listOf(
            binding.screensaverContainer,
            binding.clockOverlay,
            binding.dateOverlay
        ).forEach { view ->
            updateViewVisibility(view, isMainScreen)
        }
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
        lifecycleScope.launch {
            try {
                if (photoManager.getPhotoCount() > 0) {
                    val views = PhotoDisplayManager.Views(
                        primaryView = binding.photoPreview,
                        overlayView = binding.photoPreviewOverlay,
                        clockView = binding.clockOverlay,
                        dateView = binding.dateOverlay,
                        locationView = binding.locationOverlay,
                        loadingIndicator = binding.loadingIndicator,
                        loadingMessage = binding.loadingMessage,
                        container = binding.screensaverContainer,
                        overlayMessageContainer = binding.overlayMessageContainer,
                        overlayMessageText = binding.overlayMessageText
                    )

                    // Initialize if needed
                    if (!photoDisplayManager.isInitialized()) {
                        photoDisplayManager.initialize(views, lifecycleScope)

                        // Update settings
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                        photoDisplayManager.updateSettings(
                            showClock = prefs.getBoolean("show_clock", true),
                            showDate = prefs.getBoolean("show_date", true),
                            photoInterval = prefs.getString("photo_interval", "10000")?.toLongOrNull() ?: 10000L,
                            isRandomOrder = prefs.getBoolean("random_order", false)
                        )
                    }

                    photoDisplayManager.startPhotoDisplay()
                    Log.d(TAG, "Photo display started with ${photoManager.getPhotoCount()} photos")
                } else {
                    Log.w(TAG, "No photos available to display")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting photo display", e)
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

        if (!isDestroyed && photoManager.getPhotoCount() > 0) {
            photoDisplayManager.startPhotoDisplay()
        }
    }

    override fun onDestroy() {
        try {
            isDestroyed = true
            if (PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("kiosk_mode_enabled", false)) {
                startKioskMode()
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

            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
            super.onDestroy()
        }
    }
}