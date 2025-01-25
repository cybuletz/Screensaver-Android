package com.example.screensaver.preview

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.example.screensaver.R
import com.example.screensaver.databinding.ActivityPreviewBinding
import com.example.screensaver.transitions.PhotoTransitionManager
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var transitionManager: PhotoTransitionManager

    @Inject
    lateinit var previewManager: PreviewManager

    @Inject
    lateinit var preferences: AppPreferences

    private lateinit var primaryImageView: ImageView
    private lateinit var secondaryImageView: ImageView
    private lateinit var previewNotice: TextView

    private var currentImageView: ImageView? = null
    private var isPreviewActive = false

    companion object {
        private const val TAG = "PreviewActivity"
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
        private const val TAP_TIMEOUT = 100L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeViews()
        setupGestureDetection()
        initializePreview()
        observePreviewState()
    }

    private fun initializeViews() {
        with(binding) {
            primaryImageView = imageViewPrimary
            secondaryImageView = imageViewSecondary
            previewNotice = textViewPreviewNotice

            secondaryImageView.alpha = 0f
            currentImageView = primaryImageView
        }

        transitionManager = PhotoTransitionManager(binding.root, preferences)
        transitionManager.initialize(primaryImageView, secondaryImageView)
    }

    private fun setupGestureDetection() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isPreviewActive) {
                    endPreview()
                }
                return true
            }

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
                    abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffY < 0) {
                        endPreview()
                    }
                }
                return true
            }
        })
    }

    private fun initializePreview() {
        if (!previewManager.canStartPreview()) {
            showPreviewCooldownMessage()
            finish()
            return
        }

        lifecycleScope.launch {
            previewManager.startPreview()
            isPreviewActive = true
            binding.previewNotice.visibility = View.VISIBLE
        }
    }

    private fun observePreviewState() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                previewManager.canStartPreview.collect { canStart ->
                    if (!canStart && isPreviewActive) {
                        endPreview()
                    }
                }
            }
        }
    }

    private fun showPreviewCooldownMessage() {
        val timeRemaining = previewManager.getTimeUntilNextPreviewAllowed() / 1000
        val message = getString(R.string.preview_cooldown_message, timeRemaining)
        binding.previewNotice.text = message
    }

    private fun endPreview() {
        isPreviewActive = false
        previewManager.endPreview()
        finish()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (isPreviewActive) {
            endPreview()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isPreviewActive) {
            previewManager.endPreview()
        }
    }
}