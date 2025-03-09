package com.example.screensaver.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.screensaver.data.AppDataManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import dagger.hilt.android.internal.Contexts
import dagger.hilt.EntryPoints

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var appDataManager: AppDataManager

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.d("Boot completed, initializing app state")
            initializeAppState()
        }
    }

    private fun initializeAppState() {
        if (!::appDataManager.isInitialized) {
            Timber.e("AppDataManager not initialized")
            return
        }

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

            // Check if screensaver was active
            if (currentState.isScreensaverReady) {
                Timber.d("Restoring screensaver state")
                restoreScreensaverState()
            }

            Timber.i("App state initialized successfully after boot")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize app state after boot")
            appDataManager.resetToDefaults()
        }
    }


    private fun restoreScreensaverState() {
        appDataManager.updateState { state ->
            state.copy(
                lastSyncTimestamp = System.currentTimeMillis()
            )
        }
    }
}