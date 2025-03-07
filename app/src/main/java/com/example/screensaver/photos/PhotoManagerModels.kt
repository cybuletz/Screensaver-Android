    package com.example.screensaver.photos

    import android.os.Parcelable
    import kotlinx.parcelize.Parcelize
    import java.time.Instant

    sealed class PhotoManagerState {
        object Idle : PhotoManagerState()
        object Loading : PhotoManagerState()
        data class Error(val message: String) : PhotoManagerState()
        data class Success(val message: String) : PhotoManagerState()
    }

    @Parcelize
    data class ManagedPhoto(
        val id: String,
        val uri: String,
        val sourceType: PhotoSourceType,
        val albumId: String?,
        val dateAdded: Long = System.currentTimeMillis(),
        var isSelected: Boolean = false
    ) : Parcelable

    @Parcelize
    data class VirtualAlbum(
        val id: String,
        val name: String,
        val photoUris: List<String>,
        val dateCreated: Long = System.currentTimeMillis(),
        val isSelected: Boolean = false
    ) : Parcelable

    enum class PhotoSourceType {
        GOOGLE_PHOTOS,
        LOCAL,
        VIRTUAL
    }