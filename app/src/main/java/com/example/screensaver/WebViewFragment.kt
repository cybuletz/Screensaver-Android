class WebViewFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var photosManager: GooglePhotosManager
    private var isLoading = false

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

        // Initialize photos manager on a background thread
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                initializePhotosManager()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize photos manager", e)
                withContext(Dispatchers.Main) {
                    showError("Failed to initialize photos: ${e.message}")
                }
            }
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            domStorageEnabled = true
            allowFileAccess = true
            // Add hardware acceleration
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
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

            photosManager = GooglePhotosManager(requireContext())
            photosManager.initialize(account)
            loadRandomPhoto()
        }
    }

    private suspend fun loadRandomPhoto() {
        if (isLoading) return

        withContext(Dispatchers.IO) {
            try {
                val photos = photosManager.getRandomPhotos(1)
                if (photos.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        webView.loadUrl(photos.first())
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showError("No photos available")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                withContext(Dispatchers.Main) {
                    showError("Failed to load photos: ${e.message}")
                }
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