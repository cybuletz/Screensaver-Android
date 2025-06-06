package com.photostreamr.ads

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.photostreamr.BuildConfig
import com.photostreamr.R
import com.photostreamr.version.AppVersionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.Volatile

@Singleton
class AdManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersionManager: AppVersionManager,
    private val consentManager: ConsentManager
) {
    companion object {
        private const val TAG = "AdManager"

        // Test ad units
        private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111" // Standard test ID
        private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712" // Standard test ID
        private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110" // Standard test ID

        // Production ad units (Replace with your actual IDs if necessary)
        //private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-1825370608705808/5588599522"
        //private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-1825370608705808/2803751564"
        //private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-1825370608705808/2360210075"

        // Production ad units (Replace with your actual IDs if necessary)
        private const val PRODUCTION_BANNER_AD_UNIT_ID = "ca-app-pub-1825370608705808/5588599522"
        private const val PRODUCTION_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-1825370608705808/2803751564"
        private const val PRODUCTION_NATIVE_AD_UNIT_ID = "ca-app-pub-1825370608705808/2360210075"

        private val IS_DEBUG = BuildConfig.DEBUG

        private val BANNER_AD_UNIT_ID = if (IS_DEBUG) TEST_BANNER_AD_UNIT_ID else PRODUCTION_BANNER_AD_UNIT_ID
        private val INTERSTITIAL_AD_UNIT_ID = if (IS_DEBUG) TEST_INTERSTITIAL_AD_UNIT_ID else PRODUCTION_INTERSTITIAL_AD_UNIT_ID
        private val NATIVE_AD_UNIT_ID = if (IS_DEBUG) TEST_NATIVE_AD_UNIT_ID else PRODUCTION_NATIVE_AD_UNIT_ID

        private const val INTERSTITIAL_AD_DISPLAY_DURATION = 10000L // 10 seconds
        private const val MINIMUM_AD_INTERVAL = 180000L // 3 minutes minimum between ads
        private const val BANNER_REFRESH_INTERVAL = 600000L // 10 minutes in milliseconds
        private const val MIN_NATIVE_AD_FREQUENCY = 25
        private const val MAX_NATIVE_AD_FREQUENCY = 35
        private const val DEFAULT_NATIVE_AD_FREQUENCY = 30
        private const val NATIVE_AD_CACHE_SIZE = 3 // Increased from 1 to 3
        private const val AD_LOAD_TIMEOUT_DURATION = 10000L // 10 seconds
        private const val MIN_AD_REQUEST_INTERVAL = 180000L // 3 minutes minimum between ad requests
    }
    private val random = java.util.Random()

    private var photoCount = 0
    private var photosUntilNextAd = getRandomAdFrequency()

    private val autoRestartHandler = Handler(Looper.getMainLooper())
    private var autoRestartRunnable: Runnable? = null
    private val AUTO_RESTART_INTERVAL = 1 * 60 * 60 * 1000L // 2 hours

    private var lastAdRequestTime = 0L
    private var retryCount = 0
    private val MAX_RETRIES = 3
    private val pendingAdRequests = AtomicInteger(0)
    private val MAX_CONCURRENT_REQUESTS = 1

    @Volatile private var isInitialized = false // Standard volatile boolean is fine here
    private var mainAdView: AdView? = null
    private var settingsAdView: AdView? = null
    private var interstitialAd: InterstitialAd? = null
    private var timerRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var fullScreenInterstitialAd: InterstitialAd? = null
    // --- Use AtomicBoolean correctly ---
    private val isFullScreenInterstitialLoading = AtomicBoolean(false)
    private var lastFullScreenInterstitialTime = 0L
    private val FULL_SCREEN_INTERSTITIAL_INTERVAL = 600000L // 10 minutes
    private val FULL_SCREEN_INTERSTITIAL_DISPLAY_DURATION = 20000L // 20 seconds

    private var bannerRefreshRunnable: Runnable? = null

    private var lastInterstitialAdTime = 0L
    // --- Use AtomicBoolean correctly ---
    private val isInterstitialShowing = AtomicBoolean(false)
    private val isLoadingInterstitial = AtomicBoolean(false)

    private var showInterstitialInSettings = true

    private val nativeAdsCache = ConcurrentLinkedQueue<NativeAd>()
    // --- Use AtomicBoolean correctly ---
    private val isLoadingNativeAd = AtomicBoolean(false)
    private val nativeAdLoadScope = CoroutineScope(Dispatchers.IO)

    @Volatile private var currentNativeAdLoadCallback: ((NativeAd?) -> Unit)? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private var consentObserver: Observer<ConsentManager.ConsentState>? = null

    fun setupConsentObserver(lifecycleOwner: LifecycleOwner) {
        consentObserver?.let {
            consentManager.consentState.removeObserver(it)
        }

        consentObserver = Observer<ConsentManager.ConsentState> { state ->
            Log.d(TAG, "Consent state changed to: $state")
            when (state) {
                ConsentManager.ConsentState.OBTAINED,
                ConsentManager.ConsentState.NOT_REQUIRED -> {
                    if (!isInitialized) {
                        initializeAds()
                    }
                }
                ConsentManager.ConsentState.REQUIRED -> {
                    pauseAds()
                }
                ConsentManager.ConsentState.ERROR -> {
                    Log.e(TAG, "Error with consent, proceeding with non-personalized ads")
                    if (!isInitialized) {
                        initializeAds()
                    }
                }
                else -> {
                    Log.d(TAG, "Consent in unknown state, waiting")
                }
            }
        }
        consentManager.consentState.observe(lifecycleOwner, consentObserver!!)
    }

    fun initialize() {
        Log.d(TAG, "AdManager initialize called")
        if (isInitialized) {
            Log.d(TAG, "AdManager already initialized")
            return
        }
        if (consentManager.canShowAds()) {
            Log.d(TAG, "Consent already obtained or not required, initializing ads")
            initializeAds()
        } else {
            Log.d(TAG, "Waiting for consent before initializing ads")
        }
    }

    private fun initializeAds() {
        if (isInitialized) return
        try {
            Log.d(TAG, "Initializing Mobile Ads SDK")
            MobileAds.initialize(context) { initializationStatus ->
                Log.d(TAG, "Mobile Ads initialized: $initializationStatus")
                isInitialized = true
                preloadNativeAds() // Preload after initialization
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AdManager", e)
        }
    }

    fun getInterstitialAdUnitId(): String {
        return INTERSTITIAL_AD_UNIT_ID
    }

    private fun getRandomAdFrequency(): Int {
        return try {
            if (MIN_NATIVE_AD_FREQUENCY >= MAX_NATIVE_AD_FREQUENCY + 1) {
                DEFAULT_NATIVE_AD_FREQUENCY
            } else {
                random.nextInt(MAX_NATIVE_AD_FREQUENCY - MIN_NATIVE_AD_FREQUENCY + 1) + MIN_NATIVE_AD_FREQUENCY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating random ad frequency", e)
            DEFAULT_NATIVE_AD_FREQUENCY
        }
    }

    /**
     * Disables hardware acceleration for ad views on Android versions up to and including Android 11
     * to prevent GPU-related crashes with native ads.
     * Also disables drawing cache on Android 8 to prevent NullPointerException in Bitmap.mDensity.
     */
    fun ensureSafeHardwareAcceleration(view: View) {
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) { // R = Android 11
                // Disable hardware acceleration for Android 11 and below
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                // Drawing cache bug fix for Android 8
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
                    view.setDrawingCacheEnabled(false)

                    // Apply the fix to all child views if this is a ViewGroup
                    if (view is ViewGroup) {
                        disableDrawingCacheRecursively(view)
                    }

                    Log.d(TAG, "Applied Android 8 drawing cache fix to prevent NPE")
                }

                Log.d(TAG, "Using software rendering for ads on Android ${Build.VERSION.SDK_INT}")
            } else {
                // For newer versions, use hardware acceleration
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                Log.d(TAG, "Using hardware rendering for ads on Android ${Build.VERSION.SDK_INT}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting layer type", e)
        }
    }

    /**
     * Completely disables drawing cache throughout the entire view hierarchy
     * This is critical for preventing the NullPointerException in Bitmap.mDensity on Android 8
     */
    @Suppress("DEPRECATION")
    private fun disableDrawingCacheRecursively(viewGroup: ViewGroup) {
        // Disable on the parent
        viewGroup.setDrawingCacheEnabled(false)

        // Process all children
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child != null) {
                child.setDrawingCacheEnabled(false)

                // Force quality to LOW to prevent cache creation
                try {
                    child.drawingCacheQuality = View.DRAWING_CACHE_QUALITY_LOW
                } catch (e: Exception) {
                    // Ignore, just an extra precaution
                }

                // Recursively process any ViewGroups
                if (child is ViewGroup) {
                    disableDrawingCacheRecursively(child)
                }
            }
        }
    }

    fun getNativeAdView(activity: Activity, nativeAd: NativeAd): View {
        val inflater = LayoutInflater.from(activity)

        try {
            // Inflate the layout
            val rootView = inflater.inflate(R.layout.native_ad_layout, null) as ViewGroup

            // Apply hardware acceleration fixes for Android 8-11
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) { // Android 8 (Oreo) to 11 (R)

                // Force software rendering for entire view hierarchy
                rootView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                // Apply to all child views recursively
                applyLayerTypeRecursively(rootView, View.LAYER_TYPE_SOFTWARE)

                Log.d(TAG, "Applied Android ${Build.VERSION.SDK_INT} SIGSEGV fix: Disabled hardware acceleration for entire ad view hierarchy")

                // Android 8 specific fixes for drawing cache
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
                    safelyDisableDrawingCacheForEntireHierarchy(rootView)
                    Log.d(TAG, "Applied Android 8 specific drawing cache fixes")
                }
            } else {
                // For other versions, use standard handling
                ensureSafeHardwareAcceleration(rootView)
            }

            val adView = rootView.findViewById<NativeAdView>(R.id.native_ad_view)

            // Special handling for MediaView on problematic Android versions
            val mediaView = adView.findViewById<MediaView>(R.id.ad_media)
            if (mediaView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {

                // First set layer type to software
                mediaView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                // Post media content setup to ensure rendering happens after layout
                mediaView.post {
                    try {
                        // Set scale type before media content
                        mediaView.setImageScaleType(ImageView.ScaleType.CENTER_CROP)

                        // Brief delay before setting media content to avoid immediate rendering crash
                        mediaView.postDelayed({
                            try {
                                if (nativeAd.mediaContent != null) {
                                    // Safe setting of media content
                                    try {
                                        mediaView.mediaContent = nativeAd.mediaContent
                                        Log.d(TAG, "Successfully set media content with Android ${Build.VERSION.SDK_INT} safety measures")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error setting media content directly, trying alternate approach", e)

                                        // Second attempt - delay inside another post
                                        mediaView.post {
                                            try {
                                                mediaView.mediaContent = nativeAd.mediaContent
                                            } catch (e2: Exception) {
                                                Log.e(TAG, "Failed to set media content in post call", e2)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error setting delayed media content", e)
                            }
                        }, 50)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in MediaView setup", e)
                    }
                }
            }

            // Populate ad view content
            populateNativeAdView(nativeAd, adView)
            return rootView
        } catch (e: Exception) {
            Log.e(TAG, "Error in getNativeAdView", e)
            // Return a minimal fallback view
            val fallbackView = NativeAdView(activity)
            fallbackView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            return fallbackView
        }
    }

    /**
     * Recursively applies the specified layer type to a view and all its children
     */
    private fun applyLayerTypeRecursively(view: View, layerType: Int) {
        try {
            // Apply to this view
            view.setLayerType(layerType, null)

            // Apply to children if this is a ViewGroup
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child != null) {
                        applyLayerTypeRecursively(child, layerType)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying layer type recursively", e)
        }
    }

    /**
     * Safely disables drawing cache for an entire view hierarchy
     * Specifically modified for Android 8 to prevent SIGSEGV
     */
    @Suppress("DEPRECATION")
    private fun safelyDisableDrawingCacheForEntireHierarchy(view: View) {
        try {
            // Apply to this view
            view.setDrawingCacheEnabled(false)

            // Apply special handling for MediaView
            if (view.javaClass.name.contains("MediaView")) {
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                // Force immediate layout update to avoid deferred rendering
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY)
                )
                view.layout(view.left, view.top, view.right, view.bottom)
                view.invalidate()

                // Additional handling for WebView content inside MediaView
                try {
                    for (field in view.javaClass.declaredFields) {
                        if (field.type.name.contains("WebView")) {
                            field.isAccessible = true
                            val webView = field.get(view)
                            if (webView != null && webView is View) {
                                (webView as View).setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore reflection errors
                }
            }

            // Recursively process children
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child != null) {
                        safelyDisableDrawingCacheForEntireHierarchy(child)
                    }
                }
            }
        } catch (e: Exception) {
            // Don't let exceptions propagate
            Log.e(TAG, "Error in safelyDisableDrawingCacheForEntireHierarchy", e)
        }
    }

    /**
     * Populates a NativeAdView with data from a NativeAd
     * Safely handles media content to prevent GPU-related crashes on Android 11 and below
     */
    fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        try {
            val headlineView = adView.findViewById<TextView>(R.id.ad_headline)
            headlineView.text = nativeAd.headline
            adView.headlineView = headlineView

            val bodyView = adView.findViewById<TextView>(R.id.ad_body)
            if (nativeAd.body != null) {
                bodyView.text = nativeAd.body
                bodyView.visibility = View.VISIBLE
            } else {
                bodyView.visibility = View.GONE
            }
            adView.bodyView = bodyView

            val iconView = adView.findViewById<ImageView>(R.id.ad_app_icon)
            val icon = nativeAd.icon
            if (icon != null) {
                iconView.setImageDrawable(icon.drawable)
                iconView.visibility = View.VISIBLE
            } else {
                iconView.visibility = View.GONE
            }
            adView.iconView = iconView

            val starRatingView = adView.findViewById<RatingBar>(R.id.ad_stars)
            if (nativeAd.starRating != null) {
                starRatingView.rating = nativeAd.starRating!!.toFloat()
                starRatingView.visibility = View.VISIBLE
            } else {
                starRatingView.visibility = View.GONE
            }
            adView.starRatingView = starRatingView

            val advertiserView = adView.findViewById<TextView>(R.id.ad_advertiser)
            if (nativeAd.advertiser != null) {
                advertiserView.text = nativeAd.advertiser
                advertiserView.visibility = View.VISIBLE
            } else {
                advertiserView.visibility = View.GONE
            }
            adView.advertiserView = advertiserView

            val priceView = adView.findViewById<TextView>(R.id.ad_price)
            if (nativeAd.price != null) {
                priceView.text = nativeAd.price
                priceView.visibility = View.VISIBLE
            } else {
                priceView.visibility = View.GONE
            }
            adView.priceView = priceView

            val storeView = adView.findViewById<TextView>(R.id.ad_store)
            if (nativeAd.store != null) {
                storeView.text = nativeAd.store
                storeView.visibility = View.VISIBLE
            } else {
                storeView.visibility = View.GONE
            }
            adView.storeView = storeView

            val callToActionView = adView.findViewById<Button>(R.id.ad_call_to_action)
            if (nativeAd.callToAction != null) {
                callToActionView.text = nativeAd.callToAction
                callToActionView.visibility = View.VISIBLE
            } else {
                callToActionView.visibility = View.GONE
            }
            adView.callToActionView = callToActionView

            // MODIFIED: Handle MediaView safely - use software rendering but don't hide
            val mediaView = adView.findViewById<MediaView>(R.id.ad_media)
            if (mediaView != null) {
                try {
                    // Force software rendering for MediaView on Android 11 and below
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                        mediaView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                        // CRITICAL: Disable drawing cache for MediaView on Android 8
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
                            mediaView.setDrawingCacheEnabled(false)

                            // Force layout to update internal state before any drawing happens
                            mediaView.measure(
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                            )
                            mediaView.layout(0, 0, mediaView.measuredWidth, mediaView.measuredHeight)
                        }

                        Log.d(TAG, "Set MediaView to software rendering on Android ${Build.VERSION.SDK_INT}")
                    }

                    // Set the media view and content for all Android versions
                    adView.mediaView = mediaView
                    mediaView.visibility = View.VISIBLE

                    // Try to set the media content
                    if (nativeAd.mediaContent != null) {
                        try {
                            // IMPORTANT: Wrap this in a try-catch as it's often the trigger for the crash
                            mediaView.setImageScaleType(ImageView.ScaleType.CENTER_CROP)
                            mediaView.mediaContent = nativeAd.mediaContent
                            Log.d(TAG, "MediaView content set successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error setting media content", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up MediaView, but keeping it visible", e)
                }
            } else {
                Log.e(TAG, "MediaView not found in layout")
            }

            adView.setNativeAd(nativeAd)
            Log.d(TAG, "Native ad view populated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error populating native ad view", e)
        }
    }

    fun updatePhotoCount(count: Int) {
        photoCount = count
    }

    fun shouldShowNativeAd(): Boolean {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) {
            return false
        }

        photosUntilNextAd--
        Log.d(TAG, "Photos until next ad: $photosUntilNextAd")

        if (photosUntilNextAd <= 0) {
            photosUntilNextAd = getRandomAdFrequency()

            // Check if we have a cached ad before showing
            if (nativeAdsCache.isEmpty() && System.currentTimeMillis() - lastAdRequestTime < MIN_AD_REQUEST_INTERVAL) {
                // If we don't have a cached ad and we've requested too recently, wait longer
                Log.d(TAG, "Ad threshold reached but no cached ads available and rate limited. Delaying ad show.")
                photosUntilNextAd = 5 // Show after a few more photos instead
                return false
            }

            Log.d(TAG, "Native ad threshold reached. Next ad after $photosUntilNextAd photos")
            return true
        }
        return false
    }

    private fun preloadNativeAds() {
        // --- Use .get() ---
        if (appVersionManager.isProVersion() || isLoadingNativeAd.get() || !consentManager.canShowAds()) {
            return
        }

        val cacheSize = nativeAdsCache.size
        if (cacheSize >= NATIVE_AD_CACHE_SIZE) {
            return
        }

        val numAdsToLoad = NATIVE_AD_CACHE_SIZE - cacheSize
        if (numAdsToLoad > 0) {
            Log.d(TAG, "Preloading $numAdsToLoad native ads")
            for (i in 0 until numAdsToLoad) {
                loadNativeAdInternal(null)
            }
        }
    }

    fun loadNativeAd(callback: ((NativeAd?) -> Unit)?) {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) {
            callback?.invoke(null)
            return
        }

        val cachedAd = nativeAdsCache.poll()
        if (cachedAd != null) {
            Log.d(TAG, "Serving native ad from cache.")
            callback?.invoke(cachedAd)
            preloadNativeAdsIfNeeded()
            return
        }

        Log.d(TAG, "Native ad cache empty, loading new ad for request.")
        loadNativeAdInternal(callback)
    }

    private fun loadNativeAdInternal(callback: ((NativeAd?) -> Unit)?) {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) {
            Log.d(TAG, "Skipping native ad load: Pro version or no consent.")
            callback?.invoke(null)
            return
        }

        // Rate limiting check
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAdRequestTime < MIN_AD_REQUEST_INTERVAL) {
            Log.d(TAG, "Rate limiting ad request (too frequent), using cache instead")
            callback?.invoke(nativeAdsCache.poll())
            return
        }

        if (!isInitialized) {
            Log.w(TAG, "AdManager not initialized, attempting init and retry.")
            initialize()
            mainHandler.postDelayed({ loadNativeAdInternal(callback) }, 1000)
            return
        }

        // Concurrent requests limit
        if (pendingAdRequests.get() >= MAX_CONCURRENT_REQUESTS) {
            Log.d(TAG, "Too many concurrent ad requests, using cache or skipping")
            callback?.invoke(nativeAdsCache.poll())
            return
        }

        // --- Use compareAndSet ---
        if (!isLoadingNativeAd.compareAndSet(false, true)) {
            Log.w(TAG, "Already loading native ad, ignoring new request.")
            callback?.invoke(null)
            return
        }

        Log.d(TAG, "Starting native ad load process.")
        lastAdRequestTime = currentTime
        pendingAdRequests.incrementAndGet()
        currentNativeAdLoadCallback = callback

        val timeoutRunnable = Runnable {
            // --- Use .get() and compareAndSet ---
            if (isLoadingNativeAd.get()) {
                Log.w(TAG, "Native ad loading timed out after ${AD_LOAD_TIMEOUT_DURATION / 1000} seconds.")
                if (isLoadingNativeAd.compareAndSet(true, false)) {
                    val timedOutCallback = currentNativeAdLoadCallback
                    currentNativeAdLoadCallback = null
                    timedOutCallback?.invoke(null)
                    pendingAdRequests.decrementAndGet()
                } else {
                    Log.d(TAG, "Timeout triggered, but loading state was already reset.")
                }
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, AD_LOAD_TIMEOUT_DURATION)

        val adRequest = createAdRequest()
        val adLoader = AdLoader.Builder(context, NATIVE_AD_UNIT_ID)
            .forNativeAd { nativeAd ->
                Log.d(TAG, "Native ad loaded successfully.")
                timeoutHandler.removeCallbacks(timeoutRunnable)
                pendingAdRequests.decrementAndGet()

                // Reset retry count on success
                retryCount = 0

                // --- Use compareAndSet ---
                if (isLoadingNativeAd.compareAndSet(true, false)) {
                    val successCallback = currentNativeAdLoadCallback
                    currentNativeAdLoadCallback = null

                    if (successCallback != null) {
                        successCallback.invoke(nativeAd)
                    } else { // Preload
                        if (nativeAdsCache.size < NATIVE_AD_CACHE_SIZE) {
                            nativeAdsCache.offer(nativeAd)
                            Log.d(TAG, "Added preloaded native ad to cache (size: ${nativeAdsCache.size})")
                        } else {
                            Log.d(TAG, "Native ad cache full on preload success, destroying ad.")
                            nativeAd.destroy()
                        }
                    }
                    preloadNativeAdsIfNeeded()
                } else {
                    Log.w(TAG, "Native ad loaded, but state already reset. Discarding ad.")
                    nativeAd.destroy()
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e(TAG, "Native ad failed to load: ${error.message}, code: ${error.code}, domain: ${error.domain}")
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    pendingAdRequests.decrementAndGet()

                    // --- Use compareAndSet ---
                    if (isLoadingNativeAd.compareAndSet(true, false)) {
                        val failureCallback = currentNativeAdLoadCallback
                        currentNativeAdLoadCallback = null
                        failureCallback?.invoke(null)
                        schedulePreloadRetry()
                    } else {
                        Log.d(TAG, "Native ad failed, but state already reset.")
                    }
                }
                override fun onAdClicked() { Log.d(TAG, "Native ad clicked.") }
                override fun onAdImpression() { Log.d(TAG, "Native ad impression.") }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        Log.d(TAG, "Requesting AdLoader to load ad.")
        try {
            adLoader.loadAd(adRequest)
        } catch (e: Exception) {
            Log.e(TAG, "Exception when calling adLoader.loadAd", e)
            // --- Use .set() ---
            isLoadingNativeAd.set(false)
            pendingAdRequests.decrementAndGet()
            timeoutHandler.removeCallbacks(timeoutRunnable)
            val failureCallback = currentNativeAdLoadCallback
            currentNativeAdLoadCallback = null
            failureCallback?.invoke(null)
            schedulePreloadRetry()
        }
    }

    private fun schedulePreloadRetry() {
        if (retryCount < MAX_RETRIES) {
            retryCount++
            val delayTime = 60000L * retryCount // Increasing delay with each retry
            Log.d(TAG, "Scheduling preload retry #$retryCount in ${delayTime/1000} seconds")

            mainHandler.postDelayed({
                preloadNativeAdsIfNeeded()
            }, delayTime)
        } else {
            Log.d(TAG, "Max retries ($MAX_RETRIES) reached, scheduling longer delay")
            retryCount = 0 // Reset after a longer timeout
            mainHandler.postDelayed({
                preloadNativeAdsIfNeeded()
            }, 300000) // 5 minute timeout after max retries
        }
    }

    private fun preloadNativeAdsIfNeeded() {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) return

        // Rate limiting check
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAdRequestTime < MIN_AD_REQUEST_INTERVAL) {
            Log.d(TAG, "Rate limiting preload (too frequent), will try later")
            return
        }

        val cacheSize = nativeAdsCache.size
        // --- Use .get() ---
        if (cacheSize < NATIVE_AD_CACHE_SIZE && !isLoadingNativeAd.get() && pendingAdRequests.get() < MAX_CONCURRENT_REQUESTS) {
            val numToLoad = Math.min(NATIVE_AD_CACHE_SIZE - cacheSize, MAX_CONCURRENT_REQUESTS)
            Log.d(TAG, "Cache low ($cacheSize/$NATIVE_AD_CACHE_SIZE), preloading $numToLoad more native ads.")

            // Only preload one ad at a time to avoid flooding
            loadNativeAdInternal(null)
        }
    }

    fun getNativeAdForSlideshow(activity: Activity, callback: (NativeAd?) -> Unit) {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) {
            callback(null)
            return
        }
        val cachedAd = nativeAdsCache.poll()
        if (cachedAd != null) {
            callback(cachedAd)
            preloadNativeAdsIfNeeded()
            return
        }
        loadNativeAd(callback)
    }

    fun loadAndShowFullScreenInterstitial(activity: Activity) {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFullScreenInterstitialTime < FULL_SCREEN_INTERSTITIAL_INTERVAL) {
            Log.d(TAG, "Full screen interstitial interval not elapsed.")
            return
        }

        // --- Use .get() and compareAndSet ---
        if (isFullScreenInterstitialLoading.get() || fullScreenInterstitialAd != null) {
            Log.d(TAG, "Interstitial already loaded or loading.")
            if (fullScreenInterstitialAd != null) showFullScreenInterstitial(activity)
            return
        }
        // --- Use compareAndSet ---
        if (!isFullScreenInterstitialLoading.compareAndSet(false, true)) {
            Log.w(TAG, "Interstitial load already in progress (race condition).")
            return // Avoid starting another load
        }


        Log.d(TAG, "Loading full screen interstitial ad.")
        lastFullScreenInterstitialTime = currentTime

        val adRequest = createAdRequest()
        InterstitialAd.load(context, INTERSTITIAL_AD_UNIT_ID, adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Full screen interstitial loaded.")
                    // --- Use .set() ---
                    isFullScreenInterstitialLoading.set(false)
                    fullScreenInterstitialAd = ad
                    showFullScreenInterstitial(activity)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e(TAG, "Full screen interstitial failed to load: ${error.message}")
                    // --- Use .set() ---
                    isFullScreenInterstitialLoading.set(false)
                    fullScreenInterstitialAd = null
                }
            })
    }

    private fun showFullScreenInterstitial(activity: Activity) {
        val ad = fullScreenInterstitialAd
        // --- Use .get() ---
        if (ad == null || isInterstitialShowing.get()) {
            Log.d(TAG, "Interstitial not ready or already showing.")
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Full screen interstitial dismissed.")
                fullScreenInterstitialAd = null
                // --- Use .set() ---
                isInterstitialShowing.set(false)
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.e(TAG, "Full screen interstitial failed to show: ${error.message}")
                fullScreenInterstitialAd = null
                // --- Use .set() ---
                isInterstitialShowing.set(false)
            }
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Full screen interstitial showed.")
                // --- Use .set() ---
                isInterstitialShowing.set(true)
                fullScreenInterstitialAd = null
            }
        }
        try {
            // --- Use .get() ---
            if (!isInterstitialShowing.get()) {
                ad.show(activity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception when showing interstitial ad", e)
            fullScreenInterstitialAd = null
            // --- Use .set() ---
            isInterstitialShowing.set(false)
        }
    }

    fun checkAndShowFullScreenInterstitial(activity: Activity) {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFullScreenInterstitialTime >= FULL_SCREEN_INTERSTITIAL_INTERVAL) {
            loadAndShowFullScreenInterstitial(activity)
        }
    }

    fun preloadInterstitialForSettings() {
        // --- Use .get() ---
        if (appVersionManager.isProVersion() || isLoadingInterstitial.get() || !consentManager.canShowAds()) {
            return
        }

        showInterstitialInSettings = true
        // --- Use .set() ---
        isLoadingInterstitial.set(true)

        Log.d(TAG, "Preloading interstitial ad for settings.")
        val adRequest = createAdRequest()
        InterstitialAd.load(context, INTERSTITIAL_AD_UNIT_ID, adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Interstitial ad for settings loaded.")
                    // --- Use .set() ---
                    isLoadingInterstitial.set(false)
                    interstitialAd = ad
                    interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            Log.d(TAG, "Settings interstitial dismissed.")
                            interstitialAd = null
                            // --- Use .set() ---
                            isInterstitialShowing.set(false)
                            showInterstitialInSettings = false
                        }
                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            Log.e(TAG, "Settings interstitial failed to show: ${adError.message}")
                            interstitialAd = null
                            // --- Use .set() ---
                            isInterstitialShowing.set(false)
                            showInterstitialInSettings = false
                        }
                        override fun onAdShowedFullScreenContent() {
                            Log.d(TAG, "Settings interstitial showed.")
                            lastInterstitialAdTime = System.currentTimeMillis()
                            // --- Use .set() ---
                            isInterstitialShowing.set(true)
                            interstitialAd = null
                        }
                    }
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Interstitial ad for settings failed to load: ${adError.message}")
                    // --- Use .set() ---
                    isLoadingInterstitial.set(false)
                    interstitialAd = null
                    showInterstitialInSettings = false
                }
            })
    }

    fun setupMainActivityAd(container: FrameLayout?) {
        if (appVersionManager.isProVersion() || container == null || !consentManager.canShowAds()) {
            Log.d(TAG, "Not setting up main activity ad (Pro/No Container/No Consent).")
            container?.visibility = View.GONE
            return
        }
        Log.d(TAG, "Skipping banner ad setup in MainActivity.")
        container.removeAllViews()
        container.visibility = View.GONE
        return
    }

    private fun setupBannerRefreshTimer() {
        bannerRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        bannerRefreshRunnable = object : Runnable {
            override fun run() {
                if (!appVersionManager.isProVersion() && consentManager.canShowAds() && mainAdView != null) {
                    Log.d(TAG, "Refreshing banner ad.")
                    loadMainActivityAd()
                }
                bannerRefreshRunnable?.let {
                    mainHandler.postDelayed(it, BANNER_REFRESH_INTERVAL)
                }
            }
        }
        mainHandler.postDelayed(bannerRefreshRunnable!!, BANNER_REFRESH_INTERVAL)
    }

    private fun getAdSizeForContainer(container: FrameLayout): AdSize {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val outMetrics = DisplayMetrics()
            display.getMetrics(outMetrics)

            val density = outMetrics.density
            var adWidthPixels = container.width.toFloat()
            if (adWidthPixels <= 0f) {
                adWidthPixels = outMetrics.widthPixels.toFloat()
            }
            val adWidth = (adWidthPixels / density).toInt()
            if (adWidth <= 0) return AdSize.BANNER

            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting adaptive banner size", e)
            return AdSize.BANNER
        }
    }

    fun setupSettingsFragmentAd(container: FrameLayout) {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) {
            Log.d(TAG, "Not setting up settings ad (Pro/No Consent).")
            container.visibility = View.GONE
            return
        }
        try {
            if (!isInitialized) {
                initialize()
                container.visibility = View.GONE
                return
            }
            val adSize = getAdSizeForContainer(container)
            settingsAdView = AdView(context).apply {
                setAdUnitId(BANNER_AD_UNIT_ID)
                setAdSize(adSize)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        Log.d(TAG, "Settings fragment ad loaded")
                        container.post { container.visibility = ViewGroup.VISIBLE }
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e(TAG, "Settings fragment ad failed: ${error.message}")
                        container.visibility = ViewGroup.GONE
                    }
                }
            }
            container.removeAllViews()
            container.addView(settingsAdView)
            container.visibility = ViewGroup.GONE
            loadSettingsAd()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up settings fragment ad", e)
            container.visibility = ViewGroup.GONE
        }
    }

    fun loadMainActivityAd() {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds() || mainAdView == null) return
        mainAdView?.loadAd(createAdRequest())
    }

    fun loadSettingsAd() {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds() || settingsAdView == null) return
        settingsAdView?.loadAd(createAdRequest())
    }

    fun pauseAds() {
        mainAdView?.pause()
        settingsAdView?.pause()
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        bannerRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        Log.d(TAG, "Ads paused.")
    }

    fun resumeAds() {
        if (appVersionManager.isProVersion() || !consentManager.canShowAds()) return
        mainAdView?.resume()
        settingsAdView?.resume()
        timerRunnable?.let { mainHandler.post(it) }
        bannerRefreshRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            mainHandler.postDelayed(runnable, BANNER_REFRESH_INTERVAL)
        }
        Log.d(TAG, "Ads resumed.")
    }

    fun removeAllHandlers() {
        mainHandler.removeCallbacksAndMessages(null)
        autoRestartHandler.removeCallbacksAndMessages(null)
        timeoutHandler.removeCallbacksAndMessages(null)
    }

    fun destroyAds() {
        // Log memory BEFORE ad cleanup
        val runtime = Runtime.getRuntime()
        val javaHeapBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024).toFloat()
        val nativeHeapBefore = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024).toFloat()
        Log.i(TAG, "Memory BEFORE destroyAds: Java heap: %.2f MB, Native heap: %.2f MB".format(javaHeapBefore, nativeHeapBefore))

        mainAdView?.destroy()
        mainAdView = null
        settingsAdView?.destroy()
        settingsAdView = null
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        bannerRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        interstitialAd?.fullScreenContentCallback = null
        interstitialAd = null
        fullScreenInterstitialAd?.fullScreenContentCallback = null
        fullScreenInterstitialAd = null

        // Destroy all native ads and clear cache
        nativeAdsCache.forEach {
            it.destroy()
        }
        nativeAdsCache.clear()

        // Remove all handler callbacks to prevent leaks
        mainHandler.removeCallbacksAndMessages(null)
        autoRestartHandler.removeCallbacksAndMessages(null)
        timeoutHandler.removeCallbacksAndMessages(null)

        stopAutomaticReinitializer()
        isInitialized = false


        // Log memory AFTER ad cleanup
        val javaHeapAfter = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024).toFloat()
        val nativeHeapAfter = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024).toFloat()
        Log.i(TAG, "Memory AFTER destroyAds: Java heap: %.2f MB, Native heap: %.2f MB".format(javaHeapAfter, nativeHeapAfter))
    }

    private fun createAdRequest(): AdRequest {
        val builder = AdRequest.Builder()
        if (!consentManager.canShowPersonalizedAds()) {
            val extras = Bundle()
            extras.putString("npa", "1")
            builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
        }
        return builder.build()
    }

    object NativeAdSettings {
        const val MIN_FREQUENCY = MIN_NATIVE_AD_FREQUENCY
        const val MAX_FREQUENCY = MAX_NATIVE_AD_FREQUENCY
    }

    fun startAutomaticReinitializer() {
        Log.d(TAG, "Starting automatic ad system reinitialization timer.")
        stopAutomaticReinitializer()
        autoRestartRunnable = object : Runnable {
            override fun run() {
                Log.w(TAG, "Performing scheduled automatic ad system restart...")
                reinitializeAdSystem()
                autoRestartRunnable?.let {
                    autoRestartHandler.postDelayed(it, AUTO_RESTART_INTERVAL)
                }
            }
        }
        autoRestartHandler.postDelayed(autoRestartRunnable!!, AUTO_RESTART_INTERVAL)
    }

    fun stopAutomaticReinitializer() {
        autoRestartRunnable?.let {
            autoRestartHandler.removeCallbacks(it)
            autoRestartRunnable = null
            Log.d(TAG, "Stopped automatic ad system reinitialization timer.")
        }
    }

    fun reinitializeAdSystem() {
        Log.w(TAG, "Reinitializing ad system...")
        destroyAds()
        timeoutHandler.removeCallbacksAndMessages(null)

        // --- Use .set() ---
        isLoadingNativeAd.set(false)
        isLoadingInterstitial.set(false)
        isFullScreenInterstitialLoading.set(false)
        isInterstitialShowing.set(false)

        currentNativeAdLoadCallback = null

        var destroyedCount = 0
        while (nativeAdsCache.poll()?.also { it.destroy() } != null) {
            destroyedCount++
        }
        if (destroyedCount > 0) Log.d(TAG, "Destroyed $destroyedCount cached native ads.")

        interstitialAd?.fullScreenContentCallback = null
        interstitialAd = null
        fullScreenInterstitialAd?.fullScreenContentCallback = null
        fullScreenInterstitialAd = null
        Log.d(TAG, "Cleared interstitial ad references.")

        if (consentManager.canShowAds()) {
            Log.d(TAG, "Re-triggering Mobile Ads SDK initialization.")
            MobileAds.initialize(context) { status ->
                Log.d(TAG, "Mobile Ads re-initialized after reset: $status")
                isInitialized = true
                preloadNativeAdsIfNeeded()
            }
        } else {
            Log.d(TAG, "Consent not granted, skipping Mobile Ads re-initialization.")
            isInitialized = false
        }
        Log.w(TAG, "Ad system reinitialization complete.")
    }
}
