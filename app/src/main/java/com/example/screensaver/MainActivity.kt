package com.example.screensaver

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var isWebViewInitialized = false

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL = "file:///android_asset/index.html"
        private const val ERROR_PAGE = "file:///android_asset/error.html"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupWebView(savedInstanceState)
        setupMenu()
        handleBackPress()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.apply {
            stopLoading()
            removeJavascriptInterface("SmartTimerClient")
            destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(savedInstanceState: Bundle?) {
        webView = findViewById(R.id.webView)

        configureWebViewSettings()
        setupWebViewClient()
        setupJavaScriptInterface()

        lifecycleScope.launch {
            try {
                if (savedInstanceState != null) {
                    webView.restoreState(savedInstanceState)
                } else {
                    webView.loadUrl(DEFAULT_URL)
                }
                isWebViewInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebView", e)
                handleWebViewError()
            }
        }
    }

    private fun configureWebViewSettings() {
        webView.settings.apply {
            // Security settings
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            // Performance settings
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT

            // Feature settings
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Mixed content settings
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Misc settings
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                Log.e(TAG, "WebView error: ${error?.description}")
                handleWebViewError()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.url?.let { uri ->
                    if (uri.scheme in listOf("http", "https")) {
                        // Handle external URLs
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun setupJavaScriptInterface() {
        try {
            webView.addJavascriptInterface(
                SmartTimerClient(this),
                "SmartTimerClient"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up JavaScript interface", e)
            showError("Failed to initialize app features")
        }
    }

    private fun setupMenu() {
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_settings -> {
                        navigateToSettings()
                        true
                    }
                    R.id.action_refresh -> {
                        refreshWebView()
                        true
                    }
                    else -> false
                }
            }
        }, this, Lifecycle.State.RESUMED)
    }

    private fun handleBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack() && isWebViewInitialized) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun navigateToSettings() {
        try {
            startActivity(Intent(this, SettingsActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to settings", e)
            showError("Unable to open settings")
        }
    }

    private fun refreshWebView() {
        if (isWebViewInitialized) {
            webView.reload()
        } else {
            webView.loadUrl(DEFAULT_URL)
        }
    }

    private fun handleWebViewError() {
        try {
            webView.loadUrl(ERROR_PAGE)
            showError("Error loading page")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WebView error", e)
            showError("Application error")
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun clearWebViewData() {
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()
    }
}