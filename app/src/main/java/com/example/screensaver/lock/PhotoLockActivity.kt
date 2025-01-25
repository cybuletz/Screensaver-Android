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
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.screensaver.R
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.PhotoSourceState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
open class PhotoLockActivity : AppCompatActivity() {
    // UI Components
    protected lateinit var backgroundImageView: ImageView
    private lateinit var overlayImageView: ImageView
    private lateinit var clockView: TextClock
    private lateinit var dateView: TextView
    private lateinit var previewNotice: TextView
    private lateinit var unlockHint: TextView
    private lateinit var gestureDetector: GestureDetectorCompat

    // Managers and Handlers
    @Inject
    lateinit var photoManager: GooglePhotosManager

    @Inject
    lateinit var photoSourceState: PhotoSourceState

    protected val handler = Handler(Looper.getMainLooper())

    // State Variables
    private var isPreviewMode = false
    protected var currentPhotoIndex = 0
    protected var isPowerSaving = false
    protected var isActivityVisible = false
    private var screenWidth: Int = 0
    protected var isInitialized = false

    companion object {
        private const val TAG = "PhotoLockActivity"
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
        private const val DEFAULT_INTERVAL = 10000L // 10 seconds
        private const val POWER_SAVING_SCALE = 2L // Double the interval in power saving mode
        private const val TRANSITION_DURATION_NORMAL = 1000L
        private const val TRANSITION_DURATION_POWER_SAVING = 500L
        private const val MAX_PREVIEW_DURATION = 30000L // 30 seconds max preview
    }

