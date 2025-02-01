package com.example.screensaver

interface PhotosProvider {
    suspend fun getPhotos(): List<Photo>
    fun isAvailable(): Boolean
}