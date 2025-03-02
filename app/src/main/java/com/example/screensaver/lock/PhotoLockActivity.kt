package com.example.screensaver.lock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.screensaver.R
import com.example.screensaver.shared.GooglePhotosManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.abs
import android.app.Activity
import android.os.Build
import androidx.activity.OnBackPressedCallback


@AndroidEntryPoint
open class PhotoLockActivity : AppCompatActivity() {
    // UI Components with backing properties for safe access
    private var _backgroundImageView: ImageView? = null
    private var _overlayImageView: ImageView? = null

    protected val backgroundImageView: ImageView get() = _backgroundImageView!!
    protected val overlayImageView: ImageView get() = _overlayImageView!!

    protected lateinit var gestureDetector: GestureDetectorCompat
    private var previewStartTime: Long = 0

    @Inject
    lateinit var photoManager: GooglePhotosManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var glide: RequestManager

    // State Variables with thread-safe access
    @Volatile protected open var currentPhotoIndex = 0
    @Volatile private var isPowerSaving = false
    @Volatile private var isActivityVisible = false
    @Volatile private var screenWidth: Int = 0
    @Volatile private var isInitialized = false
    @Volatile private var isPreviewMode = false
    @Volatile private var isDestroyed = false

    companion object {
        private const val TAG = "PhotoLockActivity"
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
        private const val DEFAULT_INTERVAL = 10000L
        private const val POWER_SAVING_SCALE = 2L
        private const val TRANSITION_DURATION_NORMAL = 1000L
        private const val TRANSITION_DURATION_POWER_SAVING = 500L
        private const val MAX_PREVIEW_DURATION = 300000L
    }

