package com.example.screensaver.lock

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.GooglePhotosManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PhotoLockScreenService : Service() {
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val photoManager by lazy { GooglePhotosManager.getInstance(this) }
    private var isInitialized = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen turned off")
                    if (isInitialized) {
                        showLockScreen()
                    } else {
                        initializeService()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User unlocked device")
                    // Pre-cache next set of photos while user is using device
                    precachePhotos()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhotoLockScreenService"
        private const val PRECACHE_COUNT = 5
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Register for both screen off and user present events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        initializeService()
    }

    private fun initializeService() {
        coroutineScope.launch {
            try {
                if (photoManager.initialize()) {
                    if (photoManager.loadPhotos()) {
                        isInitialized = true
                        precachePhotos()
                        Log.d(TAG, "Service initialized successfully")
                    } else {
                        Log.e(TAG, "Failed to load photos")
                    }
                } else {
                    Log.e(TAG, "Failed to initialize photo manager")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing service", e)
            }
        }
    }

    private fun precachePhotos() {
        if (!isInitialized) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val photoCount = photoManager.getPhotoCount()
                if (photoCount > 0) {
                    repeat(minOf(PRECACHE_COUNT, photoCount)) { index ->
                        photoManager.getPhotoUrl(index)?.let { url ->
                            Glide.with(this@PhotoLockScreenService)
                                .load(url)
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .preload()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error precaching photos", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service start command received")

        if (!isInitialized) {
            initializeService()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed")

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }

        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        coroutineScope.cancel()
        photoManager.cleanup()
        isInitialized = false
    }

    private fun showLockScreen() {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguardManager.isKeyguardLocked) {
                Intent(this, PhotoLockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("from_service", true)
                    startActivity(this)
                }
                Log.d(TAG, "Lock screen activity started")
            } else {
                Log.d(TAG, "Device already locked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing lock screen", e)
        }
    }
}