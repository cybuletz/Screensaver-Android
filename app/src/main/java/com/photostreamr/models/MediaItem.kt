package com.photostreamr.models

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * Represents information about a photo album
 */
@Parcelize
data class AlbumInfo(
    val id: Long,
    val name: String,
    val photosCount: Int,
    val coverPhotoUri: Uri?
) : Parcelable

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

    enum class LoadState {
        IDLE,
        LOADING,
        LOADED,
        ERROR
    }

    val url: String
        get() = getFullQualityUrl()

    companion object {
        private const val PREVIEW_QUALITY = 60
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

    fun getFullQualityUrl(): String = baseUrl

    /**
     * Gets the URL for a preview version of the media item
     * @param maxDimension Maximum dimension (width or height) for the preview
     */
    fun getPreviewUrl(maxDimension: Int): String {
        return if (baseUrl.startsWith("content://")) {
            baseUrl // Return as-is for local content URIs
        } else {
            val (previewWidth, previewHeight) = calculatePreviewDimensions(maxDimension)
            "$baseUrl=w$previewWidth-h$previewHeight-q$PREVIEW_QUALITY"
        }
    }

    private fun calculatePreviewDimensions(maxDimension: Int): Pair<Int, Int> {
        return if (isPortrait) {
            val scaledWidth = (maxDimension * aspectRatio).toInt()
            Pair(scaledWidth, maxDimension)
        } else {
            val scaledHeight = (maxDimension / aspectRatio).toInt()
            Pair(maxDimension, scaledHeight)
        }
    }

    fun updateLoadState(newState: LoadState) {
        loadState = newState
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