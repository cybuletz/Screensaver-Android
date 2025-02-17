package com.example.screensaver.utils

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.SearchMediaItemsRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import com.example.screensaver.R

class PhotoManager private constructor(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null
    private val photoUrls = mutableListOf<String>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "PhotoManager"
        private const val PHOTO_QUALITY = "=w2560-h1440" // 2K quality
        @Volatile private var instance: PhotoManager? = null

        fun getInstance(context: Context): PhotoManager {
            return instance ?: synchronized(this) {
                instance ?: PhotoManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getPhotoCount(): Int = photoUrls.size

    private suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val refreshToken = prefs.getString("refresh_token", null)
                ?: throw Exception("No refresh token available")

            val clientId = context.getString(R.string.google_oauth_client_id)
            val clientSecret = context.getString(R.string.google_oauth_client_secret)

            val connection = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = StringBuilder()
                .append("grant_type=refresh_token")
                .append("&refresh_token=").append(refreshToken)
                .append("&client_id=").append(clientId)
                .append("&client_secret=").append(clientSecret)
                .toString()

            connection.outputStream.use { it.write(postData.toByteArray()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                prefs.edit()
                    .putString("access_token", jsonResponse.getString("access_token"))
                    .putLong("token_expiration", System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000))
                    .apply()

                true
            } else {
                Log.e(TAG, "Failed to refresh token: ${connection.responseMessage}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            false
        }
    }

    fun cleanup() {
        coroutineScope.cancel()
        photosLibraryClient?.shutdown()
        photosLibraryClient = null
        instance = null
    }
}