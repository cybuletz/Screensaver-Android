package com.example.screensaver

import android.content.Context
import android.webkit.JavascriptInterface

class SmartTimerClient(private val context: Context) {
    @JavascriptInterface
    fun getCurrentTime(): String {
        return System.currentTimeMillis().toString()
    }

    @JavascriptInterface
    fun showMessage(message: String) {
        // You can add implementation later
    }

    // Add more methods as needed
}