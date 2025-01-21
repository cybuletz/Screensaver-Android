package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

private const val TAG = "GooglePhotosManager"

interface GooglePhotosApiService {
    @GET("v1/mediaItems")
    suspend fun listMediaItems(
        @Header("Authorization") token: String,
        @Query("pageSize") pageSize: Int
    ): MediaItemsResponse
}

class GooglePhotosManager(private val context: Context) {
    private var accessToken: String? = null
    private val apiService: GooglePhotosApiService

    companion object {
        private const val BASE_URL = "https://photoslibrary.googleapis.com/"
    }

    init {
        Log.d(TAG, "Initializing GooglePhotosManager")
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(GooglePhotosApiService::class.java)
    }

    fun initialize(account: GoogleSignInAccount) {
        Log.d(TAG, "Initializing with account: ${account.email}")
        accessToken = account.idToken
        if (accessToken == null) {
            Log.e(TAG, "Failed to get access token")
        } else {
            Log.d(TAG, "Access token retrieved successfully")
        }
    }

    suspend fun getRandomPhotos(maxResults: Int = 50): List<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Getting random photos, maxResults: $maxResults")
            val token = accessToken
            if (token == null) {
                Log.e(TAG, "No access token available")
                return@withContext emptyList()
            }

            val authHeader = "Bearer $token"
            Log.d(TAG, "Calling photos API")
            val response = apiService.listMediaItems(authHeader, maxResults)
            Log.d(TAG, "Retrieved ${response.mediaItems.size} items")

            return@withContext response.mediaItems
                .filter { it.mimeType.startsWith("image/") }
                .map { it.baseUrl }
                .shuffled()
                .also { Log.d(TAG, "Returning ${it.size} filtered photos") }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos", e)
            return@withContext emptyList()
        }
    }
}