    private val powerSavingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                mainHandler.post { updatePowerSavingMode() }
            }
        }
    }

    private val photoChangeRunnable = object : Runnable {
        override fun run() {
            if (!isActivityVisible || !isInitialized || isDestroyed) return

            activityScope.launch {  // Changed from uiScope to activityScope
                try {
                    transitionToNextPhoto()
                    scheduleNextPhotoChange()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during photo transition", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isPreviewMode) {
                    finish()
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        onBackPressedDispatcher.onBackPressed()
                    } else {
                        @Suppress("DEPRECATION")
                        super@PhotoLockActivity.onBackPressed()
                    }
                }
            }
        })
        glide = Glide.with(this)
        initializeViews()
        setupGestureDetection()
        registerPowerSavingReceiver()
        initializePreviewMode()
        initializePhotos()
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        if (isInitialized && !isDestroyed) {
            startPhotoDisplay()
        }
    }

    override fun onStop() {
        isActivityVisible = false
        mainHandler.removeCallbacks(photoChangeRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        isDestroyed = true
        cleanup()
        activityScope.cancel() // Instead of uiScope.cancel()
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (!isDestroyed) {
            gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
        } else {
            super.onTouchEvent(event)
        }
    }

    protected open fun initializeViews() {
        _backgroundImageView = findViewById(R.id.backgroundImageView)
        _overlayImageView = findViewById(R.id.overlayImageView)
    }

    private fun setupGestureDetection() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (isDestroyed) return false

                val diffY = e2.y - (e1?.y ?: 0f)
                val diffX = e2.x - (e1?.x ?: 0f)

                if (abs(diffY) > abs(diffX) &&
                    abs(diffY) > SWIPE_THRESHOLD &&
                    abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffY < 0) finishPreview()
                }
                return true
            }
        })
    }

    private fun initializePhotos() {
        if (isDestroyed) return

        activityScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (photoManager.initialize() && photoManager.loadPhotos()?.isNotEmpty() == true) {
                        withContext(Dispatchers.Main) {
                            if (!isDestroyed) {
                                isInitialized = true
                                startPhotoDisplay()
                            }
                        }
                    } else {
                        showError("Failed to load photos")
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error initializing photos", e)
                    showError("Error initializing photos: ${e.message}")
                }
            }
        }
    }

    private fun startPhotoDisplay() {
        if (!isInitialized || isDestroyed) return

        try {
            loadPhoto(currentPhotoIndex, backgroundImageView)
            scheduleNextPhotoChange()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting photo display", e)
        }
    }

    protected fun loadPhoto(index: Int, imageView: ImageView) {
        if (!isInitialized || isDestroyed) return

        try {
            val url = photoManager.getPhotoUrl(index)
            if (!isDestroyed && url != null) {
                glide.load(url)
                    .apply(createGlideOptions())
                    .into(imageView)
            } else {
                loadDefaultBackground(imageView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photo", e)
            loadDefaultBackground(imageView)
        }
    }

    private fun loadDefaultBackground(imageView: ImageView) {
        if (!isDestroyed) {
            glide.load(R.drawable.default_photo)
                .into(imageView)
        }
    }

    private fun transitionToNextPhoto() {
        if (!isActivityVisible || isDestroyed) return

        currentPhotoIndex = (currentPhotoIndex + 1) % photoManager.getPhotoCount()

        // Preload next photo
        activityScope.launch {  // Changed from uiScope to activityScope
            try {
                val nextIndex = (currentPhotoIndex + 1) % photoManager.getPhotoCount()
                photoManager.getPhotoUrl(nextIndex)?.let { url ->
                    if (!isDestroyed) {
                        glide.load(url)
                            .apply(createGlideOptions())
                            .preload()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading next photo", e)
            }
        }

        val transitionType = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("lock_screen_transition", "fade") ?: "fade"

        when (transitionType) {
            "fade" -> performFadeTransition()
            "slide" -> performSlideTransition()
            "zoom" -> performZoomTransition()
            else -> performFadeTransition()
        }
    }

    private fun performFadeTransition() {
        if (isDestroyed) return

        loadPhoto(currentPhotoIndex, overlayImageView)
        overlayImageView.alpha = 0f
        overlayImageView.visibility = View.VISIBLE

        ObjectAnimator.ofFloat(overlayImageView, "alpha", 0f, 1f).apply {
            duration = if (isPowerSaving) TRANSITION_DURATION_POWER_SAVING else TRANSITION_DURATION_NORMAL
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isDestroyed) {
                        backgroundImageView.setImageDrawable(overlayImageView.drawable)
                        overlayImageView.visibility = View.GONE
                    }
                }
            })
            start()
        }
    }

    private fun performSlideTransition() {
        if (isDestroyed) return

        loadPhoto(currentPhotoIndex, overlayImageView)
        overlayImageView.translationX = screenWidth.toFloat()
        overlayImageView.visibility = View.VISIBLE

        val duration = if (isPowerSaving) TRANSITION_DURATION_POWER_SAVING else TRANSITION_DURATION_NORMAL

        val slideIn = ObjectAnimator.ofFloat(
            overlayImageView,
            "translationX",
            screenWidth.toFloat(),
            0f
        ).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
        }

        val slideOut = ObjectAnimator.ofFloat(
            backgroundImageView,
            "translationX",
            0f,
            -screenWidth.toFloat()
        ).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
        }

        slideIn.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!isDestroyed) {
                    backgroundImageView.setImageDrawable(overlayImageView.drawable)
                    overlayImageView.visibility = View.GONE
                    backgroundImageView.translationX = 0f
                }
            }
        })

        slideIn.start()
        slideOut.start()
    }

    private fun performZoomTransition() {
        if (isDestroyed) return

        loadPhoto(currentPhotoIndex, overlayImageView)
        overlayImageView.scaleX = 1.5f
        overlayImageView.scaleY = 1.5f
        overlayImageView.alpha = 0f
        overlayImageView.visibility = View.VISIBLE

        val duration = if (isPowerSaving) TRANSITION_DURATION_POWER_SAVING else TRANSITION_DURATION_NORMAL

        ObjectAnimator.ofFloat(overlayImageView, "alpha", 0f, 1f).apply {
            this.duration = duration
            start()
        }

        ObjectAnimator.ofFloat(overlayImageView, "scaleX", 1.5f, 1f).apply {
            this.duration = duration
            start()
        }

        ObjectAnimator.ofFloat(overlayImageView, "scaleY", 1.5f, 1f).apply {
            this.duration = duration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isDestroyed) {
                        backgroundImageView.setImageDrawable(overlayImageView.drawable)
                        overlayImageView.visibility = View.GONE
                    }
                }
            })
            start()
        }
    }

    private fun registerPowerSavingReceiver() {
        try {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            registerReceiver(powerSavingReceiver, filter)
            updatePowerSavingMode()
        } catch (e: Exception) {
            Log.e(TAG, "Error registering power saving receiver", e)
        }
    }

    private fun updatePowerSavingMode() {
        if (isDestroyed) return

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            isPowerSaving = powerManager.isPowerSaveMode

            window.apply {
                if (isPowerSaving) {
                    clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                } else {
                    addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                }
            }

            updateViewsForPowerMode()
            setupImageLoadingQuality()
            scheduleNextPhotoChange()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating power saving mode", e)
        }
    }

    private fun updateViewsForPowerMode() {
        if (isDestroyed) return
    }

    private fun setupImageLoadingQuality() {
        if (!isDestroyed) {
            glide.setDefaultRequestOptions(createGlideOptions())
        }
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        if (!isDestroyed) {
            mainHandler.post {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (!isPreviewMode) {
                    finish()
                }
            }
        }
    }

    private fun scheduleNextPhotoChange() {
        mainHandler.removeCallbacks(photoChangeRunnable)

        if (isActivityVisible && isInitialized && !isDestroyed) {
            val baseInterval = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt("lock_screen_interval", 10) * 1000L
            val interval = if (isPowerSaving) baseInterval * POWER_SAVING_SCALE else baseInterval
            mainHandler.postDelayed(photoChangeRunnable, interval)
        }
    }

    private fun createGlideOptions() = RequestOptions().apply {
        error(R.drawable.default_photo)
        diskCacheStrategy(DiskCacheStrategy.ALL)
        format(if (isPowerSaving) DecodeFormat.PREFER_RGB_565 else DecodeFormat.PREFER_ARGB_8888)
        if (isPowerSaving) {
            override(800, 800)
        }
    }

    protected open fun cleanup() {
        try {
            unregisterReceiver(powerSavingReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        mainHandler.removeCallbacks(photoChangeRunnable)

        if (!isDestroyed) {
            try {
                _backgroundImageView?.let { glide.clear(it) }
                _overlayImageView?.let { glide.clear(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing Glide resources", e)
            }
        }

        isInitialized = false

        // Clear view references
        _backgroundImageView = null
        _overlayImageView = null
    }

    protected fun initializePreviewMode() {
        if (intent.getBooleanExtra("preview_mode", false)) {
            isPreviewMode = true
            previewStartTime = System.currentTimeMillis()
            checkPreviewDuration()
        }
    }

    private fun checkPreviewDuration() {
        if (isPreviewMode && System.currentTimeMillis() - previewStartTime > MAX_PREVIEW_DURATION) {
            finishPreview()
        }
    }

    private fun finishPreview() {
        if (isPreviewMode) {
            isPreviewMode = false
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}