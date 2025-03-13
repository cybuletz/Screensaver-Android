package com.example.screensaver.music

interface SpotifyTokenManager {
    fun clearToken()
    fun getAccessToken(): String?
}