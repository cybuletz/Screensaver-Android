package com.example.screensaver.settings

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.screensaver.R
import com.example.screensaver.databinding.ActivitySetupWizardBinding
import com.example.screensaver.data.AppDataManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant

@AndroidEntryPoint
class SetupWizardActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupWizardBinding

    private val photoSelectionState: PhotoSelectionState by viewModels()

    @Inject
    lateinit var appDataManager: AppDataManager

    private val sourceSelectionState: SourceSelectionState by viewModels()
    private lateinit var pagerAdapter: WizardPagerAdapter

    companion object {
        const val TOTAL_STEPS = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupStepIndicator()
        setupWizardNavigation()
        observeState()
        observeSourceSelection()
    }

    private fun setupViewPager() {
        pagerAdapter = WizardPagerAdapter(this)
        binding.wizardViewPager.apply {
            adapter = pagerAdapter
            isUserInputEnabled = false // Prevent swipe navigation
        }
    }

    private fun setupWizardNavigation() {
        binding.buttonNext.setOnClickListener {
            when (binding.wizardViewPager.currentItem) {
                0 -> handleSourceSelectionNext()
                1 -> handlePhotoSelectionNext()
                2 -> finishSetup()
            }
        }

        binding.buttonBack.setOnClickListener {
            if (binding.wizardViewPager.currentItem > 0) {
                binding.wizardViewPager.currentItem--
            } else {
                showExitDialog()
            }
        }
    }

    private fun handleSourceSelectionNext() {
        if (sourceSelectionState.isValid()) {
            binding.wizardViewPager.currentItem = 1
            updateStepIndicator(1)
        } else {
            showError(getString(R.string.error_no_source_selected))
        }
    }

    fun moveToNextStep() {
        binding.wizardViewPager.currentItem = 1
        updateStepIndicator(1)
    }

    fun handlePhotoSelectionNext() {
        if (photoSelectionState.isValid()) {
            binding.wizardViewPager.currentItem = 2
            updateStepIndicator(2)
        } else {
            showError(getString(R.string.error_no_photos_selected))
        }
    }

    private fun showExitDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_setup)
            .setMessage(R.string.exit_setup_message)
            .setPositiveButton(R.string.exit) { _, _ -> finish() }
            .setNegativeButton(R.string.continue_setup, null)
            .show()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun setupStepIndicator() {
        binding.stepIndicator.max = TOTAL_STEPS
        updateStepIndicator(0)
    }

    private fun updateStepIndicator(step: Int) {
        binding.stepIndicator.progress = step + 1
        binding.stepText.text = getString(R.string.step_format, step + 1, TOTAL_STEPS)
    }

    private fun observeState() {
        lifecycleScope.launch {
            // Observe ViewPager page changes
            binding.wizardViewPager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        updateStepIndicator(position)
                        updateNavigationButtons(position)
                    }
                }
            )
        }
    }

    private fun updateNavigationButtons(position: Int) {
        binding.buttonBack.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        binding.buttonNext.text = when (position) {
            TOTAL_STEPS - 1 -> getString(R.string.finish)
            else -> getString(R.string.next)
        }
    }

    private fun observeSourceSelection() {
        lifecycleScope.launch {
            sourceSelectionState.selectedSources.collect { sources ->
                binding.buttonNext.isEnabled = sources.isNotEmpty()
            }
        }
    }

    fun completeSetup() {
        finishSetup()
    }

    private fun finishSetup() {
        lifecycleScope.launch {
            try {
                // Save selected sources and photos
                appDataManager.updateState { currentState ->
                    currentState.copy(
                        photoSources = sourceSelectionState.selectedSources.value,
                        selectedAlbums = photoSelectionState.selectedPhotos.value,
                        isScreensaverReady = true,
                        lastModified = Instant.now().epochSecond
                    )
                }

                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                showError(getString(R.string.setup_save_error))
            }
        }
    }
}