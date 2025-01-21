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
    private lateinit var googlePhotosManager: GooglePhotosManager

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

    // Update loadScreensaver method
    private fun loadScreensaver() {
        if (googlePhotosManager.isAuthenticated()) {
            lifecycleScope.launch {
                try {
                    val photoUrls = googlePhotosManager.getRandomPhotos()
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
                    // Handle error
                }
            }
        } else {
            // Start sign in activity
            startActivity(Intent(requireContext(), GoogleSignInActivity::class.java))
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