package com.example.screensaver.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a media item (photo) in the application.
 * This class is immutable and implements Parcelable for efficient data transfer between components.
 *
 * @property id Unique identifier for the media item
 * @property albumId ID of the album containing this media item
 * @property baseUrl Base URL of the media item for loading different sizes
 * @property mimeType The MIME type of the media item (e.g., "image/jpeg")
 * @property width Original width of the media item in pixels
 * @property height Original height of the media item in pixels
 * @property description Optional description of the media item
 * @property createdAt Timestamp when the photo was taken or created
 * @property loadState Current loading state of the media item
 */
@Parcelize
data class MediaItem(
    val id: String = UUID.randomUUID().toString(),
    val albumId: String,
    val baseUrl: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var loadState: LoadState = LoadState.IDLE
) : Parcelable {

    /**
     * Represents the loading state of a media item
     */
    enum class LoadState {
        IDLE,
        LOADING,
        LOADED,
        ERROR
    }

    companion object {
        private const val DEFAULT_QUALITY = 100
        private const val PREVIEW_QUALITY = 60
        private const val THUMBNAIL_QUALITY = 30

        /**
         * Creates an empty media item with default values
         */
        fun createEmpty(albumId: String): MediaItem = MediaItem(
            albumId = albumId,
            baseUrl = "",
            mimeType = "image/jpeg",
            width = 0,
            height = 0
        )
    }

    init {
        require(albumId.isNotBlank()) { "Album ID cannot be blank" }
        require(baseUrl.isNotBlank()) { "Base URL cannot be blank" }
        require(mimeType.isNotBlank()) { "MIME type cannot be blank" }
        require(width >= 0) { "Width must be non-negative" }
        require(height >= 0) { "Height must be non-negative" }
    }

    /**
     * Gets the aspect ratio of the media item
     */
    val aspectRatio: Float
        get() = if (height != 0) width.toFloat() / height.toFloat() else 0f

    /**
     * Checks if the media item is in portrait orientation
     */
    val isPortrait: Boolean
        get() = height > width

    /**
     * Checks if the media item is currently loading
     */
    val isLoading: Boolean
        get() = loadState == LoadState.LOADING

    /**
     * Gets the URL for the full-quality version of the media item
     */
    fun getFullQualityUrl(): String = "$baseUrl=w$width-h$height-q$DEFAULT_QUALITY"

    /**
     * Gets the URL for a preview version of the media item
     * @param maxDimension Maximum dimension (width or height) for the preview
     */
    fun getPreviewUrl(maxDimension: Int): String {
        val (previewWidth, previewHeight) = calculatePreviewDimensions(maxDimension)
        return "$baseUrl=w$previewWidth-h$previewHeight-q$PREVIEW_QUALITY"
    }

    /**
     * Gets the URL for a thumbnail version of the media item
     * @param size Desired size (width and height) for the thumbnail
     */
    fun getThumbnailUrl(size: Int): String =
        "$baseUrl=w$size-h$size-c-q$THUMBNAIL_QUALITY"

    /**
     * Calculates preview dimensions maintaining aspect ratio
     */
    private fun calculatePreviewDimensions(maxDimension: Int): Pair<Int, Int> {
        return if (isPortrait) {
            val scaledWidth = (maxDimension * aspectRatio).toInt()
            Pair(scaledWidth, maxDimension)
        } else {
            val scaledHeight = (maxDimension / aspectRatio).toInt()
            Pair(maxDimension, scaledHeight)
        }
    }

    /**
     * Updates the loading state of the media item
     */
    fun updateLoadState(newState: LoadState) {
        loadState = newState
    }

    /**
     * Validates the media item data
     * @throws IllegalStateException if the media item data is invalid
     */
    fun validate() {
        check(albumId.isNotBlank()) { "Album ID cannot be blank" }
        check(baseUrl.isNotBlank()) { "Base URL cannot be blank" }
        check(mimeType.isNotBlank()) { "MIME type cannot be blank" }
        check(width >= 0) { "Invalid width: $width" }
        check(height >= 0) { "Invalid height: $height" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaItem) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = buildString {
        append("MediaItem(id='$id'")
        append(", albumId='$albumId'")
        append(", ${width}x$height")
        append(", state=$loadState)")
    }
}

/**
 * Extension functions for MediaItem collections
 */
fun List<MediaItem>.sortByCreationDate(): List<MediaItem> =
    sortedByDescending { it.createdAt }

fun List<MediaItem>.filterPortrait(): List<MediaItem> =
    filter { it.isPortrait }

fun List<MediaItem>.filterLandscape(): List<MediaItem> =
    filter { !it.isPortrait }

fun List<MediaItem>.byLoadState(state: MediaItem.LoadState): List<MediaItem> =
    filter { it.loadState == state }