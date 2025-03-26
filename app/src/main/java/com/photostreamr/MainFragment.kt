package com.photostreamr

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.screensaver.databinding.FragmentMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.view.animation.AnimationUtils
import com.google.android.material.snackbar.Snackbar
import androidx.preference.PreferenceManager
import com.example.screensaver.ui.PhotoDisplayManager
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class MainFragment : Fragment() {
    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var photoSourceState: PhotoSourceState

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var photoManager: PhotoRepository

    private var isPhotoDisplayActive = false

    companion object {
        private const val TAG = "MainFragment"
        private const val MIN_PREVIEW_INTERVAL = 5000L // 5 seconds between previews
        private const val KEY_PHOTO_DISPLAY_ACTIVE = "photo_display_active"
        private const val KEY_PREVIEW_ENABLED = "preview_enabled"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (photoSourceState.isScreensaverReady()) {
            setupPhotoDisplay()
            initializeDisplayMode()
            setupViews()
            observePhotoManager()
        } else {
            setupViews()
        }

        savedInstanceState?.let {
            if (it.getBoolean(KEY_PHOTO_DISPLAY_ACTIVE, false)) {
                startPreviewMode()
            }
        }
    }

    private fun observePhotoManager() {
        viewLifecycleOwner.lifecycleScope.launch {
            photoManager.loadingState.collectLatest { state ->
                when (state) {
                    PhotoRepository.LoadingState.SUCCESS -> {
                        binding.loadingIndicator.visibility = View.GONE
                        if (photoManager.getPhotoCount() > 0) {
                            updatePreviewButtonState()
                        }
                    }
                    PhotoRepository.LoadingState.LOADING -> {
                        binding.loadingIndicator.visibility = View.VISIBLE
                    }
                    PhotoRepository.LoadingState.ERROR -> {
                        binding.loadingIndicator.visibility = View.GONE
                        showError("Error loading photos")
                    }
                    PhotoRepository.LoadingState.IDLE -> {
                        binding.loadingIndicator.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun setupPhotoDisplay() {
        val shouldRestartDisplay = isPhotoDisplayActive

        photoDisplayManager.initialize(
            PhotoDisplayManager.Views(
                primaryView = binding.photoPreview,
                overlayView = binding.photoPreviewOverlay,
                locationView = binding.locationOverlay,
                loadingIndicator = binding.loadingIndicator,
                loadingMessage = binding.loadingMessage,
                container = binding.screensaverContainer,
                overlayMessageContainer = binding.overlayMessageContainer,
                overlayMessageText = binding.overlayMessageText,
                backgroundLoadingIndicator = binding.backgroundLoadingIndicator
            ),
            viewLifecycleOwner.lifecycleScope
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        photoDisplayManager.updateSettings(
            transitionDuration = prefs.getInt("transition_duration", 1000).toLong(),
            showLocation = prefs.getBoolean("show_location", false),
            isRandomOrder = prefs.getBoolean("random_order", false)
        )

        if (shouldRestartDisplay) {
            binding.screensaverContainer.visibility = View.VISIBLE
            photoDisplayManager.startPhotoDisplay()
        }
    }

    private fun setupViews() {
        binding.previewButton.apply {
            setOnClickListener {
                if (canStartPreview()) {
                    startPreviewMode()
                } else {
                    showPreviewCooldownMessage()
                }
            }
            visibility = if (photoSourceState.isScreensaverReady()) View.VISIBLE else View.GONE
        }

        binding.screensaverReadyCard.apply {
            visibility = if (photoSourceState.isScreensaverReady()) View.VISIBLE else View.GONE
        }
    }

    private fun enablePreviewButton() {
        binding.previewButton.apply {
            isEnabled = true
            alpha = 1.0f
            startAnimation(AnimationUtils.loadAnimation(context, android.R.anim.fade_in))
        }
    }

    private fun canStartPreview(): Boolean {
        return photoSourceState.getTimeSinceLastPreview() > MIN_PREVIEW_INTERVAL
    }

    private fun showPreviewCooldownMessage() {
        val remainingTime = (MIN_PREVIEW_INTERVAL - photoSourceState.getTimeSinceLastPreview()) / 1000
        Snackbar.make(
            binding.root,
            getString(R.string.preview_cooldown_message, remainingTime),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun startPreviewMode() {
        if (!isPhotoDisplayActive && photoSourceState.isScreensaverReady()) {
            isPhotoDisplayActive = true
            binding.screensaverContainer.visibility = View.VISIBLE
            photoDisplayManager.startPhotoDisplay()
            photoSourceState.recordPreviewStarted()
        }
    }

    private fun startPhotoDisplay() {
        Log.d(TAG, "Starting photo display")
        binding.screensaverContainer.visibility = View.VISIBLE
        photoDisplayManager.startPhotoDisplay()
        isPhotoDisplayActive = true
    }

    private fun stopPhotoDisplay() {
        Log.d(TAG, "Stopping photo display")
        photoDisplayManager.stopPhotoDisplay()
        isPhotoDisplayActive = false
        binding.screensaverContainer.visibility = View.GONE
    }

    private fun initializeDisplayMode() {
        val isReady = photoSourceState.isScreensaverReady()
        Log.d(TAG, "Initializing display mode. Photos ready: $isReady")

        if (isReady) {
            binding.screensaverContainer.visibility = View.VISIBLE
            binding.screensaverReadyCard.visibility = View.VISIBLE
            startPhotoDisplay()
        } else {
            binding.screensaverContainer.visibility = View.GONE
            binding.screensaverReadyCard.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun updatePreviewButtonState() {
        if (photoSourceState.isScreensaverReady()) {
            binding.previewButton.visibility = View.VISIBLE
            binding.screensaverReadyCard.visibility = View.VISIBLE
            if (canStartPreview()) {
                enablePreviewButton()
            } else {
                binding.previewButton.isEnabled = false
                binding.previewButton.alpha = 0.5f
            }
        } else {
            binding.previewButton.visibility = View.GONE
            binding.screensaverReadyCard.visibility = View.GONE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PHOTO_DISPLAY_ACTIVE, isPhotoDisplayActive)
    }

    override fun onResume() {
        super.onResume()
        if (isPhotoDisplayActive) {
            startPhotoDisplay()
        }
        updatePreviewButtonState()
    }

    override fun onPause() {
        if (isPhotoDisplayActive) {
            stopPhotoDisplay()
        }
        super.onPause()
    }

    override fun onDestroyView() {
        photoDisplayManager.cleanup()
        super.onDestroyView()
        _binding = null
    }
}