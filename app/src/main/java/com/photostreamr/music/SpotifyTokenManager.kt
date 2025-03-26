package com.photostreamr.music

interface SpotifyTokenManager {
    fun clearToken()
    fun getAccessToken(): String?
}