package com.photostreamr.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photostreamr.data.AppDataManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

// Remove @AndroidEntryPoint since we'll use EntryPoints
class BootReceiver : BroadcastReceiver() {
    // Define an EntryPoint interface
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootReceiverEntryPoint {
        fun appDataManager(): AppDataManager
    }

    // Get dependencies through EntryPointAccessors
    private fun getAppDataManager(context: Context): AppDataManager {
        return EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootReceiverEntryPoint::class.java
        ).appDataManager()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed, initializing app state")
            // Get the dependency when needed
            val appDataManager = getAppDataManager(context)
            initializeAppState(appDataManager)
        }
    }

    private fun initializeAppState(appDataManager: AppDataManager) {
        try {
            val currentState = appDataManager.getCurrentState()

            // Verify state integrity
            currentState.validate()

            // Reset transient states
            appDataManager.updateState { state ->
                state.copy(
                    isInPreviewMode = false,
                    lastPreviewTimestamp = 0,
                    previewCount = 0
                )
            }

            // Check if photostreamr was active
            if (currentState.isScreensaverReady) {
                Timber.d("Restoring photostreamr state")
                restoreScreensaverState(appDataManager)
            }

            Timber.i("App state initialized successfully after boot")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize app state after boot")
            appDataManager.resetToDefaults()
        }
    }

    private fun restoreScreensaverState(appDataManager: AppDataManager) {
        appDataManager.updateState { state ->
            state.copy(
                lastSyncTimestamp = System.currentTimeMillis()
            )
        }
    }
}