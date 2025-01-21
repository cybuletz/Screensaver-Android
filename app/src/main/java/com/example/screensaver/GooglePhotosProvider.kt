package com.example.screensaver

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.android.gms.auth.api.signin.GoogleSignIn

class GooglePhotosProvider(private val context: Context) : PhotosProvider {
    override suspend fun getPhotos(): List<Photo> {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return emptyList()
        // Implement Google Photos API calls using the account
        return listOf(
            Photo(
                id = "sample",
                url = "https://example.com/photo.jpg",
                title = "Sample Photo",
                description = "Uploaded by ${account.email}"
            )
        )
    }

    override fun isAvailable(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("use_google_photos", false)
    }
}