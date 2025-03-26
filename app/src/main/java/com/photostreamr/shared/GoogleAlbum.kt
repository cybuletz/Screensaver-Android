package com.photostreamr.shared

data class GoogleAlbum(
    val id: String,
    val title: String,
    val coverPhotoUrl: String?,
    val mediaItemsCount: Long
)