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
import androidx.preference.PreferenceManager
import com.example.screensaver.ui.PhotoDisplayManager
import com.example.screensaver.lock.LockScreenPhotoManager
import kotlinx.coroutines.flow.collectLatest
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.RequiresApi

@AndroidEntryPoint
class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private var isWebViewInitialized = false

    @Inject
    lateinit var photoSourceState: PhotoSourceState

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var photoManager: LockScreenPhotoManager

    private var isPhotoDisplayActive = false
    private var usePhotoDisplay: Boolean = false

    companion object {
        private const val TAG = "MainFragment"
        private const val DEFAULT_URL = "file:///android_asset/index.html"
        private const val ERROR_PAGE = "file:///android_asset/error.html"
        private const val MIN_PREVIEW_INTERVAL = 5000L // 5 seconds between previews

        private const val KEY_PHOTO_DISPLAY_ACTIVE = "photo_display_active"
        private const val KEY_PREVIEW_ENABLED = "preview_enabled"
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

        if (photoSourceState.isScreensaverReady()) {
            setupPhotoDisplay()
            initializeDisplayMode()
            setupViews()
            observePhotoManager()
        } else {
            setupWebView(savedInstanceState)
            setupViews()
        }

        savedInstanceState?.let {
            if (it.getBoolean(KEY_PHOTO_DISPLAY_ACTIVE, false)) {
                startPreviewMode()
            }
        }
    }

    private fun observePhotoManager() {
        viewLifecycleOwner.lifecycleScope.launch {
            photoManager.loadingState.collectLatest { state ->
                when (state) {
                    LockScreenPhotoManager.LoadingState.SUCCESS -> {
                        binding.loadingIndicator.visibility = View.GONE
                        if (photoManager.getPhotoCount() > 0) {
                            updatePreviewButtonState()
                        }
                    }
                    LockScreenPhotoManager.LoadingState.LOADING -> {
                        binding.loadingIndicator.visibility = View.VISIBLE
                    }
                    LockScreenPhotoManager.LoadingState.ERROR -> {
                        binding.loadingIndicator.visibility = View.GONE
                        showError("Error loading photos")
                    }
                    LockScreenPhotoManager.LoadingState.IDLE -> {
                        binding.loadingIndicator.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setupPhotoDisplay() {
        val shouldRestartDisplay = isPhotoDisplayActive

        photoDisplayManager.initialize(
            PhotoDisplayManager.Views(
                primaryView = binding.photoPreview,
                overlayView = binding.photoPreviewOverlay,
                clockView = binding.clockOverlay,
                dateView = binding.dateOverlay,
                locationView = binding.locationOverlay,
                loadingIndicator = binding.loadingIndicator,
                loadingMessage = binding.loadingMessage,
                container = binding.screensaverContainer,
                overlayMessageContainer = binding.overlayMessageContainer,
                overlayMessageText = binding.overlayMessageText,
                backgroundLoadingIndicator = binding.backgroundLoadingIndicator
            ),
            viewLifecycleOwner.lifecycleScope
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        photoDisplayManager.updateSettings(
            transitionDuration = prefs.getInt("transition_duration", 1000).toLong(),
            photoInterval = prefs.getInt("photo_interval", 10000).toLong(),
            showClock = prefs.getBoolean("show_clock", false),
            showDate = prefs.getBoolean("show_date", false),
            showLocation = prefs.getBoolean("show_location", false),
            isRandomOrder = prefs.getBoolean("random_order", false)
        )

        if (shouldRestartDisplay) {
            binding.screensaverContainer.visibility = View.VISIBLE
            binding.webView.visibility = View.GONE
            photoDisplayManager.startPhotoDisplay()
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
        if (!isPhotoDisplayActive && photoSourceState.isScreensaverReady()) {
            isPhotoDisplayActive = true
            binding.apply {
                webView.stopLoading()
                webView.visibility = View.GONE
                screensaverContainer.visibility = View.VISIBLE
            }
            photoDisplayManager.startPhotoDisplay()
            photoSourceState.recordPreviewStarted()

            // Start service in preview mode
            Intent(requireContext(), PhotoLockScreenService::class.java).also { intent ->
                intent.action = "START_PREVIEW"
                requireContext().startService(intent)
            }
        }
    }

    private fun stopPreviewMode() {
        if (isPhotoDisplayActive) {
            stopPhotoDisplay()
            if (!photoSourceState.isScreensaverReady()) {
                binding.webView.visibility = View.VISIBLE
                setupWebView(null)
            }

            // Stop preview mode in service
            Intent(requireContext(), PhotoLockScreenService::class.java).also { intent ->
                intent.action = "STOP_PREVIEW"
                requireContext().startService(intent)
            }
        }
    }

    private fun startPhotoDisplay() {
        Log.d(TAG, "Starting photo display")
        binding.apply {
            webView.stopLoading()
            webView.visibility = View.GONE
            screensaverContainer.visibility = View.VISIBLE
        }
        photoDisplayManager.startPhotoDisplay()
        isPhotoDisplayActive = true
    }

    private fun stopPhotoDisplay() {
        Log.d(TAG, "Stopping photo display")
        photoDisplayManager.stopPhotoDisplay()
        isPhotoDisplayActive = false
        binding.screensaverContainer.visibility = View.GONE
    }

    private fun initializeDisplayMode() {
        val isReady = photoSourceState.isScreensaverReady()
        Log.d(TAG, "Initializing display mode. Photos ready: $isReady")

        if (isReady) {
            binding.webView.apply {
                stopLoading()
                clearHistory()
                visibility = View.GONE
            }
            binding.screensaverContainer.visibility = View.VISIBLE
            binding.screensaverReadyCard.visibility = View.VISIBLE
            startPhotoDisplay()
        } else {
            binding.webView.visibility = View.VISIBLE
            binding.screensaverContainer.visibility = View.GONE
            binding.screensaverReadyCard.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(savedInstanceState: Bundle?) {
        configureWebViewSettings()
        setupWebViewClient()
        setupJavaScriptInterface()

        viewLifecycleOwner.lifecycleScope.launch {
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings() {
        binding.webView.settings.apply {
            // Basic access settings
            allowFileAccess = true
            allowContentAccess = false
            javaScriptEnabled = true

            // Storage and cache settings
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT

            // Zoom control settings
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // Content handling settings
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            // Security settings - using suppressions for deprecated APIs
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            // Media settings
            mediaPlaybackRequiresUserGesture = true

            // Display settings
            useWideViewPort = true
            loadWithOverviewMode = true

            // Database and form settings
            databaseEnabled = false
            @Suppress("DEPRECATION")
            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
        }

        // Additional security settings for newer API levels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_BOUND,
                true
            )
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
                showError("Error loading page: ${error?.description}")
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
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.apply {
            putBoolean(KEY_PHOTO_DISPLAY_ACTIVE, isPhotoDisplayActive)
            _binding?.let {
                binding.webView.saveState(outState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (photoSourceState.isScreensaverReady()) {
            binding.webView.visibility = View.GONE
            if (isPhotoDisplayActive) {
                startPhotoDisplay()
            }
        } else {
            binding.webView.onResume()
        }
        updatePreviewButtonState()
    }

    override fun onPause() {
        if (isPhotoDisplayActive) {
            stopPhotoDisplay()
        }
        if (!photoSourceState.isScreensaverReady()) {
            binding.webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        photoDisplayManager.cleanup()
        super.onDestroyView()
        _binding = null
    }

    fun onBackPressed(): Boolean {
        return if (isPhotoDisplayActive) {
            stopPreviewMode()
            true
        } else {
            false
        }
    }

    fun refreshDisplay() {
        if (photoSourceState.isScreensaverReady()) {
            photoDisplayManager.startPhotoDisplay()
        } else {
            binding.webView.reload()
        }
    }
}