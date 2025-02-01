package com.example.screensaver.shared

/**
 * Represents an album from the Google Photos API
 * This data class serves as a container for album information retrieved from Google Photos
 */
data class GoogleAlbum(
    /**
     * Unique identifier for the album
     * Example: "ABC123xyz789"
     */
    val id: String,

    /**
     * The display name/title of the album
     * Example: "Vacation 2024"
     */
    val title: String,

    /**
     * Direct URL to the album's cover photo
     * Can be null if no cover photo is set
     * Example: "https://lh3.googleusercontent.com/abc123..."
     */
    val coverPhotoUrl: String?,

    /**
     * Total number of media items (photos/videos) in the album
     * Example: 42L
     */
    val mediaItemsCount: Long
)