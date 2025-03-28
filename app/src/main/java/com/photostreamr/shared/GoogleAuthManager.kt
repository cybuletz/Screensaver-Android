package com.photostreamr.auth

import android.content.Context
import android.util.Log
import com.photostreamr.R
import com.photostreamr.data.SecureStorage
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val TOKEN_EXPIRY_BUFFER = 60000L // 1 minute buffer

        // Updated scopes for photo picker
        private val REQUIRED_SCOPES = listOf(
            "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        )
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.IDLE)
    val authState: StateFlow<AuthState> = _authState

    enum class AuthState {
        IDLE, AUTHENTICATING, AUTHENTICATED, ERROR
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            _authState.value = AuthState.AUTHENTICATING

            if (!hasValidTokens()) {
                _authState.value = AuthState.ERROR
                return@withContext false
            }

            val credentials = getOrRefreshCredentials()
            if (credentials == null) {
                _authState.value = AuthState.ERROR
                return@withContext false
            }

            _authState.value = AuthState.AUTHENTICATED
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in initialize", e)
            _authState.value = AuthState.ERROR
            false
        }
    }

    fun hasValidTokens(): Boolean {
        val credentials = secureStorage.getGoogleCredentials()
        return credentials != null &&
                credentials.accessToken.isNotEmpty() &&
                credentials.expirationTime > System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER
    }

    suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val credentials = secureStorage.getGoogleCredentials()
                ?: throw Exception("No credentials available")

            val refreshToken = credentials.refreshToken
            val clientId = context.getString(R.string.google_oauth_client_id)
            val clientSecret = context.getString(R.string.google_oauth_client_secret)

            connection = (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val postData = buildPostData(refreshToken, clientId, clientSecret)
            connection.outputStream.use { it.write(postData.toByteArray()) }

            return@withContext when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> handleSuccessfulTokenRefresh(connection, credentials)
                else -> handleFailedTokenRefresh(connection)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun getOrRefreshCredentials(): GoogleCredentials? = withContext(Dispatchers.IO) {
        try {
            val credentials = secureStorage.getGoogleCredentials()
            Log.d(TAG, "Checking credentials - Access Token: ${credentials != null}")

            if (credentials == null) {
                Log.d(TAG, "Missing tokens")
                return@withContext null
            }

            if (needsRefresh(credentials)) {
                Log.d(TAG, "Token expired or needs refresh")
                if (!refreshTokens()) {
                    return@withContext null
                }
                // Get fresh credentials after refresh
                val updatedCredentials = secureStorage.getGoogleCredentials() ?: return@withContext null
                return@withContext createGoogleCredentials(updatedCredentials)
            }

            createGoogleCredentials(credentials)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting/refreshing credentials", e)
            null
        }
    }

    private fun needsRefresh(credentials: SecureStorage.GoogleCredentials): Boolean {
        return credentials.expirationTime <= System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER
    }

    private fun buildPostData(refreshToken: String, clientId: String, clientSecret: String): String =
        buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(refreshToken)
            append("&client_id=").append(clientId)
            append("&client_secret=").append(clientSecret)
        }

    private fun handleSuccessfulTokenRefresh(
        connection: HttpURLConnection,
        currentCredentials: SecureStorage.GoogleCredentials
    ): Boolean {
        return try {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(response)

            val newAccessToken = jsonResponse.getString("access_token")
            val expirationTime = System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000)

            secureStorage.saveGoogleCredentials(
                accessToken = newAccessToken,
                refreshToken = currentCredentials.refreshToken,
                expirationTime = expirationTime,
                email = currentCredentials.email
            )

            Log.d(TAG, "Successfully refreshed token")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling token refresh response", e)
            false
        }
    }

    private fun handleFailedTokenRefresh(connection: HttpURLConnection): Boolean {
        Log.e(TAG, "Failed to refresh token: ${connection.responseMessage}")
        return false
    }

    private fun createGoogleCredentials(credentials: SecureStorage.GoogleCredentials): GoogleCredentials {
        return GoogleCredentials.create(AccessToken(credentials.accessToken, Date(credentials.expirationTime)))
            .createScoped(REQUIRED_SCOPES)
    }

    fun getCurrentCredentials(): SecureStorage.GoogleCredentials? {
        return secureStorage.getGoogleCredentials()
    }

    fun clearCredentials() {
        secureStorage.clearGoogleCredentials()
        _authState.value = AuthState.IDLE
    }
}