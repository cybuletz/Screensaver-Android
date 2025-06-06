package com.photostreamr.models

sealed class LoadingState {
    object IDLE : LoadingState()
    object LOADING : LoadingState()
    data class ERROR(val message: String) : LoadingState()
}