package com.example.screensaver.models

data class MediaItemModel(
    val id: String,
    val baseUrl: String,
    val mimeType: String,
    val filename: String,
    var loadState: LoadState = LoadState.IDLE
) {
    enum class LoadState {
        IDLE, LOADING, LOADED, ERROR
    }
}