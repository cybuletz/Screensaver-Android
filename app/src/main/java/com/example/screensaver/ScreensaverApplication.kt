package com.example.screensaver

import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.work.CacheCleanupWorker
import com.example.screensaver.work.PhotoRefreshWorker
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.example.screensaver.data.AppDataManager
import androidx.preference.PreferenceManager
import com.example.screensaver.music.RadioManager
import com.example.screensaver.music.RadioPreferences
import com.example.screensaver.music.SpotifyManager
import com.example.screensaver.music.SpotifyPreferences
import java.io.File
import kotlinx.coroutines.NonCancellable


/**
 * Custom Application class for initialization and global state management
 */
@HiltAndroidApp
class ScreensaverApplication : Application() {

    @Inject lateinit var preferences: AppPreferences

    @Inject lateinit var photoSourceState: PhotoSourceState

    @Inject lateinit var appDataManager: AppDataManager

    @Inject lateinit var spotifyManager: SpotifyManager

    @Inject lateinit var spotifyPreferences: SpotifyPreferences

    @Inject lateinit var radioManager: RadioManager

    @Inject lateinit var radioPreferences: RadioPreferences

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    companion object {
        private const val VERSION_NAME = BuildConfig.VERSION_NAME
        private const val VERSION_CODE = BuildConfig.VERSION_CODE
        private const val CACHE_CLEANUP_WORK = "cache_cleanup_work"
        private const val PHOTO_REFRESH_WORK = "photo_refresh_work"
        private const val CACHE_CLEANUP_INTERVAL_HOURS = 24L
        private const val PHOTO_REFRESH_INTERVAL_HOURS = 6L
        private const val PREVIEW_MODE_TAG = "preview_mode"
    }

    override fun onCreate() {
        super.onCreate()
        checkFirstInstall()
        initializeApp()
    }

    private fun initializeApp() {
        if (BuildConfig.DEBUG) {
            initializeDebugMode()
        }

        initializeFirebase()
        initializeTheme()
        initializeWorkManager()
        initializePhotoSourceState()
        initializeAppData()
        initializeSpotify()
        initializeRadio()
        logApplicationStart()
    }

    private fun checkFirstInstall() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val lastInstalledVersion = prefs.getInt("last_installed_version", -1)
        val currentVersion = BuildConfig.VERSION_CODE

