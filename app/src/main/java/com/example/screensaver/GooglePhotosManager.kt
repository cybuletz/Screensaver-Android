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
        private const val TAG = "GooglePhotosManager"
        private const val BASE_URL = "https://photoslibrary.googleapis.com/"
    }

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(GooglePhotosApiService::class.java)
    }

    fun initialize(account: GoogleSignInAccount) {
        accessToken = "Bearer ${account.idToken}"
        Log.d(TAG, "PhotosLibraryClient initialized successfully")
    }

    fun isAuthenticated() = accessToken != null

    suspend fun getRandomPhotos(maxResults: Int = 50): List<String> = withContext(Dispatchers.IO) {
        try {
            accessToken?.let { token ->
                val response = apiService.listMediaItems(token, maxResults)
                response.mediaItems
                    .filter { it.mimeType.startsWith("image/") }
                    .map { it.baseUrl }
                    .shuffled()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos: ${e.message}", e)
            emptyList()
        }
    }
}