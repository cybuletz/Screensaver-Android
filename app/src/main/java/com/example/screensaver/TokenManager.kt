package com.example.screensaver

import android.content.Context
import java.util.Date

class TokenManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    fun saveTokens(accessToken: String, refreshToken: String?, expiresIn: Long) {
        val expirationTime = System.currentTimeMillis() + (expiresIn * 1000)
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("token_expiration", expirationTime)
            .apply()
    }

    fun getValidAccessToken(): String? {
        val expiration = prefs.getLong("token_expiration", 0)
        val currentTime = System.currentTimeMillis()

        return if (currentTime < expiration) {
            prefs.getString("access_token", null)
        } else {
            null
        }
    }
}