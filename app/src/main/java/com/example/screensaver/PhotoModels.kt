package com.example.screensaver

data class MediaItemsResponse(
    val mediaItems: List<MediaItem>
)

data class MediaItem(
    val id: String,
    val baseUrl: String,
    val mimeType: String
)