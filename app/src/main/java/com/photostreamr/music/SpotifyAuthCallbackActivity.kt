package com.photostreamr.music

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationResponse
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject
import android.os.Handler
import android.os.Looper

@AndroidEntryPoint
class SpotifyAuthCallbackActivity : AppCompatActivity() {

    @Inject
    lateinit var spotifyAuthManager: SpotifyAuthManager

    companion object {
        private const val SPOTIFY_AUTH_REQUEST_CODE = 1337
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("SpotifyAuthCallbackActivity onCreate with intent: ${intent?.data}")

        try {
            // Get the response from the intent
            val response = AuthorizationClient.getResponse(SPOTIFY_AUTH_REQUEST_CODE, intent)

            Timber.d("Auth response type: ${response.type}, error: ${response.error}")

            if (response.type == AuthorizationResponse.Type.ERROR &&
                response.error == "AUTHENTICATION_SERVICE_UNAVAILABLE") {
                // Try to launch the Spotify app first
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage("com.spotify.music")
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                        // Wait a bit before retrying auth
                        Handler(Looper.getMainLooper()).postDelayed({
                            retryAuth()
                        }, 1000)
                        return
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error launching Spotify app")
                }
            }

            // Handle the response
            spotifyAuthManager.handleAuthResponse(
                requestCode = SpotifyAuthManager.REQUEST_CODE,
                resultCode = if (response.type == AuthorizationResponse.Type.TOKEN) RESULT_OK else RESULT_CANCELED,
                data = intent
            )
        } catch (e: Exception) {
            Timber.e(e, "Error handling Spotify auth callback")
            spotifyAuthManager.handleAuthResponse(
                requestCode = SpotifyAuthManager.REQUEST_CODE,
                resultCode = RESULT_CANCELED,
                data = intent
            )
        }

        finish()
    }

    private fun retryAuth() {
        try {
            val authIntent = spotifyAuthManager.getAuthIntent(this)
            startActivity(authIntent)
        } catch (e: Exception) {
            Timber.e(e, "Error retrying auth")
        }
        finish()
    }
}