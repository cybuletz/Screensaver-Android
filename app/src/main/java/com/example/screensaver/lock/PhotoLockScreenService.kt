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
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.PhotoLoadingManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import com.example.screensaver.models.MediaItem

@AndroidEntryPoint
class PhotoLockScreenService : Service() {

    @Inject
    lateinit var photoLoadingManager: PhotoLoadingManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val photoManager by lazy { GooglePhotosManager.getInstance(applicationContext) }
    private var isInitialized = false
    private var initializationJob: Job? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_USER_PRESENT -> handleUserPresent()
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
        registerScreenReceiver()
        initializeService()
    }

    private fun registerScreenReceiver() {
        try {
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
            Log.d(TAG, "Screen receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receiver", e)
        }
    }

    private fun handleScreenOff() {
        Log.d(TAG, "Screen turned off")
        if (isInitialized) {
            showLockScreen()
        } else {
            initializeService()
        }
    }

    private fun handleUserPresent() {
        Log.d(TAG, "User unlocked device")
        serviceScope.launch {
            try {
                precachePhotos()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling user present", e)
            }
        }
    }

    private fun initializeService() {
        initializationJob?.cancel()
        initializationJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (photoManager.initialize() && photoManager.loadPhotos()) {
                        isInitialized = true
                        precachePhotos()
                        Log.d(TAG, "Service initialized successfully")
                    } else {
                        Log.e(TAG, "Failed to initialize photo manager")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing service", e)
                isInitialized = false
            }
        }
    }

    private suspend fun precachePhotos() {
        if (!isInitialized) return

        withContext(Dispatchers.IO) {
            try {
                val photoCount = photoManager.getPhotoCount()
                when {
                    photoCount > 0 -> {
                        repeat(minOf(PRECACHE_COUNT, photoCount)) { index ->
                            photoManager.getPhotoUrl(index)?.let { url ->
                                val mediaItem = MediaItem(
                                    id = index.toString(),
                                    albumId = "lock_screen",
                                    baseUrl = url,
                                    mimeType = "image/jpeg", // Default mime type
                                    width = 1920,  // Default width
                                    height = 1080  // Default height
                                )
                                photoLoadingManager.preloadPhoto(mediaItem)
                            }
                        }
                        Log.d(TAG, "Precached $PRECACHE_COUNT photos")
                    }
                    else -> {
                        Log.d(TAG, "No photos to precache")
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
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        initializationJob?.cancel()
        serviceScope.cancel()
        photoManager.cleanup()
        isInitialized = false

        // Clear Glide memory cache
        Glide.get(applicationContext).clearMemory()
    }

    private fun showLockScreen() {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguardManager.isKeyguardLocked) {
                val lockIntent = Intent(this, PhotoLockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("from_service", true)
                }
                startActivity(lockIntent)
                Log.d(TAG, "Lock screen activity started")
            } else {
                Log.d(TAG, "Device already locked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing lock screen", e)
        }
    }
}