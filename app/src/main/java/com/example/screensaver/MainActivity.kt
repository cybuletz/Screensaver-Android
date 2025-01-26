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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.example.screensaver.databinding.ActivityMainBinding
import com.example.screensaver.kiosk.KioskActivity
import com.example.screensaver.kiosk.KioskPolicyManager
import com.example.screensaver.lock.PhotoLockScreenService
import com.example.screensaver.ui.PhotoDisplayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.view.View
import com.example.screensaver.models.LoadingState
import kotlinx.coroutines.flow.collect
import com.example.screensaver.lock.PhotoManager
import com.example.screensaver.lock.PhotoManager.LoadingState as PhotoManagerState

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private var _navController: NavController? = null
    private val navController get() = _navController!!

    @Inject
    lateinit var photoManager: PhotoManager

    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    private var isDestroyed = false

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
            setupPhotoDisplay()
            startLockScreenService()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            showToast("Error initializing app")
            finish()
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
                    container = binding.photoContainer
                )

                photoDisplayManager.initialize(views, lifecycleScope)
                Log.d(TAG, "PhotoDisplayManager initialized")

                // Load preferences and update settings directly
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                photoDisplayManager.updateSettings(
                    showClock = prefs.getBoolean("show_clock", true),
                    showDate = prefs.getBoolean("show_date", true),
                    photoInterval = prefs.getString("photo_interval", "10000")?.toLongOrNull() ?: 10000L
                )

                // Single collector for photo loading state
                photoManager.loadingState
                    .collect { state ->
                        Log.d(TAG, "Photo manager state: $state, photo count: ${photoManager.getPhotoCount()}")
                        when (state) {
                            PhotoManagerState.SUCCESS -> {
                                binding.loadingIndicator.visibility = View.GONE
                                val photoCount = photoManager.getPhotoCount()
                                Log.d(TAG, "Success state with $photoCount photos")
                                if (photoCount > 0) {
                                    photoDisplayManager.startPhotoDisplay()
                                }
                            }
                            PhotoManagerState.LOADING -> {
                                binding.loadingIndicator.visibility = View.VISIBLE
                            }
                            PhotoManagerState.ERROR -> {
                                binding.loadingIndicator.visibility = View.GONE
                            }
                            PhotoManagerState.IDLE -> {
                                binding.loadingIndicator.visibility = View.GONE
                                if (photoManager.getPhotoCount() == 0) {
                                    Log.d(TAG, "No photos in IDLE state, attempting to load")
                                    photoManager.loadPhotos()
                                }
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in setupPhotoDisplay", e)
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

        updateViewVisibility(binding.photoContainer, isMainScreen)
        updateViewVisibility(binding.clockView, isMainScreen)
        updateViewVisibility(binding.dateView, isMainScreen)
    }

    private fun checkKioskMode(): Boolean {
        if (isDestroyed) return false

        val isKioskEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("kiosk_mode_enabled", false)

        if (isKioskEnabled) {
            Log.d(TAG, "Kiosk mode is enabled, checking permissions")

            // Check for device admin first
            if (!kioskPolicyManager.isDeviceAdmin()) {
                requestDeviceAdmin()
                return false
            }

            // Check for lock task permission
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
        binding.bottomNavigation.setupWithNavController(navController)

        // Add navigation listener
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
            photoDisplayManager.startPhotoDisplay() // Update to use PhotoDisplayManager instead
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

    override fun onBackPressed() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()

        if (currentFragment is MainFragment) {
            // Handle back press in main fragment if needed
            return
        }
        super.onBackPressed()
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
        return _navController?.navigateUp() ?: false || super.onSupportNavigateUp()
    }

    override fun onResume() {
        super.onResume()
        if (checkKioskMode()) {
            return
        }

        updateLockScreenService("CHECK_KIOSK_MODE")

        // Only start display if we have photos
        if (!isDestroyed && photoManager.getPhotoCount() > 0) {
            photoDisplayManager.startPhotoDisplay()
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("kiosk_mode_enabled", false)) {
            startKioskMode()
        }
        photoDisplayManager.cleanup() // Add cleanup for PhotoDisplayManager
        _binding = null
        _navController = null
        super.onDestroy()
    }
}