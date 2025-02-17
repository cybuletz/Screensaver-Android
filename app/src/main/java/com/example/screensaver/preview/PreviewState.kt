package com.example.screensaver.preview

sealed class PreviewState {
    object Initial : PreviewState()
    data class Cooldown(val remainingSeconds: Long) : PreviewState()
    data class Error(val message: String) : PreviewState()
    data class Available(val remainingPreviews: Int) : PreviewState()
}