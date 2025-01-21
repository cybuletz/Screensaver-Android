package com.example.screensaver

import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

private const val TAG = "PhotosHelper"
private const val BASE_URL = "https://photoslibrary.googleapis.com/"

interface GooglePhotosApi {
    @GET("v1/mediaItems")
    suspend fun listMediaItems(
        @Header("Authorization") token: String,
        @Query("pageSize") pageSize: Int
    ): MediaItemsResponse
}

data class MediaItemsResponse(
    val mediaItems: List<MediaItem>
)

data class MediaItem(
    val id: String,
    val baseUrl: String,
    val mimeType: String
)

class PhotosHelper(private val account: GoogleSignInAccount) {
    private val api: GooglePhotosApi

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(GooglePhotosApi::class.java)
    }

    suspend fun getRandomPhotos(count: Int = 50): List<String> = withContext(Dispatchers.IO) {
        try {
            val token = "Bearer ${account.idToken}"
            val response = api.listMediaItems(token, count)
            response.mediaItems
                .filter { it.mimeType.startsWith("image/") }
                .map { it.baseUrl }
                .shuffled()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos: ${e.message}", e)
            emptyList()
        }
    }
}