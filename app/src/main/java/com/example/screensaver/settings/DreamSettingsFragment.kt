package com.example.screensaver.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.screensaver.R
import com.example.screensaver.databinding.FragmentDreamSettingsBinding
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.utils.ErrorHandler
import com.example.screensaver.viewmodels.PhotoViewModel
import com.example.screensaver.preview.PreviewActivity
import com.example.screensaver.preview.PreviewViewModel
import com.example.screensaver.preview.PreviewState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import android.content.Intent
import com.example.screensaver.AlbumSelectionActivity

/**
 * Fragment for configuring screensaver settings
 */
@AndroidEntryPoint
class DreamSettingsFragment : Fragment() {

    private var _binding: FragmentDreamSettingsBinding? = null
    private val binding get() = _binding!!

    private val photoViewModel: PhotoViewModel by viewModels()
    private val previewViewModel: PreviewViewModel by viewModels()

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var errorHandler: ErrorHandler

    companion object {
        private const val MIN_TRANSITION_DURATION = 5 // seconds
        private const val MAX_TRANSITION_DURATION = 300 // seconds
        private const val DEFAULT_TRANSITION_DURATION = 30 // seconds

        private const val MIN_PHOTO_QUALITY = 0 // low
        private const val MAX_PHOTO_QUALITY = 2 // high
        private const val DEFAULT_PHOTO_QUALITY = 1 // medium

        fun newInstance() = DreamSettingsFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDreamSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeSettings()
        setupListeners()
    }

    private fun setupUI() {
        val currentSettings = photoViewModel.getCurrentSettings()

        binding.apply {
            sliderTransitionDuration.apply {
                valueFrom = MIN_TRANSITION_DURATION.toFloat()
                valueTo = MAX_TRANSITION_DURATION.toFloat()
                value = currentSettings.transitionDuration.toFloat()
                setLabelFormatter { "${it.toInt()} seconds" }
            }

            radioGroupQuality.check(when (currentSettings.photoQuality) {
                MIN_PHOTO_QUALITY -> R.id.radioLowQuality
                MAX_PHOTO_QUALITY -> R.id.radioHighQuality
                else -> R.id.radioMediumQuality
            })

            switchRandomOrder.isChecked = currentSettings.randomOrder
            switchShowClock.isChecked = currentSettings.showClock
            switchShowLocation.isChecked = currentSettings.showLocation
            switchShowDate.isChecked = currentSettings.showDate
            switchEnableTransitions.isChecked = currentSettings.enableTransitions
            switchDarkMode.isChecked = currentSettings.darkMode

            sliderTransitionDuration.isEnabled = currentSettings.enableTransitions

            // Update preview button initial state
            updatePreviewButtonState(previewViewModel.getRemainingPreviews())
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                photoViewModel.selectedAlbums.collect { albums ->
                    binding.textSelectedAlbums.text = requireContext().resources.getQuantityString(
                        R.plurals.selected_albums_count,
                        albums.size,
                        albums.size
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                previewViewModel.previewState.collect { state ->
                    handlePreviewState(state)
                }
            }
        }
    }

    private fun handlePreviewState(state: PreviewState) {
        binding.buttonPreview.apply {
            when (state) {
                PreviewState.Initial -> {
                    isEnabled = true
                    setText(R.string.preview_button_text)
                }
                is PreviewState.Cooldown -> {
                    isEnabled = false
                    text = getString(R.string.preview_cooldown_message, state.remainingSeconds)
                }
                is PreviewState.Error -> {
                    isEnabled = false
                    text = state.message
                }
                is PreviewState.Available -> {
                    isEnabled = true
                    text = getString(R.string.preview_button_text_with_count, state.remainingPreviews)
                }
            }
        }
    }

    private fun updatePreviewButtonState(remainingPreviews: Int) {
        binding.buttonPreview.apply {
            isEnabled = remainingPreviews > 0
            text = if (remainingPreviews > 0) {
                getString(R.string.preview_button_text_with_count, remainingPreviews)
            } else {
                getString(R.string.preview_cooldown_active)
            }
        }
    }

    private fun setupListeners() {
        binding.apply {
            sliderTransitionDuration.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    updatePreview()
                }
            }

            radioGroupQuality.setOnCheckedChangeListener { _, _ ->
                updatePreview()
            }

            switchRandomOrder.setOnCheckedChangeListener { _, _ ->
                updatePreview()
            }

            switchShowClock.setOnCheckedChangeListener { _, _ ->
                updatePreview()
            }

            switchShowLocation.setOnCheckedChangeListener { _, _ ->
                updatePreview()
            }

            switchShowDate.setOnCheckedChangeListener { _, _ ->
                updatePreview()
            }

            switchEnableTransitions.setOnCheckedChangeListener { _, isChecked ->
                sliderTransitionDuration.isEnabled = isChecked
                updatePreview()
            }

            switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
                updatePreview()
            }

            buttonSelectAlbums.setOnClickListener {
                startActivity(Intent(requireContext(), AlbumSelectionActivity::class.java))
            }

            buttonPreview.setOnClickListener {
                if (previewViewModel.getRemainingPreviews() > 0) {
                    startActivity(Intent(requireContext(), PreviewActivity::class.java))
                } else {
                    lifecycleScope.launch {
                        errorHandler.handleError(IllegalStateException(getString(R.string.preview_limit_reached)), binding.root)
                    }
                }
            }

            buttonResetSettings.setOnClickListener {
                resetSettings()
            }
        }
    }

    private fun updatePreview() {
        binding.apply {
            photoViewModel.updatePreviewSettings(
                transitionDuration = sliderTransitionDuration.value.toInt(),
                photoQuality = when (radioGroupQuality.checkedRadioButtonId) {
                    R.id.radioLowQuality -> MIN_PHOTO_QUALITY
                    R.id.radioHighQuality -> MAX_PHOTO_QUALITY
                    else -> DEFAULT_PHOTO_QUALITY
                },
                randomOrder = switchRandomOrder.isChecked,
                showClock = switchShowClock.isChecked,
                showLocation = switchShowLocation.isChecked,
                showDate = switchShowDate.isChecked,
                enableTransitions = switchEnableTransitions.isChecked,
                darkMode = switchDarkMode.isChecked
            )
        }
    }

    private fun resetSettings() {
        binding.apply {
            sliderTransitionDuration.value = DEFAULT_TRANSITION_DURATION.toFloat()
            radioGroupQuality.check(R.id.radioMediumQuality)
            switchRandomOrder.isChecked = true
            switchShowClock.isChecked = true
            switchShowLocation.isChecked = false
            switchShowDate.isChecked = true
            switchEnableTransitions.isChecked = true
            switchDarkMode.isChecked = false
        }

        photoViewModel.updatePreviewSettings(
            transitionDuration = DEFAULT_TRANSITION_DURATION,
            photoQuality = DEFAULT_PHOTO_QUALITY,
            randomOrder = true,
            showClock = true,
            showLocation = false,
            showDate = true,
            enableTransitions = true,
            darkMode = false
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}