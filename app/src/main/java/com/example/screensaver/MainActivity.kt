package com.example.screensaver

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.example.screensaver.databinding.ActivityMainBinding
import com.example.screensaver.kiosk.KioskActivity
import com.example.screensaver.kiosk.KioskPolicyManager
import com.example.screensaver.lock.PhotoLockScreenService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if kiosk mode is enabled before setting up the normal UI
        if (checkKioskMode()) {
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        setupMenu()
        startLockScreenService()
    }

    private fun checkKioskMode(): Boolean {
        val isKioskEnabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("kiosk_mode_enabled", false)

        if (isKioskEnabled) {
            Log.d(TAG, "Kiosk mode is enabled, starting KioskActivity")
            startKioskMode()
            finish()
            return true
        }
        return false
    }

    private fun startKioskMode() {
        // Set up kiosk policies
        kioskPolicyManager.setKioskPolicies(true)

        // Start the kiosk activity
        Intent(this, KioskActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(this)
        }

        // Update the service
        Intent(this, PhotoLockScreenService::class.java).also { intent ->
            intent.action = "CHECK_KIOSK_MODE"
            startService(intent)
        }
    }

    private fun startLockScreenService() {
        Intent(this, PhotoLockScreenService::class.java).also { intent ->
            startService(intent)
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Setup BottomNavigationView with NavController
        binding.bottomNavigation.setupWithNavController(navController)
    }

    private fun setupMenu() {
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_settings -> {
                        navController.navigate(R.id.action_mainFragment_to_settingsFragment)
                        true
                    }
                    R.id.action_refresh -> {
                        // Find current fragment and refresh if it's MainFragment
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                            ?.childFragmentManager
                            ?.fragments
                            ?.firstOrNull()
                        if (currentFragment is MainFragment) {
                            currentFragment.refreshWebView()
                        }
                        true
                    }
                    else -> false
                }
            }
        }, this, Lifecycle.State.RESUMED)
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onResume() {
        super.onResume()
        // Check kiosk mode status when app comes to foreground
        if (checkKioskMode()) {
            return
        }

        // Check if lock screen service needs to be updated
        Intent(this, PhotoLockScreenService::class.java).also { intent ->
            intent.action = "CHECK_KIOSK_MODE"
            startService(intent)
        }
    }

    override fun onDestroy() {
        // If this is being destroyed while kiosk mode is enabled,
        // make sure we maintain the kiosk state
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("kiosk_mode_enabled", false)) {
            startKioskMode()
        }
        super.onDestroy()
    }
}