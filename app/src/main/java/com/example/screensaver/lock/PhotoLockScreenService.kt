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
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.example.screensaver.MainActivity
import com.example.screensaver.PhotoSourceState
import com.example.screensaver.models.MediaItem
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.utils.NotificationHelper
import com.example.screensaver.utils.PhotoLoadingManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import com.example.screensaver.lock.LockScreenPhotoManager.PhotoAddMode

@AndroidEntryPoint
class PhotoLockScreenService : Service() {

    @Inject
    lateinit var photoLoadingManager: PhotoLoadingManager

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var photoSourceState: PhotoSourceState

    @Inject
    lateinit var lockScreenPhotoManager: LockScreenPhotoManager

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var notificationHelper: NotificationHelper

    private var isPreviewMode = false
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
        private const val MIN_PREVIEW_INTERVAL = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        val notification = notificationHelper.createServiceNotification(
            title = if (isPreviewMode) "Preview Mode Active" else "Lock Screen Active",
            content = if (isPreviewMode) "Tap to exit preview mode" else "Tap to return to lock screen"
        )

        try {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }

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
        if (!isPreviewMode && isInitialized) {
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
                val selectedAlbums = preferences.getSelectedAlbumIds()
                Log.d(TAG, "Initializing service with ${selectedAlbums.size} selected albums")

                if (selectedAlbums.isNotEmpty()) {
                    if (googlePhotosManager.initialize()) {
                        val photos = googlePhotosManager.loadPhotos()
                        if (photos != null) {
                            withContext(Dispatchers.IO) {
                                // Don't clear existing photos, just append new ones
                                lockScreenPhotoManager.addPhotos(
                                    photos = photos,
                                    mode = PhotoAddMode.APPEND
                                )
                            }

                            isInitialized = true
                            precachePhotos()
                        }
                    }
                } else {
                    Log.d(TAG, "No albums selected, keeping existing photos")
                    isInitialized = true
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> Log.d(TAG, "Initialization job was cancelled")
                    else -> Log.e(TAG, "Error initializing service", e)
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
                isInitialized = false
                initializeService()
                showMainScreen()
            }
        }

        updateNotification()

        if (!isInitialized) {
            Log.d(TAG, "Service not initialized, starting initialization")
            serviceScope.launch {
                try {
                    initializeService()
                    val photoCount = lockScreenPhotoManager.getPhotoCount()
                    Log.d(TAG, "Service initialized, photo count: $photoCount")
                    if (photoCount == 0) {
                        Log.d(TAG, "No photos available, attempting to load")
                        serviceScope.launch {
                            val photos = googlePhotosManager.loadPhotos()
                            if (photos?.isNotEmpty() == true) {
                                lockScreenPhotoManager.addPhotos(photos)
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
            Log.d(TAG, "Service already initialized, photo count: ${lockScreenPhotoManager.getPhotoCount()}")
        }

        return START_STICKY
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
        updateNotification()
    }

    private fun updateNotification() {
        try {
            val notification = notificationHelper.createServiceNotification(
                title = if (isPreviewMode) "Preview Mode Active" else "Lock Screen Active",
                content = if (isPreviewMode) "Tap to exit preview mode" else "Tap to return to lock screen"
            )
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
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
        isPreviewMode = false
        Glide.get(applicationContext).clearMemory()
    }

    private fun showMainScreen() {
        try {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("from_service", true)
                putExtra("photos_ready", true)
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
}