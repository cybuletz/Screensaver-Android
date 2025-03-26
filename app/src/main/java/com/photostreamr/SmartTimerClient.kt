package com.photostreamr

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast

class SmartTimerClient(private val context: Context) {
    @JavascriptInterface
    fun getCurrentTime(): String {
        return System.currentTimeMillis().toString()
    }

    @JavascriptInterface
    fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}