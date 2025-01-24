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

/**
 * Custom Application class for initialization and global state management
 */
@HiltAndroidApp
class ScreensaverApplication : Application() {

    @Inject
    lateinit var preferences: AppPreferences

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    companion object {
        private const val VERSION_NAME = BuildConfig.VERSION_NAME
        private const val VERSION_CODE = BuildConfig.VERSION_CODE
        private const val CACHE_CLEANUP_WORK = "cache_cleanup_work"
        private const val PHOTO_REFRESH_WORK = "photo_refresh_work"
        private const val CACHE_CLEANUP_INTERVAL_HOURS = 24L
        private const val PHOTO_REFRESH_INTERVAL_HOURS = 6L
    }

    override fun onCreate() {
        super.onCreate()
        initializeApp()  // This was missing from the original implementation
    }

    private fun initializeApp() {
        if (BuildConfig.DEBUG) {
            initializeDebugMode()
        }

        initializeFirebase()
        initializeTheme()
        initializeWorkManager()
        logApplicationStart()
    }

    private fun initializeDebugMode() {
        Timber.plant(Timber.DebugTree())
        enableStrictMode()
    }

    private fun enableStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()               // This is more comprehensive
                    .penaltyLog()
                    .permitDiskReads()         // Allow disk reads for preferences
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
        val params = Bundle().apply {
            putString("version_name", VERSION_NAME)
            putInt("version_code", VERSION_CODE)
            putString("build_type", BuildConfig.BUILD_TYPE)
            putBoolean("debug_mode", BuildConfig.DEBUG)
        }

        firebaseAnalytics.logEvent("app_start", params)
        Timber.i("Application started: v$VERSION_NAME ($VERSION_CODE)")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Timber.w("Low memory condition detected")
        clearNonEssentialCaches()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                Timber.w("Critical memory condition detected")
                clearAllCaches()
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
}