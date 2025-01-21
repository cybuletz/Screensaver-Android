package com.example.screensaver

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var preferencesManager: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private lateinit var googlePhotosManager: GooglePhotosManager
    private val photosHelper = PhotosHelper.getInstance()

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
        googlePhotosManager = GooglePhotosManager(requireContext())

        WebView.setWebContentsDebuggingEnabled(true)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }

        // Initialize PhotosHelper with Google Account if available
        MainActivity.AccountManager.getGoogleAccount()?.let { account ->
            photosHelper.initialize(account)
        }

        setupAutoRefresh()
        loadScreensaver()
    }

    private fun loadScreensaver() {
        if (MainActivity.AccountManager.getGoogleAccount() != null) {
            lifecycleScope.launch {
                try {
                    val photos = photosHelper.getPhotos(maxResults = 50)
                    val photoUrls = photos.map { it.url }

                    // Create a simple HTML slideshow
                    val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <style>
                            body { margin: 0; background: black; }
                            .slideshow { 
                                width: 100vw; 
                                height: 100vh; 
                                object-fit: contain;
                            }
                        </style>
                        <script>
                            const photos = ${photoUrls.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }};
                            let currentIndex = 0;
                            
                            function nextPhoto() {
                                currentIndex = (currentIndex + 1) % photos.length;
                                document.getElementById('photo').src = photos[currentIndex];
                            }
                            
                            setInterval(nextPhoto, 10000);
                        </script>
                    </head>
                    <body>
                        <img id="photo" class="slideshow" src="${photoUrls.firstOrNull()}" />
                    </body>
                    </html>
                    """.trimIndent()

                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                } catch (e: Exception) {
                    // Fallback to server URL if Google Photos fails
                    loadServerUrl()
                }
            }
        } else {
            loadServerUrl()
        }
    }

    private fun loadServerUrl() {
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

        refreshRunnable = Runnable {
            loadScreensaver()
            setupAutoRefresh() // Schedule next refresh
        }.also {
            handler.postDelayed(it, REFRESH_INTERVAL)
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
        private const val REFRESH_INTERVAL = 3600000L // 1 hour in milliseconds
        fun newInstance() = WebViewFragment()
    }
}