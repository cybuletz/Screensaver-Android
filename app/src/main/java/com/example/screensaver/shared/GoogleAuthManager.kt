package com.example.screensaver.shared

import android.content.Context
import android.util.Log
import com.example.screensaver.R
import com.example.screensaver.data.SecureStorage
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        // Increase buffer to 5 minutes for better handling of almost-expired tokens
        private const val TOKEN_EXPIRY_BUFFER = 300000L // 5 minutes buffer
        private const val MAX_REFRESH_ATTEMPTS = 3
        private const val REFRESH_ATTEMPT_INTERVAL = 30000L // 30 seconds

        // Updated scopes for photo picker
        private val REQUIRED_SCOPES = listOf(
            "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
        )
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.IDLE)
    val authState: StateFlow<AuthState> = _authState

    private var lastRefreshAttempt = 0L
    private var refreshAttemptCount = 0

    enum class AuthState {
        IDLE, AUTHENTICATING, AUTHENTICATED, ERROR
    }

    /**
     * Helper method for consistent timestamp logging
     */
    private fun getCurrentDateTime(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            _authState.value = AuthState.AUTHENTICATING

            Log.d(TAG, "Initializing authentication at ${getCurrentDateTime()}")

            if (!hasValidTokens()) {
                Log.d(TAG, "No valid tokens found, attempting to refresh")
                if (!refreshTokens()) {
                    _authState.value = AuthState.ERROR
                    return@withContext false
                }
            }

            val credentials = getOrRefreshCredentials()
            if (credentials == null) {
                _authState.value = AuthState.ERROR
                Log.e(TAG, "Failed to get valid credentials")
                return@withContext false
            }

            _authState.value = AuthState.AUTHENTICATED
            Log.d(TAG, "Successfully authenticated at ${getCurrentDateTime()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in initialize at ${getCurrentDateTime()}", e)
            _authState.value = AuthState.ERROR
            false
        }
    }

    fun hasValidTokens(): Boolean {
        val credentials = secureStorage.getGoogleCredentials()
        val isValid = credentials != null &&
                credentials.accessToken.isNotEmpty() &&
                credentials.expirationTime > System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER

        if (!isValid && credentials != null) {
            Log.d(TAG, "Tokens invalid or expiring soon. Current time: ${System.currentTimeMillis()}, Expiry time: ${credentials.expirationTime}")
        }

        return isValid
    }

    suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        // Add rate limiting for refresh attempts
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRefreshAttempt < REFRESH_ATTEMPT_INTERVAL) {
            refreshAttemptCount++
            if (refreshAttemptCount > MAX_REFRESH_ATTEMPTS) {
                Log.w(TAG, "Too many token refresh attempts in a short period at ${getCurrentDateTime()}")
                return@withContext false
            }
        } else {
            refreshAttemptCount = 1  // Reset counter but count this attempt
        }
        lastRefreshAttempt = currentTime

        var connection: HttpURLConnection? = null
        try {
            val credentials = secureStorage.getGoogleCredentials()
                ?: throw Exception("No credentials available")

            val refreshToken = credentials.refreshToken
            val clientId = context.getString(R.string.google_oauth_client_id)
            val clientSecret = context.getString(R.string.google_oauth_client_secret)

            Log.d(TAG, "Refreshing tokens at ${getCurrentDateTime()}")

            connection = (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15000  // 15 seconds
                readTimeout = 15000     // 15 seconds
            }

            val postData = buildPostData(refreshToken, clientId, clientSecret)
            connection.outputStream.use { it.write(postData.toByteArray()) }

            return@withContext when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> handleSuccessfulTokenRefresh(connection, credentials)
                else -> handleFailedTokenRefresh(connection)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token at ${getCurrentDateTime()}", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun getOrRefreshCredentials(): GoogleCredentials? = withContext(Dispatchers.IO) {
        try {
            val credentials = secureStorage.getGoogleCredentials()

            if (credentials == null) {
                Log.d(TAG, "Missing tokens at ${getCurrentDateTime()}")
                return@withContext null
            }

            if (needsRefresh(credentials)) {
                Log.d(TAG, "Token expired or needs refresh at ${getCurrentDateTime()}")
                if (!refreshTokens()) {
                    return@withContext null
                }
                // Get fresh credentials after refresh
                val updatedCredentials = secureStorage.getGoogleCredentials() ?: return@withContext null
                return@withContext createGoogleCredentials(updatedCredentials)
            }

            createGoogleCredentials(credentials)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting/refreshing credentials at ${getCurrentDateTime()}", e)
            null
        }
    }

    private fun needsRefresh(credentials: SecureStorage.GoogleCredentials): Boolean {
        val needsRefresh = credentials.expirationTime <= System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER
        if (needsRefresh) {
            Log.d(TAG, "Token needs refresh - Expiration: ${credentials.expirationTime}, Current: ${System.currentTimeMillis()}")
        }
        return needsRefresh
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

            // Save refreshed credentials
            secureStorage.saveGoogleCredentials(
                accessToken = newAccessToken,
                refreshToken = currentCredentials.refreshToken,
                expirationTime = expirationTime,
                email = currentCredentials.email
            )

            Log.d(TAG, "Successfully refreshed token at ${getCurrentDateTime()}, new expiry: ${Date(expirationTime)}")

            // Reset attempt count on success
            refreshAttemptCount = 0
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling token refresh response at ${getCurrentDateTime()}", e)
            false
        }
    }

    private fun handleFailedTokenRefresh(connection: HttpURLConnection): Boolean {
        val error = try {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
        } catch (e: Exception) {
            "Failed to read error response: ${e.message}"
        }

        Log.e(TAG, "Failed to refresh token at ${getCurrentDateTime()}: ${connection.responseCode} - ${connection.responseMessage} - $error")
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
        refreshAttemptCount = 0
        Log.d(TAG, "Credentials cleared at ${getCurrentDateTime()}")
    }
}