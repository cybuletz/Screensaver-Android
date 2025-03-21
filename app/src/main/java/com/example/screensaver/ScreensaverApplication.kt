package com.example.screensaver

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
import com.example.screensaver.music.SpotifyManager
import com.example.screensaver.music.SpotifyPreferences
import java.io.File
import kotlinx.coroutines.NonCancellable

@HiltAndroidApp
class ScreensaverApplication : Application() {

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var photoSourceState: PhotoSourceState

    @Inject
    lateinit var appDataManager: AppDataManager

    @Inject
    lateinit var spotifyManager: SpotifyManager

    @Inject
    lateinit var spotifyPreferences: SpotifyPreferences

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
        private const val PREF_NEEDS_FIRST_RESTART = "needs_first_restart"
        private const val PREF_GOOGLE_PHOTOS_INITIALIZED = "google_photos_initialized"
    }

    private var activityCounter = 0

    override fun onCreate() {
        super.onCreate()

        // Check for first run before any other initialization
        if (shouldInitializeGooglePhotos()) {
            Timber.d("First run detected initializing Google Photos")
            initializeGooglePhotosAndRestart()
        } else {
            initializeApp()
            registerActivityLifecycleCallbacks(appLifecycleCallbacks)
        }
    }

    private fun shouldInitializeGooglePhotos(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val lastInstalledVersion = prefs.getInt("last_installed_version", -1)
        val googlePhotosInitialized = prefs.getBoolean(PREF_GOOGLE_PHOTOS_INITIALIZED, false)

        return lastInstalledVersion == -1 || isAppDataCleared() || !googlePhotosInitialized
    }

    private fun initializeGooglePhotosAndRestart() {
        try {
            Timber.d("Starting Google Photos initialization")

            // Force release and reinitialize Google Photos connection
            contentResolver.call(
                android.provider.MediaStore.AUTHORITY_URI,
                "external_reset",
                null,
                null
            )

            // Small delay to allow system to process
            Thread.sleep(100) // Using Thread.sleep since we're before any coroutines setup

            // Mark as initialized and update version
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.edit()
                .putBoolean(PREF_GOOGLE_PHOTOS_INITIALIZED, true)
                .putInt("last_installed_version", VERSION_CODE)
                .apply()

            Timber.d("Google Photos initialized, triggering immediate restart")

            // Immediate restart
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            startActivity(intent)
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Google Photos")
            FirebaseCrashlytics.getInstance().recordException(e)
            // Continue with normal initialization as fallback
            initializeApp()
            registerActivityLifecycleCallbacks(appLifecycleCallbacks)
        }
    }

    private fun checkFirstInstall() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val lastInstalledVersion = prefs.getInt("last_installed_version", -1)

        if (lastInstalledVersion == -1 || isAppDataCleared()) {
            Timber.d("First install detected")
            prefs.edit()
                .putInt("last_installed_version", VERSION_CODE)
                .apply()
        }
    }

    private val appLifecycleCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            activityCounter++
        }

        override fun onActivityStopped(activity: Activity) {
            activityCounter--
            if (activityCounter == 0) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ScreensaverApplication)

                // Only check once per background transition
                if (prefs.contains(PREF_NEEDS_FIRST_RESTART)) {
                    Timber.d("First background detected, checking if restart needed")
                    val needsRestart = prefs.getBoolean(PREF_NEEDS_FIRST_RESTART, false)
                    if (needsRestart) {
                        Timber.d("Performing first-time restart")
                        // Remove the flag before restarting
                        prefs.edit().remove(PREF_NEEDS_FIRST_RESTART).apply()
                        handleGracefulRestart()
                    }
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun handleGracefulRestart() {
        applicationScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: Exception) {
                Timber.e(e, "Error during restart")
            }
        }
    }

    private fun initializeApp() {

        // Add checkFirstInstall() early in the initialization sequence
        checkFirstInstall()

        initializeFirebase()
        initializeTheme()
        initializeWorkManager()
        initializePhotoSourceState()
        initializeAppData()
        initializeSpotify()
        logApplicationStart()
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