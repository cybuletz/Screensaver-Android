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
import com.example.screensaver.lock.PhotoLockScreenService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

@AndroidEntryPoint
class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var isWebViewInitialized = false

    @Inject
    lateinit var photoSourceState: PhotoSourceState

    companion object {
        private const val TAG = "MainFragment"
        private const val DEFAULT_URL = "file:///android_asset/index.html"
        private const val ERROR_PAGE = "file:///android_asset/error.html"
        private const val MIN_PREVIEW_INTERVAL = 5000L // 5 seconds between previews

        fun newInstance() = MainFragment()
    }

    private var errorToastCount = 0
    private val MAX_TOAST_COUNT = 3
    private var isPreviewEnabled = false

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
        setupViews()
        if (photoSourceState.isScreensaverReady()) {
            enablePreviewButton()
        } else {
            setupWebView(savedInstanceState)
        }
    }

    private fun setupViews() {
        binding.previewButton.apply {
            setOnClickListener {
                if (canStartPreview()) {
                    startPreviewMode()
                } else {
                    showPreviewCooldownMessage()
                }
            }
            visibility = if (photoSourceState.isScreensaverReady()) View.VISIBLE else View.GONE
        }

        binding.screensaverReadyCard.apply {
            visibility = if (photoSourceState.isScreensaverReady()) View.VISIBLE else View.GONE
        }
    }

    private fun enablePreviewButton() {
        binding.previewButton.apply {
            isEnabled = true
            alpha = 1.0f
            startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
        }
        isPreviewEnabled = true
    }

    private fun canStartPreview(): Boolean {
        return photoSourceState.getTimeSinceLastPreview() > MIN_PREVIEW_INTERVAL
    }

    private fun showPreviewCooldownMessage() {
        val remainingTime = (MIN_PREVIEW_INTERVAL - photoSourceState.getTimeSinceLastPreview()) / 1000
        Snackbar.make(
            binding.root,
            getString(R.string.preview_cooldown_message, remainingTime),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun startPreviewMode() {
        // Start the service with preview mode
        val serviceIntent = Intent(requireContext(), PhotoLockScreenService::class.java).apply {
            action = "START_PREVIEW"
        }
        ContextCompat.startForegroundService(requireContext(), serviceIntent)

        // Show preview activity
        val previewIntent = Intent(requireContext(), PhotoLockActivity::class.java).apply {
            putExtra("preview_mode", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(previewIntent)
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
        updatePreviewButtonState()
    }

    private fun updatePreviewButtonState() {
        if (photoSourceState.isScreensaverReady()) {
            binding.previewButton.visibility = View.VISIBLE
            binding.screensaverReadyCard.visibility = View.VISIBLE
            if (canStartPreview()) {
                enablePreviewButton()
            } else {
                binding.previewButton.isEnabled = false
                binding.previewButton.alpha = 0.5f
            }
        } else {
            binding.previewButton.visibility = View.GONE
            binding.screensaverReadyCard.visibility = View.GONE
        }
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
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
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