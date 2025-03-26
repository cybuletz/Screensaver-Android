package com.photostreamr.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.photostreamr.version.AppVersionManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
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
        private const val BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111" // Replace with your actual ad unit ID
    }

    private var isInitialized = false
    private var mainAdView: AdView? = null
    private var settingsAdView: AdView? = null
    private var timerRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize() {
        if (isInitialized) return

        try {
            MobileAds.initialize(context) { initializationStatus ->
                Log.d(TAG, "Mobile Ads initialized: $initializationStatus")
                isInitialized = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AdManager", e)
        }
    }

    fun setupMainActivityAd(container: FrameLayout) {
        if (appVersionManager.isProVersion()) {
            Log.d(TAG, "Pro version, not showing ads")
            return
        }

        try {
            if (!isInitialized) {
                initialize()
            }

            mainAdView = AdView(context).apply {
                setAdUnitId(BANNER_AD_UNIT_ID)
                setAdSize(AdSize.BANNER)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        Log.d(TAG, "Main activity ad loaded")
                        container.visibility = ViewGroup.VISIBLE
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e(TAG, "Main activity ad failed to load: ${error.message}")
                        container.visibility = ViewGroup.GONE
                    }
                }
            }

            container.removeAllViews()
            container.addView(mainAdView)
            container.visibility = ViewGroup.GONE

            // Start the timer for periodic ad display
            scheduleAdDisplay(container)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up main activity ad", e)
        }
    }

    fun setupSettingsFragmentAd(container: FrameLayout) {
        if (appVersionManager.isProVersion()) {
            Log.d(TAG, "Pro version, not showing ads")
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
                        container.visibility = ViewGroup.VISIBLE
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e(TAG, "Settings fragment ad failed to load: ${error.message}")
                        container.visibility = ViewGroup.GONE
                    }
                }
            }

            container.removeAllViews()
            container.addView(settingsAdView)
            container.visibility = ViewGroup.GONE

            // Load ad immediately for settings fragment
            loadSettingsAd()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up settings fragment ad", e)
        }
    }

    private fun scheduleAdDisplay(container: FrameLayout) {
        // Cancel any existing timer
        timerRunnable?.let { mainHandler.removeCallbacks(it) }

        timerRunnable = object : Runnable {
            override fun run() {
                if (appVersionManager.shouldShowAd()) {
                    loadMainActivityAd()
                    appVersionManager.updateLastAdShownTime()
                }

                // Schedule next check
                mainHandler.postDelayed(this, 60000) // Check every minute
            }
        }

        // Start the timer
        mainHandler.post(timerRunnable!!)
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
    }

    fun resumeAds() {
        if (appVersionManager.isProVersion()) return

        mainAdView?.resume()
        settingsAdView?.resume()
        timerRunnable?.let { mainHandler.post(it) }
    }

    fun destroyAds() {
        mainAdView?.destroy()
        settingsAdView?.destroy()
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        mainAdView = null
        settingsAdView = null
    }
}