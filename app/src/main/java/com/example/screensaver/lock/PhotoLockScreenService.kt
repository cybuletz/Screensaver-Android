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
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.R
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.PhotoLoadingManager
import com.example.screensaver.models.MediaItem
import com.example.screensaver.kiosk.KioskActivity
import com.example.screensaver.kiosk.KioskPolicyManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import com.example.screensaver.PhotoSourceState
import com.example.screensaver.lock.LockScreenPhotoManager
import kotlinx.coroutines.*
import com.example.screensaver.MainActivity
import com.example.screensaver.utils.AppPreferences

@AndroidEntryPoint
class PhotoLockScreenService : Service() {

    @Inject
    lateinit var photoLoadingManager: PhotoLoadingManager

    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var photoSourceState: PhotoSourceState

    @Inject
    lateinit var lockScreenPhotoManager: LockScreenPhotoManager  // Main photo manager

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager  // Google Photos source

    private var isPreviewMode = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isInitialized = false
    private var initializationJob: Job? = null
    private var isKioskMode = false

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
        private const val MIN_PREVIEW_INTERVAL = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Start foreground immediately before any other operations
        startForegroundWithNotification()

        // Then do other initializations
        createNotificationChannel()
        registerScreenReceiver()
        checkKioskMode()
        initializeService()
    }

    private fun checkKioskMode() {
        isKioskMode = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("kiosk_mode_enabled", false)
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
        val title = when {
            isPreviewMode -> "Preview Mode Active"
            isKioskMode -> "Kiosk Mode Active"
            else -> "Lock Screen Active"
        }

        val text = when {
            isPreviewMode -> "Tap to exit preview mode"
            isKioskMode -> "App running in kiosk mode"
            else -> "Tap to return to lock screen"
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
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
        if (!isPreviewMode && isInitialized) {
            if (isKioskMode) {
                showKioskScreen()
            } else {
                showLockScreen()
            }
        } else {
            initializeService()
        }
    }

    private fun handleUserPresent() {
        Log.d(TAG, "User unlocked device")
        serviceScope.launch {
            try {
                if (isKioskMode && !isPreviewMode) {
                    showKioskScreen()
                }
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
                val selectedAlbums = preferences.getSelectedAlbumIds()
                if (selectedAlbums.isNotEmpty()) {
                    if (googlePhotosManager.initialize()) {
                        val photos = googlePhotosManager.loadPhotos()
                        if (photos != null) {
                            lockScreenPhotoManager.clearPhotos()
                            lockScreenPhotoManager.addPhotos(photos)
                            // Add this line to ensure preference is set
                            PreferenceManager.getDefaultSharedPreferences(this@PhotoLockScreenService)
                                .edit()
                                .putStringSet("photo_source_selection",
                                    setOf("google_photos") + (PreferenceManager
                                        .getDefaultSharedPreferences(this@PhotoLockScreenService)
                                        .getStringSet("photo_source_selection", emptySet()) ?: emptySet())
                                )
                                .apply()
                            isInitialized = true
                            precachePhotos()
                        }
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
        if (!isInitialized) {
            Log.d(TAG, "Skipping precache - not initialized")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val photoCount = lockScreenPhotoManager.getPhotoCount()
                Log.d(TAG, "Starting precache with $photoCount available photos")

                when {
                    photoCount > 0 -> {
                        val countToPreload = minOf(PRECACHE_COUNT, photoCount)
                        repeat(countToPreload) { index ->
                            lockScreenPhotoManager.getPhotoUrl(index)?.let { url ->
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
                        Log.d(TAG, "Precached $countToPreload photos")
                    }
                    else -> {
                        Log.d(TAG, "No photos available to precache")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error precaching photos", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service start command received: ${intent?.action}")

        when (intent?.action) {
            "CHECK_KIOSK_MODE" -> {
                Log.d(TAG, "Checking kiosk mode status")
                checkKioskMode()
                if (isKioskMode && !isPreviewMode) {
                    Log.d(TAG, "Kiosk mode active and not in preview - applying policies")
                    kioskPolicyManager.setKioskPolicies(true)
                    showKioskScreen()
                }
            }
            "STOP_KIOSK" -> {
                Log.d(TAG, "Stopping kiosk mode")
                isKioskMode = false
                kioskPolicyManager.setKioskPolicies(false)
            }
            "START_PREVIEW" -> {
                Log.d(TAG, "Starting preview mode")
                handlePreviewStart()
            }
            "STOP_PREVIEW" -> {
                Log.d(TAG, "Stopping preview mode")
                handlePreviewStop()
            }
            "AUTH_UPDATED" -> {
                Log.d(TAG, "Auth updated, photo count: ${lockScreenPhotoManager.getPhotoCount()}")
                isInitialized = false  // Reset initialization state
                initializeService()    // Initialize service to load photos
                showMainScreen()  // Show main screen instead of lock screen
            }
        }

        startForegroundWithNotification()

        if (!isInitialized) {
            Log.d(TAG, "Service not initialized, starting initialization")
            serviceScope.launch {
                try {
                    initializeService()
                    val photoCount = lockScreenPhotoManager.getPhotoCount()  // Changed from photoManager
                    Log.d(TAG, "Service initialized, photo count: $photoCount")
                    if (photoCount == 0) {
                        Log.d(TAG, "No photos available, attempting to load")
                        serviceScope.launch {
                            val photos = googlePhotosManager.loadPhotos()  // Changed from photoManager
                            if (photos?.isNotEmpty() == true) {
                                lockScreenPhotoManager.addPhotos(photos)  // Add photos to lockScreenPhotoManager
                                Log.d(TAG, "Successfully loaded ${photos.size} photos")
                                precachePhotos()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during service initialization", e)
                }
            }
        } else {
            Log.d(TAG, "Service already initialized, photo count: ${lockScreenPhotoManager.getPhotoCount()}")  // Changed from photoManager
        }

        return START_STICKY
    }

    fun onAuthenticationUpdated() {
        if (!isInitialized) {
            initializeService()
        }
    }

    private fun handlePreviewStart() {
        if (canStartPreview()) {
            isPreviewMode = true
            photoSourceState.recordPreviewStarted()
            showLockScreen(true)
        }
    }

    private fun handlePreviewStop() {
        isPreviewMode = false
        startForegroundWithNotification()
    }

    private fun canStartPreview(): Boolean {
        return photoSourceState.getTimeSinceLastPreview() > MIN_PREVIEW_INTERVAL
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
        googlePhotosManager.cleanup()
        lockScreenPhotoManager.cleanup()
        isInitialized = false

        if (isKioskMode) {
            kioskPolicyManager.setKioskPolicies(false)
        }

        isPreviewMode = false
        Glide.get(applicationContext).clearMemory()
    }

    private fun showMainScreen() {
        try {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("from_service", true)
                putExtra("photos_ready", true)  // Add this line
            }
            startActivity(mainIntent)
            Log.d(TAG, "Main activity started with photos_ready flag")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing main screen", e)
        }
    }

    private fun showLockScreen(isPreview: Boolean = false) {
        try {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguardManager.isKeyguardLocked || isPreview) {
                val lockIntent = Intent(this, PhotoLockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("from_service", true)
                    putExtra("preview_mode", isPreview)
                }
                startActivity(lockIntent)
                Log.d(TAG, "Lock screen activity started (Preview: $isPreview)")
            } else {
                Log.d(TAG, "Device already locked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing lock screen", e)
        }
    }

    private fun showKioskScreen() {
        try {
            val kioskIntent = Intent(this, KioskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("from_service", true)
            }
            startActivity(kioskIntent)
            Log.d(TAG, "Kiosk activity started")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing kiosk screen", e)
        }
    }
}