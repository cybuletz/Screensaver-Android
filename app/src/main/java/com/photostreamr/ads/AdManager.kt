package com.photostreamr.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.photostreamr.version.AppVersionManager
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.photostreamr.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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

        // Production ad units - replace with your actual ad unit IDs
        private const val PRODUCTION_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
        private const val PRODUCTION_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

        // Auto-detect whether we're in debug or release mode
        private val IS_DEBUG = BuildConfig.DEBUG

        // Use appropriate ad unit IDs based on build type
        private val BANNER_AD_UNIT_ID = if (IS_DEBUG) TEST_BANNER_AD_UNIT_ID else PRODUCTION_BANNER_AD_UNIT_ID
        private val INTERSTITIAL_AD_UNIT_ID = if (IS_DEBUG) TEST_INTERSTITIAL_AD_UNIT_ID else PRODUCTION_INTERSTITIAL_AD_UNIT_ID

        // Configure ad display timing (in milliseconds)
        private const val INTERSTITIAL_AD_DISPLAY_DURATION = 10000L // 10 seconds
        private const val MINIMUM_AD_INTERVAL = 180000L // 3 minutes minimum between ads

        // Banner refresh interval (10 minutes)
        private const val BANNER_REFRESH_INTERVAL = 600000L // 10 minutes in milliseconds
    }

    private var isInitialized = false
    private var mainAdView: AdView? = null
    private var settingsAdView: AdView? = null
    private var interstitialAd: InterstitialAd? = null
    private var timerRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Add a refresh handler for banner ads
    private var bannerRefreshRunnable: Runnable? = null

    // Track when ads were shown
    private var lastInterstitialAdTime = 0L
    private var isInterstitialShowing = false
    private var isLoadingInterstitial = false

    fun initialize() {
        if (isInitialized) return

        try {
            MobileAds.initialize(context) { initializationStatus ->
                Log.d(TAG, "Mobile Ads initialized: $initializationStatus")
                isInitialized = true

                // Pre-load interstitial ad after initialization
                preloadInterstitialAd()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AdManager", e)
        }
    }

    private fun preloadInterstitialAd() {
        if (appVersionManager.isProVersion() || isLoadingInterstitial) {
            return
        }

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

                            // Preload next ad for future use
                            preloadInterstitialAd()
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            Log.e(TAG, "Interstitial ad failed to show: ${adError.message}")
                            interstitialAd = null
                            isInterstitialShowing = false
                        }

                        override fun onAdShowedFullScreenContent() {
                            Log.d(TAG, "Interstitial ad showed successfully")
                            lastInterstitialAdTime = System.currentTimeMillis()
                            isInterstitialShowing = true

                            // Auto-dismiss after duration if needed
                            mainHandler.postDelayed({
                                if (isInterstitialShowing && interstitialAd != null) {
                                    // Let the user dismiss it themselves
                                    // We don't force-dismiss ads as this could violate policy
                                }
                            }, INTERSTITIAL_AD_DISPLAY_DURATION)
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.e(TAG, "Interstitial ad failed to load: ${adError.message}")
                    interstitialAd = null
                    isLoadingInterstitial = false

                    // Retry after delay
                    mainHandler.postDelayed({
                        preloadInterstitialAd()
                    }, 60000) // Retry after 1 minute
                }
            })
    }

    fun setupMainActivityAd(container: FrameLayout?) {
        if (appVersionManager.isProVersion() || container == null) {
            Log.d(TAG, "Pro version or null container, not showing ads")
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
                        container.post {
                            if (container.isAttachedToWindow) {
                                container.visibility = ViewGroup.GONE
                            }
                        }

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
                        // Don't set to GONE here, let the ad listener handle visibility

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
    // This is compliant with AdMob policies that allow refreshing ads
    // at a reasonable interval (not too frequent)
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
            container.visibility = View.GONE
        }
    }

    private fun scheduleAdDisplay() {
        // Cancel any existing timer
        timerRunnable?.let { mainHandler.removeCallbacks(it) }

        timerRunnable = object : Runnable {
            override fun run() {
                if (appVersionManager.shouldShowAd()) {
                    // We no longer automatically show interstitial ads in MainActivity
                    // Those are now only for SettingsFragment and explicitly called
                    appVersionManager.updateLastAdShownTime()
                }

                // Schedule next check
                mainHandler.postDelayed(this, 60000) // Check every minute
            }
        }

        // Start the timer
        mainHandler.post(timerRunnable!!)
    }

    private fun shouldShowInterstitial(): Boolean {
        // Check if enough time has passed since last interstitial
        val timeSinceLastAd = System.currentTimeMillis() - lastInterstitialAdTime
        return interstitialAd != null &&
                timeSinceLastAd > MINIMUM_AD_INTERVAL &&
                !isInterstitialShowing
    }

    // This is now primarily used for SettingsFragment
    fun showInterstitialAd(activity: Activity? = null) {
        if (appVersionManager.isProVersion() || isInterstitialShowing) {
            return
        }

        val ad = interstitialAd
        if (ad != null && activity != null) {
            Log.d(TAG, "Showing interstitial ad")
            ad.show(activity)
        } else if (ad == null) {
            Log.d(TAG, "Interstitial ad not loaded yet")
            // Load a new one for next time
            preloadInterstitialAd()
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

        // Make sure we have an interstitial ready
        if (interstitialAd == null && !isLoadingInterstitial) {
            preloadInterstitialAd()
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
    }
}