package com.example.screensaver.music

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
        const val KEY_SPOTIFY_TOKEN = "afba4ceb5c5b466da28c58b5d0c5d54f"
        private const val DEV_MODE = true
    }

    fun getAuthIntent(activity: android.app.Activity): Intent {
        val builder = AuthorizationRequest.Builder(
            CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            REDIRECT_URI
        )

        builder.setScopes(arrayOf(
            "streaming",
            "playlist-read-private",
            "playlist-read-collaborative",
            "app-remote-control"
        ))

        if (DEV_MODE) {  // Use the companion object constant
            builder.setCustomParam("environment", "development")
        }

        val request = builder.build()
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }

    fun handleAuthResponse(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data == null) {
            _authState.value = AuthState.Error(Exception("No data received"))
            return
        }

        val response = AuthorizationClient.getResponse(requestCode, data)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                Timber.d("Auth successful")
                secureStorage.saveSecurely(KEY_SPOTIFY_TOKEN, response.accessToken)
                _authState.value = AuthState.Authenticated(response.accessToken)
                spotifyPreferences.setEnabled(true)
                // Don't call retry here, let the UI handle it
            }
            AuthorizationResponse.Type.ERROR -> {
                Timber.e("Auth error: ${response.error}")
                _authState.value = AuthState.Error(Exception(response.error))
                spotifyPreferences.setEnabled(false)
            }
            else -> {
                Timber.w("Auth cancelled")
                _authState.value = AuthState.NotAuthenticated
                spotifyPreferences.setEnabled(false)
            }
        }
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