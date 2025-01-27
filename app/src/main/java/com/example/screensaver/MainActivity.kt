package com.example.screensaver

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.view.View
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private var _navController: NavController? = null
    private val navController get() = _navController!!

    @Inject
    lateinit var photoManager: LockScreenPhotoManager

    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    private var isDestroyed = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (checkKioskMode()) {
                return
            }

            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupNavigation()
            setupMenu()
            initializePhotoDisplayManager() // New method call instead of setupPhotoDisplay
            startLockScreenService()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Error initializing app")
            finish()
        }
    }

    private fun initializePhotoDisplayManager() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Initializing photo display manager")
                val views = PhotoDisplayManager.Views(
                    primaryView = binding.primaryImageView,
                    overlayView = binding.overlayImageView,
                    clockView = binding.clockView,
                    dateView = binding.dateView,
                    locationView = binding.locationView,
                    loadingIndicator = binding.loadingIndicator,
                    loadingMessage = binding.loadingMessage,  // Add the loadingMessage
                    container = binding.photoContainer
                )

                // Initialize PhotoDisplayManager
                photoDisplayManager.initialize(views, lifecycleScope)

                // Rest of the method remains the same...
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing photo display manager", e)
                binding.loadingIndicator.visibility = View.VISIBLE  // Remove ?
                binding.loadingMessage.apply {  // Remove ?
                    text = e.message
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this) {
            val currentDestination = navController.currentDestination
            when (currentDestination?.id) {
                R.id.nav_photos -> {
                    val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                    val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()

                    if (currentFragment is MainFragment && currentFragment.onBackPressed()) {
                        return@addCallback
                    }
                    if (isTaskRoot) {
                        finish()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
                else -> {
                    navController.navigate(R.id.nav_photos)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("photos_ready", false) == true) {
            startPhotoDisplay()  // You'll need to implement this method
        }
    }

    private fun setupPhotoDisplay() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Setting up photo display")
                val views = PhotoDisplayManager.Views(
                    primaryView = binding.primaryImageView,
                    overlayView = binding.overlayImageView,
                    clockView = binding.clockView,
                    dateView = binding.dateView,
                    locationView = binding.locationView,
                    loadingIndicator = binding.loadingIndicator,
                    loadingMessage = binding.loadingMessage,  // Add this line
                    container = binding.photoContainer
                )

                // Initialize with loading state
                binding.loadingIndicator.visibility = View.VISIBLE  // Changed from isVisible

                // Wait for photo manager to be ready
                withContext(Dispatchers.IO) {
                    var attempts = 0
                    while (attempts < 6 && photoManager.getPhotoCount() == 0) {
                        delay(500)
                        attempts++
                    }
                }

                photoDisplayManager.initialize(views, lifecycleScope)
                Log.d(TAG, "PhotoDisplayManager initialized")

                val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                photoDisplayManager.updateSettings(
                    showClock = prefs.getBoolean("show_clock", true),
                    showDate = prefs.getBoolean("show_date", true),
                    photoInterval = prefs.getString("photo_interval", "10000")?.toLongOrNull() ?: 10000L,
                    isRandomOrder = prefs.getBoolean("random_order", false)
                )

                // Start display only if we have photos
                if (photoManager.getPhotoCount() > 0) {
                    photoDisplayManager.startPhotoDisplay()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in setupPhotoDisplay", e)
                binding.loadingIndicator.visibility = View.GONE
            }
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
        val isMainScreen = destinationId == R.id.nav_photos
        Log.d(TAG, "Navigation destination: $destinationId, isMainScreen: $isMainScreen")

        listOf(binding.photoContainer, binding.clockView, binding.dateView).forEach { view ->
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
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: throw IllegalStateException("Nav host fragment not found")

        _navController = navHostFragment.navController

        setupActionBarWithNavController(navController)
        binding.bottomNavigation.setupWithNavController(navController)

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
                            navController.navigate(R.id.nav_settings)
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
                        primaryView = binding.primaryImageView,
                        overlayView = binding.overlayImageView,
                        clockView = binding.clockView,
                        dateView = binding.dateView,
                        locationView = binding.locationView,
                        loadingIndicator = binding.loadingIndicator,
                        loadingMessage = binding.loadingMessage,  // Add this line
                        container = binding.photoContainer
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

                    // Start the photo display
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

            _binding = null
            _navController = null

            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
            super.onDestroy()
        }
    }
}