    private val powerSavingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> updatePowerSavingMode()
            }
        }
    }

    private val photoChangeRunnable = object : Runnable {
        override fun run() {
            if (isActivityVisible && isInitialized) {
                lifecycleScope.launch {
                    transitionToNextPhoto()
                    scheduleNextPhotoChange()
                }
            }
        }
    }

    private val exitPreviewCallback = {
        handler.removeCallbacks(photoChangeRunnable)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isPreviewMode = intent.getBooleanExtra("preview_mode", false)
        setContentView(R.layout.activity_photo_lock)
        initializeViews()
        setupWindow()
        setupGestureDetection()
        registerPowerSavingReceiver()
        initializePhotos()
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        if (isInitialized) {
            startPhotoDisplay()
            if (isPreviewMode) {
                checkPreviewDuration()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
        handler.removeCallbacks(photoChangeRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isPreviewMode && event.action == MotionEvent.ACTION_UP) {
            handleUnlock()
            return true
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    protected open fun setupWindow() {
        screenWidth = resources.displayMetrics.widthPixels
        window.apply {
            if (!isPreviewMode) {
                addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        updatePowerSavingMode()
    }

    protected open fun initializeViews() {
        backgroundImageView = findViewById(R.id.backgroundImageView)
        overlayImageView = findViewById(R.id.overlayImageView)
        clockView = findViewById(R.id.lockScreenClock)
        dateView = findViewById(R.id.lockScreenDate)
        previewNotice = findViewById(R.id.previewNotice)
        unlockHint = findViewById(R.id.unlockHint)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        clockView.visibility = if (prefs.getBoolean("lock_screen_clock", true))
            View.VISIBLE else View.GONE

        dateView.apply {
            visibility = if (prefs.getBoolean("lock_screen_date", true))
                View.VISIBLE else View.GONE
            text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        }

        if (isPreviewMode) {
            previewNotice.apply {
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(500).start()
            }
            unlockHint.apply {
                text = getString(R.string.tap_to_exit_preview)
                visibility = View.VISIBLE
            }
            photoSourceState.recordPreviewStarted()
        } else {
            previewNotice.visibility = View.GONE
            unlockHint.apply {
                text = getString(R.string.swipe_up_to_unlock)
                visibility = View.VISIBLE
            }
        }
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
                val diffY = e2.y - (e1?.y ?: 0f)
                val diffX = e2.x - (e1?.x ?: 0f)

                if (abs(diffY) > abs(diffX) &&
                    abs(diffY) > SWIPE_THRESHOLD &&
                    abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY < 0) handleUnlock()
                }
                return true
            }
        })
    }

    private fun initializePhotos() {
        lifecycleScope.launch {
            try {
                if (photoManager.initialize() && photoManager.loadPhotos()?.isNotEmpty() == true) {
                    isInitialized = true
                    startPhotoDisplay()
                } else {
                    showError("Failed to load photos")
                }
            } catch (e: Exception) {
                showError("Error initializing photos: ${e.message}")
            }
        }
    }

    private fun checkPreviewDuration() {
        if (isPreviewMode && photoSourceState.getTimeSinceLastPreview() > MAX_PREVIEW_DURATION) {
            handleUnlock()
        }
    }

    override fun onBackPressed() {
        if (isPreviewMode) {
            finish()
        } else {
            super.onBackPressed()
        }
    }

    private fun startPhotoDisplay() {
        if (!isInitialized) return
        loadPhoto(currentPhotoIndex, backgroundImageView)
        scheduleNextPhotoChange()
    }

    protected fun loadPhoto(index: Int, imageView: ImageView) {
        if (!isInitialized) return

        try {
            val url = photoManager.getPhotoUrl(index)
            if (url != null) {
                Glide.with(this)
                    .load(url as String)
                    .apply(createGlideOptions())
                    .into(imageView)
            } else {
                loadDefaultBackground(imageView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photo: ${e.message}")
            loadDefaultBackground(imageView)
        }
    }

    private fun loadDefaultBackground(imageView: ImageView) {
        Glide.with(this)
            .load(R.drawable.default_background)
            .into(imageView)
    }

    private fun transitionToNextPhoto() {
        if (!isActivityVisible) return

        currentPhotoIndex = (currentPhotoIndex + 1) % photoManager.getPhotoCount()

        lifecycleScope.launch {
            val nextIndex = (currentPhotoIndex + 1) % photoManager.getPhotoCount()
            photoManager.getPhotoUrl(nextIndex)?.let { url ->
                Glide.with(this@PhotoLockActivity)
                    .load(url)
                    .apply(createGlideOptions())
                    .preload()
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
        loadPhoto(currentPhotoIndex, overlayImageView)
        overlayImageView.alpha = 0f
        overlayImageView.visibility = View.VISIBLE

        ObjectAnimator.ofFloat(overlayImageView, "alpha", 0f, 1f).apply {
            duration = if (isPowerSaving) TRANSITION_DURATION_POWER_SAVING else TRANSITION_DURATION_NORMAL
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    backgroundImageView.setImageDrawable(overlayImageView.drawable)
                    overlayImageView.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun performSlideTransition() {
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
                backgroundImageView.setImageDrawable(overlayImageView.drawable)
                overlayImageView.visibility = View.GONE
                backgroundImageView.translationX = 0f
            }
        })

        slideIn.start()
        slideOut.start()
    }

    private fun performZoomTransition() {
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
                    backgroundImageView.setImageDrawable(overlayImageView.drawable)
                    overlayImageView.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun registerPowerSavingReceiver() {
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        registerReceiver(powerSavingReceiver, filter)
        updatePowerSavingMode()
    }

    private fun updatePowerSavingMode() {
        if (!::clockView.isInitialized) return

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
    }

    private fun updateViewsForPowerMode() {
        if (!::clockView.isInitialized || !::dateView.isInitialized || !::unlockHint.isInitialized) return

        val alpha = if (isPowerSaving) 0.7f else 1.0f
        clockView.alpha = alpha
        dateView.alpha = alpha
        unlockHint.alpha = alpha
    }

    private fun setupImageLoadingQuality() {
        Glide.with(this).setDefaultRequestOptions(createGlideOptions())
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (!isPreviewMode) {
            finish()
        }
    }

    protected open fun handleUnlock() {
        if (isPreviewMode) {
            // Stop preview mode and notify the service
            val intent = Intent(this, PhotoLockScreenService::class.java).apply {
                action = "STOP_PREVIEW"
            }
            startService(intent)
            finish()
        } else {
            // Normal unlock behavior
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun scheduleNextPhotoChange() {
        handler.removeCallbacks(photoChangeRunnable)

        if (isActivityVisible && isInitialized) {
            val baseInterval = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt("lock_screen_interval", 10) * 1000L
            val interval = if (isPowerSaving) baseInterval * POWER_SAVING_SCALE else baseInterval
            handler.postDelayed(photoChangeRunnable, interval)
        }
    }

    private fun createGlideOptions() = RequestOptions().apply {
        error(R.drawable.default_background)
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
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }

        handler.removeCallbacks(photoChangeRunnable)

        if (isPreviewMode) {
            val intent = Intent(this, PhotoLockScreenService::class.java).apply {
                action = "STOP_PREVIEW"
            }
            startService(intent)
        }

        Glide.with(this).apply {
            clear(backgroundImageView)
            clear(overlayImageView)
        }

        isInitialized = false
    }
}