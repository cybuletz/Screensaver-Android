package com.photostreamr.version

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

@Singleton
class FeatureManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersionManager: AppVersionManager
) {
    enum class Feature {
        PHOTO_SLIDESHOW,
        MUSIC,
        WIDGETS,
        SECURITY,
        TRANSITION_EFFECTS
    }

    // Maps features to their descriptions for UI
    private val featureDescriptions = mapOf(
        Feature.PHOTO_SLIDESHOW to "Display photo slideshows from various sources",
        Feature.MUSIC to "Background music from Spotify during slideshow",
        Feature.WIDGETS to "Custom information widgets during slideshow",
        Feature.SECURITY to "Lock screen and privacy protection features",
        Feature.TRANSITION_EFFECTS to "Beautiful transition effects between photos"
    )

    fun isFeatureAvailable(feature: Feature): Boolean {
        // Photo slideshow, widgets, transition effects are always available except MUSIC, SECURITY
        return when (feature) {
            Feature.PHOTO_SLIDESHOW,
            Feature.WIDGETS,
            Feature.TRANSITION_EFFECTS -> true
            Feature.MUSIC,
            Feature.SECURITY -> appVersionManager.isProVersion()
        }
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

    fun getProVersionStateFlow(): StateFlow<AppVersionManager.VersionState> {
        return appVersionManager.versionState
    }

    fun observeFeatureAvailability(feature: Feature): Flow<Boolean> {
        return appVersionManager.versionState.map { state ->
            if (feature == Feature.PHOTO_SLIDESHOW) {
                true // Always available
            } else {
                state is AppVersionManager.VersionState.Pro
            }
        }
    }

    fun getFeatureDescription(feature: Feature): String {
        return featureDescriptions[feature] ?: "Feature description not available"
    }

    fun getAllFeatures(): List<Feature> {
        return Feature.values().toList()
    }

    fun getProFeatures(): List<Feature> {
        return Feature.values().filter { it != Feature.PHOTO_SLIDESHOW }
    }
}