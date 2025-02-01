package com.example.screensaver.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem

/**
 * Represents a photo album in the application.
 * This class is immutable except for the selection state and implements Parcelable
 * for efficient data transfer between components.
 *
 * @property id Unique identifier for the album
 * @property title Display name of the album
 * @property coverPhotoUrl URL of the album's cover photo, null if no cover is set
 * @property mediaItemsCount Number of media items in the album
 * @property isSelected Whether the album is currently selected for display
 */
@Parcelize
data class Album(
    val id: String,
    val title: String,
    val coverPhotoUrl: String?,
    val mediaItemsCount: Int,
    var isSelected: Boolean = false
) : Parcelable {

    companion object {
        const val DEFAULT_ALBUM_TITLE = "Untitled Album"
        const val MIN_MEDIA_ITEMS = 0

        /**
         * Creates an empty album with default values
         */
        fun createEmpty(): Album = Album(
            id = "",
            title = DEFAULT_ALBUM_TITLE,
            coverPhotoUrl = null,
            mediaItemsCount = 0
        )

        fun createPreviewAlbum(id: String): Album = Album(
            id = id,
            title = DEFAULT_ALBUM_TITLE,
            coverPhotoUrl = null,
            mediaItemsCount = 0,
            isSelected = true
        )

    }

    init {
        require(title.isNotBlank()) { "Album title cannot be blank" }
        require(mediaItemsCount >= MIN_MEDIA_ITEMS) { "Media items count cannot be negative" }
    }

    /**
     * Checks if the album has any media items
     */
    fun hasMedia(): Boolean = mediaItemsCount > 0

    /**
     * Checks if the album has a valid cover photo
     */
    fun hasCover(): Boolean = !coverPhotoUrl.isNullOrBlank()

    /**
     * Creates a copy of the album with updated selection state
     */
    fun withSelection(selected: Boolean): Album = copy(isSelected = selected)

    /**
     * Creates a display name for the album, including the item count
     */
    fun getDisplayName(): String = buildString {
        append(title)
        if (mediaItemsCount > 0) {
            append(" ($mediaItemsCount)")
        }
    }

    /**
     * Validates the album data
     * @throws IllegalStateException if the album data is invalid
     */
    fun validate() {
        check(title.isNotBlank()) { "Album title cannot be blank" }
        check(mediaItemsCount >= MIN_MEDIA_ITEMS) { "Invalid media items count: $mediaItemsCount" }
        coverPhotoUrl?.let {
            check(it.isNotBlank()) { "Cover photo URL cannot be blank when provided" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Album) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    /**
     * Creates a string representation of the album for debugging
     */
    override fun toString(): String = buildString {
        append("Album(id='$id'")
        append(", title='$title'")
        append(", mediaCount=$mediaItemsCount")
        append(", selected=$isSelected")
        append(", hasCover=${hasCover()})")
    }
}

/**
 * Extension functions for Album collections
 */
fun List<Album>.selectedAlbums(): List<Album> = filter { it.isSelected }

fun List<Album>.totalMediaCount(): Int = sumOf { it.mediaItemsCount }

fun List<Album>.hasSelectedAlbums(): Boolean = any { it.isSelected }

/**
 * Sorts albums by various criteria
 */
fun List<Album>.sortByTitle(): List<Album> = sortedBy { it.title }

fun List<Album>.sortByMediaCount(): List<Album> = sortedByDescending { it.mediaItemsCount }

// Removed sortByCreationDate as createdAt property was not defined in the Album class