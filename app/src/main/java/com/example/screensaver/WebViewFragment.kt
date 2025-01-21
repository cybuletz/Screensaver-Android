package com.example.screensaver

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.launch

class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var photosManager: GooglePhotosManager

    companion object {
        private const val TAG = "WebViewFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_web_view, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        webView = view.findViewById(R.id.webView)
        photosManager = GooglePhotosManager(requireContext())

        // Get the account from MainActivity
        val mainActivity = requireActivity() as MainActivity
        val account = MainActivity.Companion.GlobalAccountManager.getGoogleAccount()
        if (account == null) {
            Log.e(TAG, "Google account is null")
            return
        }

        // Initialize PhotosManager with the account
        photosManager.initialize(account)

        setupWebView()
        loadRandomPhoto()
    }

    private fun setupWebView() {
        Log.d(TAG, "Setting up WebView")
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            domStorageEnabled = true
            allowFileAccess = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished loading: $url")
            }
        }
    }

    private fun loadRandomPhoto() {
        Log.d(TAG, "Loading random photo")
        lifecycleScope.launch {
            try {
                val photos = photosManager.getRandomPhotos(1)
                Log.d(TAG, "Retrieved ${photos.size} photos")
                if (photos.isNotEmpty()) {
                    val photoUrl = photos.first()
                    Log.d(TAG, "Loading photo URL: $photoUrl")
                    webView.loadUrl(photoUrl)
                } else {
                    Log.e(TAG, "No photos retrieved")
                    // Show some error message to the user
                    webView.loadData(
                        "<html><body><h1>No photos available</h1></body></html>",
                        "text/html",
                        "UTF-8"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                // Show error message to the user
                webView.loadData(
                    "<html><body><h1>Error loading photos: ${e.message}</h1></body></html>",
                    "text/html",
                    "UTF-8"
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView.destroy()
    }
}