package com.example.screensaver.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.example.screensaver.BuildConfig
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import com.example.screensaver.data.SecureStorage
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

@Singleton
class SpotifyAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage,
    private val spotifyPreferences: SpotifyPreferences
) : SpotifyTokenManager {

    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    companion object {
        private const val CLIENT_ID = "b6d959e9ca544b2aaebb37d0bb41adb5"
        private const val REDIRECT_URI = "screensaver-spotify://callback"
        const val REQUEST_CODE = 1337
        private const val DEV_MODE = false
        const val KEY_SPOTIFY_TOKEN = "spotify_access_token"
    }

    private fun AuthorizationRequest.Builder.setCustomParam(key: String, value: String): AuthorizationRequest.Builder {
        try {
            val method = AuthorizationRequest.Builder::class.java.getDeclaredMethod(
                "setCustomParam",
                String::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(this, key, value)
        } catch (e: Exception) {
            Timber.e(e, "Failed to set custom param: $key=$value")
        }
        return this
    }

    fun getAuthIntent(activity: Activity): Intent {
        val builder = AuthorizationRequest.Builder(
            CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            REDIRECT_URI
        ).apply {
            setScopes(arrayOf(
                "streaming",
                "playlist-read-private",
                "playlist-read-collaborative",
                "app-remote-control",
                "user-read-playback-state",
                "user-modify-playback-state"
            ))
            setShowDialog(true)
        }

        Timber.d("Creating auth request with URI: $REDIRECT_URI")
        val request = builder.build()
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }

    fun handleAuthResponse(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE) {
            Timber.w("Received response for unknown request code: $requestCode")
            return
        }

        if (data == null) {
            Timber.e("Authentication failed: No data received")
            _authState.value = AuthState.Error(Exception("No data received"))
            return
        }

        try {
            // Get the response from the EXTRA or fall back to creating it from the intent
            val response = data.getParcelableExtra<AuthorizationResponse>("response")
                ?: AuthorizationClient.getResponse(resultCode, data)

            Timber.d("Auth response received: type=${response.type}, error=${response.error}, code=${response.code}")

            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    if (response.accessToken != null) {
                        Timber.d("Auth successful with token: ${response.accessToken.take(5)}...")
                        secureStorage.saveSecurely(KEY_SPOTIFY_TOKEN, response.accessToken)
                        _authState.value = AuthState.Authenticated(response.accessToken)
                        spotifyPreferences.setEnabled(true)
                    } else {
                        Timber.e("Auth failed: Received TOKEN response but token is null")
                        _authState.value = AuthState.Error(Exception("Invalid token received"))
                        spotifyPreferences.setEnabled(false)
                    }
                }
                AuthorizationResponse.Type.ERROR -> {
                    val errorMessage = when (response.error) {
                        "AUTHENTICATION_SERVICE_UNAVAILABLE" -> {
                            if (DEV_MODE) {
                                "Development mode: Please verify your account is added to allowed test users in Spotify Dashboard"
                            } else {
                                "Authentication service unavailable. Please try again later."
                            }
                        }
                        "INVALID_CLIENT" -> "Invalid client ID. Please check your Spotify app configuration."
                        "INVALID_SCOPE" -> "Invalid permissions requested. Please check the app configuration."
                        "USER_CANCELLED" -> "Authentication cancelled by user"
                        "UNSUPPORTED_RESPONSE_TYPE" -> "Invalid response type requested"
                        "FAILED" -> "Authentication failed. Please try again."
                        null -> "Unknown error occurred"
                        else -> response.error
                    }
                    Timber.e("Auth error: $errorMessage (Original error: ${response.error})")
                    _authState.value = AuthState.Error(Exception(errorMessage))
                    spotifyPreferences.setEnabled(false)
                }
                AuthorizationResponse.Type.CODE -> {
                    // This shouldn't happen as we request TOKEN response type
                    Timber.w("Received CODE response type when TOKEN was expected")
                    _authState.value = AuthState.Error(Exception("Unexpected authorization code received"))
                    spotifyPreferences.setEnabled(false)
                }
                AuthorizationResponse.Type.EMPTY -> {
                    Timber.w("Auth cancelled by user (EMPTY response)")
                    _authState.value = AuthState.NotAuthenticated
                    spotifyPreferences.setEnabled(false)
                }
                null -> {
                    Timber.e("Auth failed: Null response type")
                    _authState.value = AuthState.Error(Exception("Invalid response received"))
                    spotifyPreferences.setEnabled(false)
                }
                else -> {
                    // Handle any other response types that might be added in the future
                    Timber.w("Unhandled auth response type: ${response.type}")
                    _authState.value = AuthState.Error(Exception("Unhandled response type: ${response.type}"))
                    spotifyPreferences.setEnabled(false)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception while handling auth response")
            _authState.value = AuthState.Error(e)
            spotifyPreferences.setEnabled(false)
        }

        // Log final state
        Timber.d("Auth flow completed. Final state: ${_authState.value}")
    }

    fun logout() {
        secureStorage.removeSecurely(KEY_SPOTIFY_TOKEN)
        _authState.value = AuthState.NotAuthenticated
        spotifyPreferences.setEnabled(false)
    }

    override fun clearToken() {
        secureStorage.removeSecurely(KEY_SPOTIFY_TOKEN)
        _authState.value = AuthState.NotAuthenticated
    }

    sealed class AuthState {
        object NotAuthenticated : AuthState()
        data class Authenticated(val token: String) : AuthState()
        data class Error(val error: Exception) : AuthState()
    }
}