package com.example.screensaver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var photosManager: GooglePhotosManager

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
        photosManager = GooglePhotosManager(requireContext())

        setupWebView()
        loadRandomPhoto()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = WebViewClient()
    }

    private fun loadRandomPhoto() {
        lifecycleScope.launch {
            try {
                val photos = photosManager.getRandomPhotos(1)
                if (photos.isNotEmpty()) {
                    webView.loadUrl(photos.first())
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}