package com.example.screensaver.preview

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.example.screensaver.R
import com.example.screensaver.databinding.ActivityPreviewBinding
import com.example.screensaver.models.Album
import com.example.screensaver.preview.PreviewState
import com.example.screensaver.transitions.PhotoTransitionManager
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.viewmodels.PhotoViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var transitionManager: PhotoTransitionManager

    private val photoViewModel: PhotoViewModel by viewModels()
    private val previewViewModel: PreviewViewModel by viewModels()

    @Inject
    lateinit var preferences: AppPreferences

    private lateinit var primaryImageView: ImageView
    private lateinit var secondaryImageView: ImageView
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
        startPreviewMode()
        observePreviewState()
        observePhotos()
    }

    private fun initializeViews() {
        with(binding) {
            primaryImageView = imageViewPrimary
            secondaryImageView = imageViewSecondary
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

    private fun startPreviewMode() {
        lifecycleScope.launch {
            if (!previewViewModel.isPreviewModeActive()) {
                val remainingPreviews = previewViewModel.getRemainingPreviews()
                if (remainingPreviews <= 0) {
                    showPreviewCooldownMessage()
                    finish()
                    return@launch
                }

                previewViewModel.startPreview()
            }

            val selectedAlbums = preferences.selectedAlbumsFlow.value.map { albumId ->
                Album.createPreviewAlbum(albumId)
            }
            photoViewModel.initialize(selectedAlbums)
            isPreviewActive = true
            binding.textViewPreviewNotice.visibility = View.VISIBLE
        }
    }

    private fun observePreviewState() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                previewViewModel.previewState.collect { state ->
                    when (state) {
                        is PreviewState.Cooldown -> {
                            showPreviewCooldownMessage()
                            finish()
                        }
                        is PreviewState.Error -> {
                            binding.textViewPreviewNotice.text = state.message
                            finish()
                        }
                        is PreviewState.Available -> {
                            if (photoViewModel.loadingState.value == PhotoViewModel.LoadingState.IDLE) {
                                val selectedAlbums = preferences.selectedAlbumsFlow.value.map { albumId ->
                                    Album.createPreviewAlbum(albumId)
                                }
                                photoViewModel.initialize(selectedAlbums)
                            }
                        }
                        is PreviewState.Initial -> {
                            // Handle initial state if needed
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                photoViewModel.loadingState.collect { state ->
                    when (state) {
                        PhotoViewModel.LoadingState.ERROR -> {
                            binding.progressBar.visibility = View.GONE
                        }
                        PhotoViewModel.LoadingState.LOADING -> {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        PhotoViewModel.LoadingState.SUCCESS -> {
                            binding.progressBar.visibility = View.GONE
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun observePhotos() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                photoViewModel.currentPhoto.observe(this@PreviewActivity) { photo ->
                    photo?.let {
                        Glide.with(this@PreviewActivity)
                            .load(it.baseUrl)
                            .into(currentImageView ?: primaryImageView)
                    }
                }
            }
        }
    }

    private fun showPreviewCooldownMessage() {
        val timeRemaining = previewViewModel.cooldownSeconds.value
        val message = getString(R.string.preview_cooldown_message, timeRemaining)
        binding.textViewPreviewNotice.text = message
    }

    private fun endPreview() {
        isPreviewActive = false
        photoViewModel.stop()
        previewViewModel.endPreview()
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
            photoViewModel.stop()
            previewViewModel.endPreview()
        }
        transitionManager.cleanup()
    }
}