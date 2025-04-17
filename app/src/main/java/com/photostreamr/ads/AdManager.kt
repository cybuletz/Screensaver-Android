package com.photostreamr.ads

import android.app.Activity
import android.content.Context
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
import com.google.android.gms.ads.AdActivity
import com.photostreamr.R
import com.photostreamr.version.AppVersionManager
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentLinkedQueue

@Singleton
class AdManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersionManager: AppVersionManager
) {
    companion object {
        private const val TAG = "AdManager"

        // Test ad units for development
        private const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
        private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        private const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

        // Production ad units - replace with your actual ad unit IDs
        private const val PRODUCTION_BANNER_AD_UNIT_ID = "ca-app-pub-1825370608705808/5588599522"
        private const val PRODUCTION_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-1825370608705808/2803751564"
        private const val PRODUCTION_NATIVE_AD_UNIT_ID = "ca-app-pub-1825370608705808/2360210075"

        // Auto-detect whether we're in debug or release mode
        private val IS_DEBUG = BuildConfig.DEBUG

        // Use appropriate ad unit IDs based on build type
        private val BANNER_AD_UNIT_ID = if (IS_DEBUG) TEST_BANNER_AD_UNIT_ID else PRODUCTION_BANNER_AD_UNIT_ID
        private val INTERSTITIAL_AD_UNIT_ID = if (IS_DEBUG) TEST_INTERSTITIAL_AD_UNIT_ID else PRODUCTION_INTERSTITIAL_AD_UNIT_ID
        private val NATIVE_AD_UNIT_ID = if (IS_DEBUG) TEST_NATIVE_AD_UNIT_ID else PRODUCTION_NATIVE_AD_UNIT_ID

        // Configure ad display timing (in milliseconds)
        private const val INTERSTITIAL_AD_DISPLAY_DURATION = 10000L // 10 seconds
        private const val MINIMUM_AD_INTERVAL = 180000L // 3 minutes minimum between ads

        // Banner refresh interval (10 minutes)
        private const val BANNER_REFRESH_INTERVAL = 60000L // 10 minutes in milliseconds

        // Native ad frequency (min and max values)
        private const val MIN_NATIVE_AD_FREQUENCY = 10 // Min photos between random ads
        private const val MAX_NATIVE_AD_FREQUENCY = 15 // Max photos between random ads
        private const val DEFAULT_NATIVE_AD_FREQUENCY = 20 // Default fallback value

        // The number of native ads to preload in the cache
        private const val NATIVE_AD_CACHE_SIZE = 3
    }
    private var photoCount = 0
    private var photosUntilNextAd = DEFAULT_NATIVE_AD_FREQUENCY

    private var isInitialized = false
    private var mainAdView: AdView? = null
    private var settingsAdView: AdView? = null
    private var interstitialAd: InterstitialAd? = null
    private var timerRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var fullScreenInterstitialAd: InterstitialAd? = null
    private var isFullScreenInterstitialLoading = false
    private var lastFullScreenInterstitialTime = 0L
    private val FULL_SCREEN_INTERSTITIAL_INTERVAL = 600000L // 10 minutes
    private val FULL_SCREEN_INTERSTITIAL_DISPLAY_DURATION = 20000L // 20 seconds

    // Add a refresh handler for banner ads
    private var bannerRefreshRunnable: Runnable? = null

    // Track when ads were shown
    private var lastInterstitialAdTime = 0L
    private var isInterstitialShowing = false
    private var isLoadingInterstitial = false

    // Flag to track whether to show interstitial in settings
    private var showInterstitialInSettings = true

    // Native ad fields
    private val nativeAdsCache = ConcurrentLinkedQueue<NativeAd>()
    private var isLoadingNativeAd = false
    private val nativeAdLoadScope = CoroutineScope(Dispatchers.IO)

    private val random = kotlin.random.Random(System.currentTimeMillis().toInt())

    // The current listener for native ad loading
    private var nativeAdLoadListener: ((NativeAd?) -> Unit)? = null

    fun initialize() {
        if (isInitialized) return

        try {
            MobileAds.initialize(context) { initializationStatus ->
                Log.d(TAG, "Mobile Ads initialized: $initializationStatus")
                isInitialized = true

                // Preload native ads right after initialization
                preloadNativeAds()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AdManager", e)
        }
    }

    // Getter for interstitial ad unit ID
    fun getInterstitialAdUnitId(): String {
        return INTERSTITIAL_AD_UNIT_ID
    }

    private fun getRandomAdFrequency(): Int {
        return try {
            // Create a new Random instance each time instead of using the class field
            kotlin.random.Random(System.currentTimeMillis().toInt()).nextInt(MIN_NATIVE_AD_FREQUENCY, MAX_NATIVE_AD_FREQUENCY + 1)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating random ad frequency", e)
            DEFAULT_NATIVE_AD_FREQUENCY
        }
    }

    fun getNativeAdView(activity: Activity, nativeAd: NativeAd): View {
        val inflater = LayoutInflater.from(activity)
        // Inflate the root view first (ConstraintLayout)
        val rootView = inflater.inflate(R.layout.native_ad_layout, null) as ViewGroup

        // Find the NativeAdView inside the layout
        val adView = rootView.findViewById<NativeAdView>(R.id.native_ad_view)

        // Now populate the NativeAdView
        populateNativeAdView(nativeAd, adView)

        // Return the root view
        return rootView
    }

    // Method to populate a native ad view with ad data
    fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
        try {
            // Set the headline
            val headlineView = adView.findViewById<TextView>(R.id.ad_headline)
            headlineView.text = nativeAd.headline
            adView.headlineView = headlineView

            // Set the body text
            val bodyView = adView.findViewById<TextView>(R.id.ad_body)
            if (nativeAd.body != null) {
                bodyView.text = nativeAd.body
                bodyView.visibility = View.VISIBLE
            } else {
                bodyView.visibility = View.GONE
            }
            adView.bodyView = bodyView

            // Set the app icon
            val iconView = adView.findViewById<ImageView>(R.id.ad_app_icon)
            val icon = nativeAd.icon
            if (icon != null) {
                iconView.setImageDrawable(icon.drawable)
                iconView.visibility = View.VISIBLE
            } else {
                iconView.visibility = View.GONE
            }
            adView.iconView = iconView

            // Set the star rating
            val starRatingView = adView.findViewById<RatingBar>(R.id.ad_stars)
            if (nativeAd.starRating != null) {
                starRatingView.rating = nativeAd.starRating!!.toFloat()
                starRatingView.visibility = View.VISIBLE
            } else {
                starRatingView.visibility = View.GONE
            }
            adView.starRatingView = starRatingView

            // Set the advertiser name
            val advertiserView = adView.findViewById<TextView>(R.id.ad_advertiser)
            if (nativeAd.advertiser != null) {
                advertiserView.text = nativeAd.advertiser
                advertiserView.visibility = View.VISIBLE
            } else {
                advertiserView.visibility = View.GONE
            }
            adView.advertiserView = advertiserView

            // Set the price
            val priceView = adView.findViewById<TextView>(R.id.ad_price)
            if (nativeAd.price != null) {
                priceView.text = nativeAd.price
                priceView.visibility = View.VISIBLE
            } else {
                priceView.visibility = View.GONE
            }
            adView.priceView = priceView

            // Set the store
            val storeView = adView.findViewById<TextView>(R.id.ad_store)
            if (nativeAd.store != null) {
                storeView.text = nativeAd.store
                storeView.visibility = View.VISIBLE
            } else {
                storeView.visibility = View.GONE
            }
            adView.storeView = storeView

            // Set the call to action button
            val callToActionView = adView.findViewById<Button>(R.id.ad_call_to_action)
            if (nativeAd.callToAction != null) {
                callToActionView.text = nativeAd.callToAction
                callToActionView.visibility = View.VISIBLE
            } else {
                callToActionView.visibility = View.GONE
            }
            adView.callToActionView = callToActionView


            val mediaView = adView.findViewById<MediaView>(R.id.ad_media)
            if (mediaView != null) {
                adView.mediaView = mediaView // This is the key line to register the MediaView
                mediaView.visibility = View.VISIBLE
            } else {
                Log.e(TAG, "MediaView not found in layout")
            }

            // Register the native ad view
            adView.setNativeAd(nativeAd)

            Log.d(TAG, "Native ad view populated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error populating native ad view", e)
        }
    }

    fun updatePhotoCount(count: Int) {
        photoCount = count
        Log.d(TAG, "Photo count updated: $photoCount")
    }

    fun shouldShowNativeAd(): Boolean {
        if (appVersionManager.isProVersion()) {
            return false
        }

        // Decrement our counter
        photosUntilNextAd--

        Log.d(TAG, "Photos until next ad: $photosUntilNextAd")

        // If we've reached zero, it's time to show an ad
        if (photosUntilNextAd <= 0) {
            // Reset counter with a new random value for next time
            photosUntilNextAd = getRandomAdFrequency()
            Log.d(TAG, "Ad threshold reached. Next ad will show after $photosUntilNextAd photos")
            return true
        }

        return false
    }


    // Method to preload native ads
    private fun preloadNativeAds() {
        if (appVersionManager.isProVersion() || isLoadingNativeAd) {
            return
        }

        val cacheSize = nativeAdsCache.size
        if (cacheSize >= NATIVE_AD_CACHE_SIZE) {
            Log.d(TAG, "Native ad cache is already full ($cacheSize/$NATIVE_AD_CACHE_SIZE)")
            return
        }

        val numAdsToLoad = NATIVE_AD_CACHE_SIZE - cacheSize
        Log.d(TAG, "Preloading $numAdsToLoad native ads")

        for (i in 0 until numAdsToLoad) {
            loadNativeAd(null)
        }
    }

    // Method to load a single native ad
    fun loadNativeAd(callback: ((NativeAd?) -> Unit)?) {
        if (appVersionManager.isProVersion()) {
            callback?.invoke(null)
            return
        }

        // If we have cached ads, use one
        if (callback != null && nativeAdsCache.isNotEmpty()) {
            val cachedAd = nativeAdsCache.poll()
            callback.invoke(cachedAd)

            // Preload a replacement
            loadNativeAdInternal(null)
            return
        }

        // No cached ad available, load a new one
        loadNativeAdInternal(callback)
    }

    // Internal method to handle the actual loading of native ads
    private fun loadNativeAdInternal(callback: ((NativeAd?) -> Unit)?) {
        if (appVersionManager.isProVersion()) {
            callback?.invoke(null)
            return
        }

        if (!isInitialized) {
            initialize()
        }

        // Set the callback
        nativeAdLoadListener = callback

        isLoadingNativeAd = true

        val adLoader = AdLoader.Builder(context, NATIVE_AD_UNIT_ID)
            .forNativeAd { nativeAd ->
                // This is called when a native ad has loaded successfully
                Log.d(TAG, "Native ad loaded successfully")
                isLoadingNativeAd = false

                if (nativeAdLoadListener == callback) {
                    // If this is the most recent callback, invoke it
                    callback?.invoke(nativeAd)
                    nativeAdLoadListener = null
                } else if (callback == null) {
                    // If no callback, this is a preload for the cache
                    nativeAdsCache.offer(nativeAd)
                    Log.d(TAG, "Added native ad to cache (size: ${nativeAdsCache.size})")
                } else {
                    // This is an old callback, just add to cache
                    nativeAdsCache.offer(nativeAd)
                    Log.d(TAG, "Added native ad to cache (size: ${nativeAdsCache.size}) for old callback")
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e(TAG, "Native ad failed to load: ${error.message}")
                    isLoadingNativeAd = false

                    if (nativeAdLoadListener == callback) {
                        callback?.invoke(null)
                        nativeAdLoadListener = null
                    }

                    // Retry loading after a delay
                    mainHandler.postDelayed({
                        if (!appVersionManager.isProVersion() && nativeAdsCache.size < NATIVE_AD_CACHE_SIZE) {
                            loadNativeAdInternal(null) // Try to preload again
                        }
                    }, 60000) // Retry after 1 minute
                }

                override fun onAdClosed() {
                    Log.d(TAG, "Native ad closed")
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
                    .build()
            )
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    // Method to create a rendered bitmap from a native ad for displaying in the slideshow
    fun getNativeAdForSlideshow(activity: Activity, callback: (NativeAd?) -> Unit) {
        if (appVersionManager.isProVersion()) {
            callback(null)
            return
        }

        // If we have a cached ad, use it
        if (nativeAdsCache.isNotEmpty()) {
            callback(nativeAdsCache.poll())

            // Preload a replacement
            nativeAdLoadScope.launch {
                preloadNativeAds()
            }
            return
        }

        // Otherwise load a new ad
        loadNativeAd(callback)
    }

    // Updated method to use the AdActivity instead of direct ad display
    fun loadAndShowFullScreenInterstitial(activity: Activity) {
        // Don't show ad if pro version or if interval hasn't passed
        if (appVersionManager.isProVersion()) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFullScreenInterstitialTime < FULL_SCREEN_INTERSTITIAL_INTERVAL) {
            Log.d(TAG, "Full screen interstitial interval not elapsed yet")
            return
        }

        // Record that we're showing an ad now
        lastFullScreenInterstitialTime = System.currentTimeMillis()

    }

    // Method to check and show full screen interstitial based on timer
    fun checkAndShowFullScreenInterstitial(activity: Activity) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFullScreenInterstitialTime >= FULL_SCREEN_INTERSTITIAL_INTERVAL) {
            loadAndShowFullScreenInterstitial(activity)
        }
    }

    // Only call this method when navigating to settings
    fun preloadInterstitialForSettings() {
        if (appVersionManager.isProVersion() || isLoadingInterstitial) {
            return
        }

        showInterstitialInSettings = true
        isLoadingInterstitial = true

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(context, INTERSTITIAL_AD_UNIT_ID, adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Interstitial ad loaded successfully")
                    interstitialAd = ad
                    isLoadingInterstitial = false

                    // Set full screen content callbacks
                    interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            Log.d(TAG, "Interstitial ad dismissed")
                            interstitialAd = null
                            isInterstitialShowing = false
                            showInterstitialInSettings = false
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            Log.e(TAG, "Interstitial ad failed to show: ${adError.message}")
                            interstitialAd = null
                            isInterstitialShowing = false
                            showInterstitialInSettings = false
                        }

                        override fun onAdShowedFullScreenContent() {
                            Log.d(TAG, "Interstitial ad showed successfully")
                            lastInterstitialAdTime = System.currentTimeMillis()
                            isInterstitialShowing = true
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Interstitial ad failed to load: ${adError.message}")
                    interstitialAd = null
                    isLoadingInterstitial = false
                    showInterstitialInSettings = false
                }
            })
    }

    fun setupMainActivityAd(container: FrameLayout?) {
        if (appVersionManager.isProVersion() || container == null) {
            Log.d(TAG, "Pro version or null container, not showing ads")
            return
        }

        // Check if we're in MainActivity by looking at the current context
        val contextName = container.context.javaClass.simpleName
        if (contextName == "MainActivity") {
            Log.d(TAG, "Skipping banner ad in MainActivity - only showing full screen interstitials")
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }

        try {
            if (!isInitialized) {
                initialize()
            }

            // Cancel any existing refresh runnable
            bannerRefreshRunnable?.let { mainHandler.removeCallbacks(it) }

            // Get the ad size before creating the AdView
            val adSize = getAdSizeForContainer(container)

            // Create new AdView with the correct size
            mainAdView = AdView(context).apply {
                setAdSize(adSize)
                setAdUnitId(BANNER_AD_UNIT_ID)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        Log.d(TAG, "Main activity banner ad loaded")
                        container.post {
                            if (container.isAttachedToWindow) {
                                container.visibility = ViewGroup.VISIBLE
                            }
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e(TAG, "Main activity banner ad failed to load: ${error.message}")
                        // Don't hide the container on failure, we'll retry

                        // If ad fails to load, retry after a short delay
                        mainHandler.postDelayed({
                            if (!appVersionManager.isProVersion() && mainAdView != null) {
                                loadMainActivityAd()
                            }
                        }, 60000) // Retry after 1 minute
                    }

                    override fun onAdClosed() {
                        Log.d(TAG, "Main activity banner ad closed")
                    }
                }
            }

            container.post {
                try {
                    if (container.isAttachedToWindow) {
                        container.removeAllViews()
                        container.addView(mainAdView)
                        container.visibility = ViewGroup.VISIBLE

                        // Load the ad immediately after adding to container
                        mainAdView?.loadAd(AdRequest.Builder().build())
                        Log.d(TAG, "Main activity banner ad loading requested")

                        // Setup the refresh timer for 10 minutes
                        setupBannerRefreshTimer()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up ad container view", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up main activity ad", e)
        }
    }

    // Setup a timer to refresh banner ads every 10 minutes
    private fun setupBannerRefreshTimer() {
        // Cancel any existing refresh timer
        bannerRefreshRunnable?.let { mainHandler.removeCallbacks(it) }

        bannerRefreshRunnable = object : Runnable {
            override fun run() {
                if (!appVersionManager.isProVersion() && mainAdView != null) {
                    Log.d(TAG, "Refreshing banner ad after 10 minute interval")
                    loadMainActivityAd()
                }

                // Schedule next refresh
                mainHandler.postDelayed(this, BANNER_REFRESH_INTERVAL)
            }
        }

        // Start the refresh timer
        mainHandler.postDelayed(bannerRefreshRunnable!!, BANNER_REFRESH_INTERVAL)
    }

    // Helper method to calculate the optimal ad size
    private fun getAdSizeForContainer(container: FrameLayout): AdSize {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val outMetrics = DisplayMetrics()
        display.getMetrics(outMetrics)

        val density = outMetrics.density

        var adWidthPixels = container.width.toFloat()
        if (adWidthPixels == 0f) {
            adWidthPixels = outMetrics.widthPixels.toFloat()
        }

        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
    }

    fun setupSettingsFragmentAd(container: FrameLayout) {
        if (appVersionManager.isProVersion()) {
            Log.d(TAG, "Pro version, not showing ads")
            container.visibility = View.GONE
            return
        }

        try {
            if (!isInitialized) {
                initialize()
            }

            settingsAdView = AdView(context).apply {
                setAdUnitId(BANNER_AD_UNIT_ID)
                setAdSize(AdSize.BANNER)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        Log.d(TAG, "Settings fragment ad loaded")
                        container.post {
                            container.visibility = ViewGroup.VISIBLE

                            // Ensure proper margins after ad loads
                            container.parent?.let { parent ->
                                if (parent is ViewGroup) {
                                    parent.requestLayout()
                                }
                            }
                        }
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e(TAG, "Settings fragment ad failed to load: ${error.message}")
                        container.visibility = ViewGroup.GONE
                    }
                }
            }

            // Setup container
            container.removeAllViews()
            container.addView(settingsAdView)

            // Set initial visibility
            container.visibility = ViewGroup.GONE

            // Load ad
            loadSettingsAd()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up settings fragment ad", e)
            container.visibility = ViewGroup.GONE
        }
    }

    private fun shouldShowInterstitial(): Boolean {
        // Check if enough time has passed since last interstitial
        val timeSinceLastAd = System.currentTimeMillis() - lastInterstitialAdTime
        return interstitialAd != null &&
                timeSinceLastAd > MINIMUM_AD_INTERVAL &&
                !isInterstitialShowing &&
                showInterstitialInSettings
    }

    // This is now primarily used for SettingsFragment
    fun showInterstitialAd(activity: Activity? = null) {
        if (appVersionManager.isProVersion() || isInterstitialShowing || !showInterstitialInSettings) {
            return
        }

        val ad = interstitialAd
        if (ad != null && activity != null) {
            Log.d(TAG, "Showing interstitial ad")
            ad.show(activity)
            // After showing, reset the flag
            showInterstitialInSettings = false
        } else if (ad == null) {
            Log.d(TAG, "Interstitial ad not loaded yet")
            showInterstitialInSettings = false
        }
    }

    fun loadMainActivityAd() {
        if (appVersionManager.isProVersion()) return

        mainAdView?.loadAd(AdRequest.Builder().build())
    }

    fun loadSettingsAd() {
        if (appVersionManager.isProVersion()) return

        settingsAdView?.loadAd(AdRequest.Builder().build())
    }

    fun pauseAds() {
        mainAdView?.pause()
        settingsAdView?.pause()
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        bannerRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    fun resumeAds() {
        if (appVersionManager.isProVersion()) return

        mainAdView?.resume()
        settingsAdView?.resume()
        timerRunnable?.let { mainHandler.post(it) }

        // Resume banner refresh
        bannerRefreshRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            mainHandler.postDelayed(runnable, BANNER_REFRESH_INTERVAL)
        }
    }

    fun destroyAds() {
        mainAdView?.destroy()
        settingsAdView?.destroy()
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        bannerRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        interstitialAd = null
        mainAdView = null
        settingsAdView = null

        // Clean up native ads
        nativeAdsCache.forEach { it.destroy() }
        nativeAdsCache.clear()
    }

    object NativeAdSettings {
        // Min and max frequency
        const val MIN_FREQUENCY = MIN_NATIVE_AD_FREQUENCY
        const val MAX_FREQUENCY = MAX_NATIVE_AD_FREQUENCY
    }
}