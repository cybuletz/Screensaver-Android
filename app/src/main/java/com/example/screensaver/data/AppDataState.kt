package com.example.screensaver.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.time.Instant

/**
 * Represents the complete state of the application's data.
 * This immutable data class ensures thread-safety and predictable state updates.
 *
 * @property lastModified Timestamp of the last modification to the state
 * @property version State version for migration purposes
 */
@Parcelize
data class AppDataState(
    val showPhotoInfo: Boolean = true,
    val darkMode: Boolean = false,
    val kioskModeEnabled: Boolean = false,

    // Display Settings
    val displayMode: String = "dream_service",
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val clockFormat: String = "24h",
    val brightness: Int = 50,
    val orientation: String = "auto",
    val keepScreenOn: Boolean = false,

    // Photo Source Settings
    val photoSources: Set<String> = setOf("local"),
    val selectedAlbums: Set<String> = emptySet(),
    val selectedLocalFolders: Set<String> = emptySet(),
    val googlePhotosEnabled: Boolean = false,

    // Transition Settings
    val transitionInterval: Int = 30,
    val transitionAnimation: String = "fade",
    val randomOrder: Boolean = true,
    val photoScale: String = "fill",

    // Schedule Settings
    val scheduleEnabled: Boolean = false,
    val startTime: String = "09:00",
    val endTime: String = "17:00",
    val activeDays: Set<String> = emptySet(),

    // State Information
    val lastSyncTimestamp: Long = 0,
    val isScreensaverReady: Boolean = false,
    val lastModified: Long = Instant.now().epochSecond,
    val version: Int = CURRENT_VERSION,
    val recoveryAttempts: List<Long> = emptyList(),
    val lastBackupTimestamp: Long = 0,
    val lastRestoredTimestamp: Long = 0,

    val previewCount: Int = 0,
    val lastPreviewTimestamp: Long = 0,
    val lastPreviewResetTime: Long = 0,
    val isInPreviewMode: Boolean = false,
    val authToken: String = "",
    val refreshToken: String = "",
    val accountEmail: String = "",
) : Parcelable {

    companion object {
        const val CURRENT_VERSION = 1

        fun createDefault() = AppDataState()
    }

    /**
     * Validates the state data
     * @throws IllegalStateException if the state is invalid
     */
    fun validate() {
        check(brightness in 0..100) { "Brightness must be between 0 and 100" }
        check(transitionInterval >= 5) { "Transition interval must be at least 5 seconds" }
        check(startTime.matches(Regex("^([0-1]?[0-9]|2[0-3]):[0-5][0-9]$"))) { "Invalid start time format" }
        check(endTime.matches(Regex("^([0-1]?[0-9]|2[0-3]):[0-5][0-9]$"))) { "Invalid end time format" }
    }

    fun withUpdatedTimestamp(): AppDataState = copy(
        lastModified = Instant.now().epochSecond
    )

    override fun toString(): String = buildString {
        append("AppDataState(")
        append("displayMode='$displayMode', ")
        append("photoSources=${photoSources.size}, ")
        append("selectedAlbums=${selectedAlbums.size}, ")
        append("googlePhotosEnabled=$googlePhotosEnabled, ")
        append("isScreensaverReady=$isScreensaverReady, ")
        append("lastModified=$lastModified, ")
        append("version=$version")
        append(")")
    }
}