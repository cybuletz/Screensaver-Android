package com.example.screensaver.shared

import com.example.screensaver.shared.GooglePhotosManager

/**
 * Interface for getting the GooglePhotosManager from the Application
 */
interface HasGooglePhotosManager {
    // Renamed method to avoid conflict with property getter
    fun provideGooglePhotosManager(): GooglePhotosManager
}