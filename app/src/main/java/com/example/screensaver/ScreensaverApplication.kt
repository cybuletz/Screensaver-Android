package com.example.screensaver

import android.app.Application
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import com.example.screensaver.utils.AppPreferences
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Custom Application class for initialization and global state management
 */
@HiltAndroidApp
class ScreensaverApplication : Application() {

    @Inject
    lateinit var preferences: AppPreferences

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val VERSION_NAME = BuildConfig.VERSION_NAME
        private const val VERSION_CODE = BuildConfig.VERSION_CODE
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            enableStrictMode()
        }

        // Initialize Firebase services
        initializeFirebase()

        // Initialize theme
        initializeTheme()

        // Initialize work manager for background tasks
        initializeWorkManager()

        // Log application start
        logApplicationStart()
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
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

    private fun initializeFirebase() {
        // Initialize Firebase Analytics
        FirebaseAnalytics.getInstance(this).apply {
            setAnalyticsCollectionEnabled(!BuildConfig.DEBUG)
            setUserProperty("app_version", VERSION_NAME)
        }

        // Initialize Firebase Crashlytics
        FirebaseCrashlytics.getInstance().apply {
            setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
            setCustomKey("version_code", VERSION_CODE)
            setCustomKey("version_name", VERSION_NAME)
        }
    }

    private fun initializeTheme() {
        applicationScope.launch {
            val isDarkMode = preferences.getDarkModeFlow().first()
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }
    }

    private fun initializeWorkManager() {
        applicationScope.launch {
            try {
                // Setup periodic work for cache cleanup
                setupCacheCleanupWork()

                // Setup periodic work for photo refresh
                setupPhotoRefreshWork()
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize WorkManager")
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private fun setupCacheCleanupWork() {
        // Implementation for cache cleanup periodic work
        // This will run daily to clean up old cached photos
    }

    private fun setupPhotoRefreshWork() {
        // Implementation for photo refresh periodic work
        // This will run periodically to refresh photo lists
    }

    private fun logApplicationStart() {
        Firebase.analytics.logEvent("app_start") {
            param("version_name", VERSION_NAME)
            param("version_code", VERSION_CODE.toString())
            param("build_type", BuildConfig.BUILD_TYPE)
            param("debug_mode", BuildConfig.DEBUG.toString())
        }

        Timber.i("Application started: v$VERSION_NAME ($VERSION_CODE)")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Clear non-essential caches
        Timber.w("Low memory condition detected")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                // Clear all caches
                Timber.w("Critical memory condition detected")
            }
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_MODERATE -> {
                // Clear non-essential caches
                Timber.w("Moderate memory condition detected")
            }
        }
    }
}