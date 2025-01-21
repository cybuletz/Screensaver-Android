package com.example.screensaver

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var preferencesManager: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return WebView(requireContext()).also {
            webView = it
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferencesManager = PreferencesManager(requireContext())

        WebView.setWebContentsDebuggingEnabled(true)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }

        setupAutoRefresh()
        loadScreensaver()
    }

    private fun loadScreensaver() {
        val username = preferencesManager.getUsername()
        val serverUrl = preferencesManager.getServerUrl()

        // Construct the URL with parameters
        val url = if (username.isNotEmpty()) {
            "$serverUrl?username=$username"
        } else {
            serverUrl
        }

        webView.loadUrl(url)
    }

    private fun setupAutoRefresh() {
        refreshRunnable?.let { handler.removeCallbacks(it) }

        refreshRunnable = object : Runnable {
            override fun run() {
                loadScreensaver()
                handler.postDelayed(
                    this,
                    preferencesManager.getRefreshInterval() * 1000 // Convert to milliseconds
                )
            }
        }

        if (preferencesManager.isAutoStartEnabled()) {
            refreshRunnable?.let { handler.post(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        setupAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        refreshRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshRunnable?.let { handler.removeCallbacks(it) }
    }

    companion object {
        fun newInstance() = WebViewFragment()
    }
}