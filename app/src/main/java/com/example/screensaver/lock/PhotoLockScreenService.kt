package com.example.screensaver.lock

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.R
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.PhotoLoadingManager
import com.example.screensaver.models.MediaItem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

@AndroidEntryPoint
class PhotoLockScreenService : Service() {

    @Inject
    lateinit var photoLoadingManager: PhotoLoadingManager

    @Inject
    lateinit var photoManager: GooglePhotosManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
        private const val NOTIFICATION_CHANNEL_ID = "lock_screen"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        registerScreenReceiver()
        initializeService()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Lock Screen Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the lock screen service running"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Lock Screen Active")
            .setContentText("Tap to return to lock screen")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
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
                    // First check if we have tokens
                    if (!photoManager.hasValidTokens()) {
                        Log.d(TAG, "No valid tokens available, waiting for authentication")
                        return@withContext
                    }

                    if (photoManager.initialize() && photoManager.loadPhotos()?.isNotEmpty() == true) {
                        isInitialized = true
                        precachePhotos()
                        Log.d(TAG, "Service initialized successfully")
                    } else {
                        Log.e(TAG, "Failed to initialize photo manager")
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> {
                        Log.d(TAG, "Initialization job was cancelled")
                    }
                    else -> {
                        Log.e(TAG, "Error initializing service", e)
                    }
                }
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
                                    mimeType = "image/jpeg",
                                    width = 1920,
                                    height = 1080
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
        startForegroundWithNotification()

        // Only try to initialize if we're not already initialized
        if (!isInitialized) {
            initializeService()
        }

        return START_STICKY
    }

    fun onAuthenticationUpdated() {
        if (!isInitialized) {
            initializeService()
        }
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