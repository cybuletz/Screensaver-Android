package com.example.screensaver

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.screensaver.databinding.FragmentMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var isWebViewInitialized = false

    companion object {
        private const val TAG = "MainFragment"
        private const val DEFAULT_URL = "file:///android_asset/index.html"
        private const val ERROR_PAGE = "file:///android_asset/error.html"

        fun newInstance() = MainFragment()
    }

    private var errorToastCount = 0
    private val MAX_TOAST_COUNT = 3

    private fun showErrorToast(message: String) {
        if (errorToastCount < MAX_TOAST_COUNT) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            errorToastCount++
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let {
            binding.webView.saveState(outState)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(savedInstanceState: Bundle?) {
        configureWebViewSettings()
        setupWebViewClient()
        setupJavaScriptInterface()

        lifecycleScope.launch {
            try {
                if (savedInstanceState != null) {
                    binding.webView.restoreState(savedInstanceState)
                } else {
                    binding.webView.loadUrl(DEFAULT_URL)
                }
                isWebViewInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebView", e)
                handleWebViewError()
            }
        }
    }

    private fun configureWebViewSettings() {
        binding.webView.settings.apply {
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
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WebView error: ${error?.description}")
                view?.loadUrl(ERROR_PAGE)
                showErrorToast("Error loading page: ${error?.description}")
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.url?.let { uri ->
                    if (uri.scheme in listOf("http", "https")) {
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
            binding.webView.addJavascriptInterface(
                SmartTimerClient(requireContext()),
                "SmartTimerClient"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up JavaScript interface", e)
            showError("Failed to initialize app features")
        }
    }

    fun refreshWebView() {
        if (isWebViewInitialized) {
            binding.webView.reload()
        } else {
            binding.webView.loadUrl(DEFAULT_URL)
        }
    }

    private fun handleWebViewError() {
        try {
            binding.webView.loadUrl(ERROR_PAGE)
            showError("Error loading page")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WebView error", e)
            showError("Application error")
        }
    }

    private fun showError(message: String) {
        showErrorToast(message)
    }

    fun clearWebViewData() {
        binding.webView.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }
}