package com.example.screensaver.music

data class PlaybackState(
    val isPlaying: Boolean = false,
    val trackName: String = "",
    val artistName: String = "",
    val trackUri: String = "",
    val errorState: SpotifyManager.SpotifyError? = null
) {
    companion object {
        val Idle = PlaybackState()
    }
}