        if (lastInstalledVersion == -1 || isAppDataCleared()) {
            // This is a fresh install or app data was cleared
            applicationScope.launch {
                try {
                    appDataManager.performFullReset()
                    prefs.edit()
                        .putInt("last_installed_version", currentVersion)
                        .putLong("first_install_time", System.currentTimeMillis())
                        .apply()
                } catch (e: Exception) {
                    Timber.e(e, "Error during first install cleanup")
                }
            }
        }
    }

    private fun isAppDataCleared(): Boolean {
        // Check if essential app directories are missing
        val essential = arrayOf(
            "shared_prefs",
            "databases",
            "cache"
        )

        return essential.any { dir ->
            !File(applicationInfo.dataDir, dir).exists()
        }
    }

    private fun initializeAppData() {
        applicationScope.launch {
            try {
                val currentState = appDataManager.getCurrentState()
                // Track in analytics
                firebaseAnalytics.setUserProperty(
                    "app_data_ready",
                    currentState.isScreensaverReady.toString()
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize AppDataManager")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun initializePhotoSourceState() {
        applicationScope.launch {
            try {
                // Reset preview mode on app start
                photoSourceState.recordPreviewEnded()

                // Log the screensaver readiness state
                Timber.d("Screensaver ready state: ${photoSourceState.isScreensaverReady()}")

                // Track in analytics
                firebaseAnalytics.setUserProperty(
                    "screensaver_ready",
                    photoSourceState.isScreensaverReady().toString()
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize PhotoSourceState")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun initializeDebugMode() {
        Timber.plant(Timber.DebugTree())
        enableStrictMode()
    }

    private fun enableStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .permitDiskReads()
                    .build()
            )

            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    private fun initializeFirebase() {
        firebaseAnalytics = FirebaseAnalytics.getInstance(this).apply {
            setAnalyticsCollectionEnabled(!BuildConfig.DEBUG)
            setUserProperty("app_version", VERSION_NAME)
        }

        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
            setCustomKey("version_code", VERSION_CODE)
            setCustomKey("version_name", VERSION_NAME)
        }
    }

    private fun initializeTheme() {
        applicationScope.launch {
            try {
                val isDarkMode = preferences.getDarkMode()
                AppCompatDelegate.setDefaultNightMode(
                    if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize theme")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun initializeWorkManager() {
        applicationScope.launch {
            try {
                setupCacheCleanupWork()
                setupPhotoRefreshWork()
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize WorkManager")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun initializeSpotify() {
        applicationScope.launch {
            try {
                spotifyManager.initialize()
                // Track in analytics
                firebaseAnalytics.setUserProperty(
                    "spotify_enabled",
                    spotifyPreferences.isEnabled().toString()
                )
                firebaseAnalytics.setUserProperty(
                    "spotify_connected",
                    spotifyPreferences.wasConnected().toString()
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize Spotify")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun initializeRadio() {
        applicationScope.launch {
            try {
                // Initialize radio state
                if (radioPreferences.isEnabled()) {
                    radioManager.initializeState()
                }
                // Track in analytics
                firebaseAnalytics.setUserProperty(
                    "radio_enabled",
                    radioPreferences.isEnabled().toString()
                )
                firebaseAnalytics.setUserProperty(
                    "radio_was_playing",
                    radioPreferences.wasPlaying().toString()
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize Radio")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun setupCacheCleanupWork() {
        val cacheCleanupRequest = PeriodicWorkRequestBuilder<CacheCleanupWorker>(
            CACHE_CLEANUP_INTERVAL_HOURS, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CACHE_CLEANUP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            cacheCleanupRequest
        )
    }

    private fun setupPhotoRefreshWork() {
        val photoRefreshRequest = PeriodicWorkRequestBuilder<PhotoRefreshWorker>(
            PHOTO_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PHOTO_REFRESH_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            photoRefreshRequest
        )
    }

    private fun logApplicationStart() {
        val currentState = appDataManager.getCurrentState()
        val params = Bundle().apply {
            putString("version_name", VERSION_NAME)
            putInt("version_code", VERSION_CODE)
            putString("build_type", BuildConfig.BUILD_TYPE)
            putBoolean("debug_mode", BuildConfig.DEBUG)
            putBoolean("screensaver_ready", currentState.isScreensaverReady)
            // Add new analytics data
            putBoolean("app_data_ready", currentState.isScreensaverReady)
        }

        firebaseAnalytics.logEvent("app_start", params)
        Timber.i("Application started: v$VERSION_NAME ($VERSION_CODE)")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Timber.w("Low memory condition detected")
        clearNonEssentialCaches()
        // Update to use appDataManager
        if (appDataManager.getCurrentState().isScreensaverReady) {
            photoSourceState.recordPreviewEnded()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                Timber.w("Critical memory condition detected")
                clearAllCaches()
                // End preview mode if active
                if (photoSourceState.isInPreviewMode) {
                    photoSourceState.recordPreviewEnded()
                }
            }
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_MODERATE -> {
                Timber.w("Moderate memory condition detected")
                clearNonEssentialCaches()
            }
        }
    }

    private fun clearAllCaches() {
        // Implementation for clearing all caches
    }

    private fun clearNonEssentialCaches() {
        // Implementation for clearing non-essential caches
    }

    override fun onTerminate() {
        applicationScope.launch(NonCancellable) {
            try {
                spotifyManager.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Error disconnecting Spotify")
            }
        }
        super.onTerminate()
    }
}