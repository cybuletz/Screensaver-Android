package com.example.screensaver.models

sealed class LoadingState {
    object IDLE : LoadingState()
    object LOADING : LoadingState()
    object SUCCESS : LoadingState()
    data class ERROR(val message: String) : LoadingState()
}