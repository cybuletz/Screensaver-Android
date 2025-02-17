package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.ListMediaItemsRequest
import com.google.photos.types.proto.MediaItem
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class GooglePhotosManager(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null
    private var isInitialized = false


    companion object {
        private const val TAG = "GooglePhotosManager"
        private val REQUIRED_SCOPES = listOf(
            //"https://www.googleapis.com/auth/photoslibrary.readonly"
            "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        )
    }

    @Throws(IllegalStateException::class)
    fun initialize(account: GoogleSignInAccount) {
        try {
            Log.d(TAG, "Initializing with account: ${account.email}")

            // Check if we have a valid ID token
            val token = account.idToken
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "ID token is null or empty")
                throw IllegalStateException("No valid ID token available")
            }

            // Create credentials with all required scopes
            val credentials = GoogleCredentials.create(
                AccessToken(
                    token,
                    Date(System.currentTimeMillis() + 3600000) // Token expires in 1 hour
                )
            ).createScoped(REQUIRED_SCOPES)

            // Build settings with credentials provider
            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            // Initialize the client
            photosLibraryClient = PhotosLibraryClient.initialize(settings).also {
                isInitialized = true
                Log.d(TAG, "PhotosLibrary client initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PhotosLibrary client", e)
            isInitialized = false
            photosLibraryClient = null
            throw IllegalStateException("Failed to initialize Photos API: ${e.message}", e)
        }
    }

    fun cleanup() {
        try {
            photosLibraryClient?.shutdown()
            photosLibraryClient = null
            isInitialized = false
            Log.d(TAG, "PhotosLibrary client cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}