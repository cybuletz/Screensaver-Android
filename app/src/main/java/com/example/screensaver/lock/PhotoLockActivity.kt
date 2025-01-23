package com.example.screensaver.lock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri  // Added Uri import
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.screensaver.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PhotoLockActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PhotoLockActivity"
    }

    private lateinit var backgroundImageView: ImageView
    private lateinit var overlayImageView: ImageView
    private lateinit var clockView: TextClock
    private lateinit var dateView: TextView
    private lateinit var previewNotice: TextView
    private lateinit var gestureDetector: GestureDetectorCompat

    private val handler = Handler(Looper.getMainLooper())
    private var isPreviewMode = false
    private var currentPhotoIndex = 0
    private var isPowerSaving = false
    private var isActivityVisible = false
    private var screenWidth: Int = 0

    private val powerSavingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> updatePowerSavingMode()
            }
        }
    }

    private val photoChangeRunnable = object : Runnable {
        override fun run() {
            if (isActivityVisible) {
                lifecycleScope.launch {
                    transitionToNextPhoto()
                    scheduleNextPhotoChange()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isPreviewMode = intent.getBooleanExtra("preview_mode", false)

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels

        if (!isPreviewMode) {
            setupSecureWindow()
        }

        setContentView(R.layout.activity_photo_lock)
        initializeViews()
        PhotoManager.getInstance(this).loadPhotos()
        setupGestureDetection()
        registerPowerSavingReceiver()

        // Initialize PhotoManager
        PhotoManager.getInstance(this).loadPhotos()

        // Load first photo after a short delay to ensure PhotoManager is ready
        handler.postDelayed({
            lifecycleScope.launch {
                startPhotoDisplay()
            }
        }, 500)
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        startPhotoDisplay()
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
        handler.removeCallbacks(photoChangeRunnable)
    }

    private fun setupSecureWindow() {
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

            if (isPowerSaving) {
                clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            }
        }
    }

    private fun registerPowerSavingReceiver() {
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        registerReceiver(powerSavingReceiver, filter)
        updatePowerSavingMode()
    }

    private fun updatePowerSavingMode() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isPowerSaving = powerManager.isPowerSaveMode

        if (isPowerSaving) {
            enablePowerSaving()
        } else {
            disablePowerSaving()
        }
    }

    private fun enablePowerSaving() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        clockView.alpha = 0.7f
        dateView.alpha = 0.7f
        setupImageLoadingQuality(lowQuality = true)
        scheduleNextPhotoChange()
    }

    private fun disablePowerSaving() {
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        clockView.alpha = 1.0f
        dateView.alpha = 1.0f
        setupImageLoadingQuality(lowQuality = false)
        scheduleNextPhotoChange()
    }

    private fun setupImageLoadingQuality(lowQuality: Boolean) {
        val requestOptions = RequestOptions().apply {
            if (lowQuality) {
                format(DecodeFormat.PREFER_RGB_565)
                override(800, 800)
            } else {
                format(DecodeFormat.PREFER_ARGB_8888)
            }
            diskCacheStrategy(DiskCacheStrategy.ALL)
        }
        Glide.with(this).setDefaultRequestOptions(requestOptions)
    }

    private fun initializeViews() {
        backgroundImageView = findViewById(R.id.backgroundImageView)
        overlayImageView = findViewById(R.id.overlayImageView)
        clockView = findViewById(R.id.lockScreenClock)
        dateView = findViewById(R.id.lockScreenDate)
        previewNotice = findViewById(R.id.previewNotice)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        clockView.visibility = if (prefs.getBoolean("lock_screen_clock", true))
            View.VISIBLE else View.GONE

        dateView.apply {
            visibility = if (prefs.getBoolean("lock_screen_date", true))
                View.VISIBLE else View.GONE
            text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        }

        previewNotice.visibility = if (isPreviewMode) View.VISIBLE else View.GONE
    }

    private fun setupGestureDetection() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun handleUnlock() {
        if (isPreviewMode) {
            finish()
        } else {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun startPhotoDisplay() {
        loadPhoto(currentPhotoIndex, backgroundImageView)
        scheduleNextPhotoChange()
    }

    private fun scheduleNextPhotoChange() {
        handler.removeCallbacks(photoChangeRunnable)

        if (isActivityVisible) {
            val baseInterval = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt("lock_screen_interval", 10) * 1000L
            val interval = if (isPowerSaving) baseInterval * 2 else baseInterval
            handler.postDelayed(photoChangeRunnable, interval)
        }
    }

    private fun loadPhoto(index: Int, imageView: ImageView) {
        try {
            PhotoManager.getInstance(this).getPhotoUrl(index)?.let { url ->
                val requestOptions = RequestOptions()
                    .error(R.drawable.default_background)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(if (isPowerSaving) DecodeFormat.PREFER_RGB_565 else DecodeFormat.PREFER_ARGB_8888)
                    .apply {
                        if (isPowerSaving) {
                            override(800, 800)
                        }
                    }

                Glide.with(this)
                    .load(url)
                    .apply(requestOptions)
                    .into(imageView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photo: ${e.message}")
            Glide.with(this)
                .load(R.drawable.default_background)
                .into(imageView)
        }
    }

    private fun transitionToNextPhoto() {
        if (!isActivityVisible) return

        currentPhotoIndex = (currentPhotoIndex + 1) % PhotoManager.getInstance(this).getPhotoCount()

        lifecycleScope.launch {
            PhotoManager.getInstance(this@PhotoLockActivity)
                .preloadNextPhoto((currentPhotoIndex + 1) % PhotoManager.getInstance(this@PhotoLockActivity).getPhotoCount())
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
            duration = if (isPowerSaving) 500L else 1000L
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

        val slideIn = ObjectAnimator.ofFloat(
            overlayImageView,
            "translationX",
            screenWidth.toFloat(),
            0f
        )
        val slideOut = ObjectAnimator.ofFloat(
            backgroundImageView,
            "translationX",
            0f,
            -screenWidth.toFloat()
        )

        val duration = if (isPowerSaving) 250L else 500L
        slideIn.duration = duration
        slideOut.duration = duration
        slideIn.interpolator = AccelerateDecelerateInterpolator()
        slideOut.interpolator = AccelerateDecelerateInterpolator()

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

        val duration = if (isPowerSaving) 250L else 500L

        val zoomIn = ObjectAnimator.ofFloat(overlayImageView, "alpha", 0f, 1f)
        val scaleDownX = ObjectAnimator.ofFloat(overlayImageView, "scaleX", 1.5f, 1f)
        val scaleDownY = ObjectAnimator.ofFloat(overlayImageView, "scaleY", 1.5f, 1f)

        zoomIn.duration = duration
        scaleDownX.duration = duration
        scaleDownY.duration = duration

        zoomIn.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                backgroundImageView.setImageDrawable(overlayImageView.drawable)
                overlayImageView.visibility = View.GONE
            }
        })

        zoomIn.start()
        scaleDownX.start()
        scaleDownY.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(powerSavingReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver: ${e.message}")
        }
        handler.removeCallbacks(photoChangeRunnable)
        Glide.with(this).clear(backgroundImageView)
        Glide.with(this).clear(overlayImageView)
    }
}