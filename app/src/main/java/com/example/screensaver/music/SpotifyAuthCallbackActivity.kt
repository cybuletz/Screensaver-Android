package com.example.screensaver.music

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.spotify.sdk.android.auth.AuthorizationClient
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SpotifyAuthCallbackActivity : AppCompatActivity() {

    @Inject
    lateinit var spotifyAuthManager: SpotifyAuthManager

    companion object {
        private const val SPOTIFY_AUTH_REQUEST_CODE = 1337
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle the response with all required parameters
        spotifyAuthManager.handleAuthResponse(
            requestCode = SPOTIFY_AUTH_REQUEST_CODE,
            resultCode = RESULT_OK,
            data = intent
        )

        // Close the activity
        finish()
    }
}