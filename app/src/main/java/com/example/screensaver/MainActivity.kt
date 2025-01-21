package com.example.screensaver

import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import android.webkit.WebViewClient

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }

        // Add WebView client to handle page loading
        webView.webViewClient = WebViewClient()

        // Add the SmartTimerClient interface
        webView.addJavascriptInterface(SmartTimerClient(this), "SmartTimerClient")

        // Load the index.html file from assets
        webView.loadUrl("file:///android_asset/index.html")
    }
}