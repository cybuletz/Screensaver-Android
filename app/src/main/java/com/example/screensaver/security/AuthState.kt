package com.example.screensaver.security

sealed class AuthState {
    object Authenticated : AuthState()
    object Unauthenticated : AuthState()
    object NotRequired : AuthState()
    data class Error(val message: String) : AuthState()
}