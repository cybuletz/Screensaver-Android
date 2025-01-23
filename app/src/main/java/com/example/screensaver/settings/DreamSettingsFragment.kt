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
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fragment for configuring screensaver settings
 */
@AndroidEntryPoint
class DreamSettingsFragment : Fragment() {

    private var _binding: FragmentDreamSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoViewModel by viewModels()

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
        // Setup transition duration slider
        binding.sliderTransitionDuration.apply {
            valueFrom = MIN_TRANSITION_DURATION.toFloat()
            valueTo = MAX_TRANSITION_DURATION.toFloat()
            value = preferences.getTransitionDuration().toFloat()
            setLabelFormatter { value -> "${value.toInt()} seconds" }
        }

        // Setup photo quality selector
        binding.radioGroupQuality.check(when (preferences.getPhotoQuality()) {
            0 -> R.id.radioLowQuality
            1 -> R.id.radioMediumQuality
            2 -> R.id.radioHighQuality
            else -> R.id.radioMediumQuality
        })

        // Setup switches
        binding.switchRandomOrder.isChecked = preferences.getRandomOrder()
        binding.switchShowClock.isChecked = preferences.getShowClock()
        binding.switchShowLocation.isChecked = preferences.getShowLocation()
        binding.switchShowDate.isChecked = preferences.getShowDate()
        binding.switchEnableTransitions.isChecked = preferences.getEnableTransitions()
        binding.switchDarkMode.isChecked = preferences.getDarkMode()
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe selected albums
                viewModel.selectedAlbums.collect { albums ->
                    binding.textSelectedAlbums.text = getString(
                        R.string.selected_albums_count,
                        albums.size
                    )
                }
            }
        }
    }

    private fun setupListeners() {
        // Transition duration changes
        binding.sliderTransitionDuration.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                preferences.setTransitionDuration(value.toInt())
                updatePreview()
            }
        }

        // Photo quality changes
        binding.radioGroupQuality.setOnCheckedChangeListener { _, checkedId ->
            val quality = when (checkedId) {
                R.id.radioLowQuality -> 0
                R.id.radioMediumQuality -> 1
                R.id.radioHighQuality -> 2
                else -> DEFAULT_PHOTO_QUALITY
            }
            preferences.setPhotoQuality(quality)
            updatePreview()
        }

        // Switch listeners
        binding.switchRandomOrder.setOnCheckedChangeListener { _, isChecked ->
            preferences.setRandomOrder(isChecked)
            updatePreview()
        }

        binding.switchShowClock.setOnCheckedChangeListener { _, isChecked ->
            preferences.setShowClock(isChecked)
            updatePreview()
        }

        binding.switchShowLocation.setOnCheckedChangeListener { _, isChecked ->
            preferences.setShowLocation(isChecked)
            updatePreview()
        }

        binding.switchShowDate.setOnCheckedChangeListener { _, isChecked ->
            preferences.setShowDate(isChecked)
            updatePreview()
        }

        binding.switchEnableTransitions.setOnCheckedChangeListener { _, isChecked ->
            preferences.setEnableTransitions(isChecked)
            binding.sliderTransitionDuration.isEnabled = isChecked
            updatePreview()
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            preferences.setDarkMode(isChecked)
            updateTheme(isChecked)
        }

        // Album selection
        binding.buttonSelectAlbums.setOnClickListener {
            viewModel.navigateToAlbumSelection()
        }

        // Reset settings
        binding.buttonResetSettings.setOnClickListener {
            resetSettings()
        }

        // Preview
        binding.buttonPreview.setOnClickListener {
            viewModel.startPreview()
        }
    }

    private fun updatePreview() {
        viewModel.updatePreviewSettings(
            transitionDuration = binding.sliderTransitionDuration.value.toInt(),
            photoQuality = when (binding.radioGroupQuality.checkedRadioButtonId) {
                R.id.radioLowQuality -> 0
                R.id.radioMediumQuality -> 1
                R.id.radioHighQuality -> 2
                else -> DEFAULT_PHOTO_QUALITY
            },
            randomOrder = binding.switchRandomOrder.isChecked,
            showClock = binding.switchShowClock.isChecked,
            showLocation = binding.switchShowLocation.isChecked,
            showDate = binding.switchShowDate.isChecked,
            enableTransitions = binding.switchEnableTransitions.isChecked
        )
    }

    private fun resetSettings() {
        // Reset all settings to defaults
        binding.sliderTransitionDuration.value = DEFAULT_TRANSITION_DURATION.toFloat()
        binding.radioGroupQuality.check(R.id.radioMediumQuality)
        binding.switchRandomOrder.isChecked = true
        binding.switchShowClock.isChecked = true
        binding.switchShowLocation.isChecked = false
        binding.switchShowDate.isChecked = true
        binding.switchEnableTransitions.isChecked = true
        binding.switchDarkMode.isChecked = false

        // Update preferences
        preferences.apply {
            setTransitionDuration(DEFAULT_TRANSITION_DURATION)
            setPhotoQuality(DEFAULT_PHOTO_QUALITY)
            setRandomOrder(true)
            setShowClock(true)
            setShowLocation(false)
            setShowDate(true)
            setEnableTransitions(true)
            setDarkMode(false)
        }

        updatePreview()
        updateTheme(false)
    }

    private fun updateTheme(isDarkMode: Boolean) {
        // Delegate theme change to activity
        (activity as? SettingsActivity)?.updateTheme(isDarkMode)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}