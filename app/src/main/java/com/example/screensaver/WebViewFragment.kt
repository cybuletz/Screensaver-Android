package com.example.screensaver

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private var isLoading = false

    companion object {
        private const val TAG = "WebViewFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_web_view, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webView)
        setupWebView()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                initializePhotosManager()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize photos manager", e)
                withContext(Dispatchers.Main) {
                    showError("Failed to initialize: ${e.message}")
                }
            }
        }
    }

    private fun setupWebView() {
        // Set hardware acceleration
        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            domStorageEnabled = true
            allowFileAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isLoading = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isLoading = false
            }
        }
    }

    private suspend fun initializePhotosManager() {
        withContext(Dispatchers.IO) {
            val account = MainActivity.Companion.GlobalAccountManager.getGoogleAccount()
            if (account == null) {
                Log.e(TAG, "Google account is null")
                withContext(Dispatchers.Main) {
                    (activity as? MainActivity)?.signIn()
                }
                return@withContext
            }

            // For now, just load a placeholder URL
            loadUrl("https://via.placeholder.com/400")
        }
    }

    private suspend fun loadUrl(url: String) {
        if (isLoading) return

        withContext(Dispatchers.Main) {
            try {
                webView.loadUrl(url)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading URL", e)
                showError("Failed to load image: ${e.message}")
            }
        }
    }

    private fun showError(message: String) {
        webView.loadData(
            "<html><body><h1>Error</h1><p>$message</p></body></html>",
            "text/html",
            "UTF-8"
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView.destroy()
    }
}