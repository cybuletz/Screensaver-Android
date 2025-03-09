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
    var isSelected: Boolean = false,
    val isHeader: Boolean = false
) : Parcelable {

    companion object {
        const val DEFAULT_ALBUM_TITLE = "Untitled Album"
        const val MIN_MEDIA_ITEMS = 0

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

    fun hasCover(): Boolean = !coverPhotoUrl.isNullOrBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Album) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = buildString {
        append("Album(id='$id'")
        append(", title='$title'")
        append(", mediaCount=$mediaItemsCount")
        append(", selected=$isSelected")
        append(", hasCover=${hasCover()})")
    }
}

fun List<Album>.selectedAlbums(): List<Album> = filter { it.isSelected }
