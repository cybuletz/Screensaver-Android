package com.example.screensaver.version

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeatureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersionManager: AppVersionManager
) {
    enum class Feature {
        PHOTO_SLIDESHOW,
        MUSIC,
        WIDGETS,
        SECURITY
    }

    fun isFeatureAvailable(feature: Feature): Boolean {
        // Photo slideshow is always available
        if (feature == Feature.PHOTO_SLIDESHOW) {
            return true
        }

        // All other features require pro version
        return appVersionManager.isProVersion()
    }

    fun showProVersionPrompt(feature: Feature): Boolean {
        // Don't show prompt for photo slideshow (always available)
        if (feature == Feature.PHOTO_SLIDESHOW) {
            return false
        }

        // Don't show prompt if user already has pro version
        if (appVersionManager.isProVersion()) {
            return false
        }

        // Show prompt for all restricted features in free version
        return true
    }
}