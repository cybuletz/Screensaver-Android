package com.example.screensaver.activities

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.preference.PreferenceManager
import com.example.screensaver.R
import com.example.screensaver.databinding.ActivityKioskBinding
import com.example.screensaver.lock.PhotoLockActivity
import com.example.screensaver.lock.PhotoLockScreenService
import com.example.screensaver.models.MediaItem
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class KioskActivity : PhotoLockActivity() {
    private lateinit var binding: ActivityKioskBinding
    private var backPressedTime: Long = 0
    private var backPressCount = 0
    private var controlsHandler = Handler(Looper.getMainLooper())
    private var exitDelay: Int = 5 // Default 5 seconds

    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    companion object {
        private const val TAG = "KioskActivity"
        private const val CONTROLS_HIDE_DELAY = 3000L // 3 seconds

        fun start(context: Context) {
            val intent = Intent(context, KioskActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        exitDelay = PreferenceManager.getDefaultSharedPreferences(this)
            .getInt("kiosk_exit_delay", 5)

        setupWindow()
        setupControls()
        initializeKioskMode()
    }

    private fun setupWindow() {
        super.setupWindow()
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun setupControls() {
        val allowedActions = PreferenceManager.getDefaultSharedPreferences(this)
            .getStringSet("kiosk_allowed_actions", setOf()) ?: setOf()

        // Show/hide controls based on allowed actions
        binding.brightnessControl.visibility =
            if (allowedActions.contains("brightness")) View.VISIBLE else View.GONE

        binding.nextPhotoButton.visibility =
            if (allowedActions.contains("photo_change")) View.VISIBLE else View.GONE

        binding.previousPhotoButton.visibility =
            if (allowedActions.contains("photo_change")) View.VISIBLE else View.GONE

        // Set up brightness control
        binding.brightnessControl.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateBrightness(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set up navigation buttons
        binding.nextPhotoButton.setOnClickListener {
            showNextPhoto()
            showControlsTemporarily()
        }

        binding.previousPhotoButton.setOnClickListener {
            showPreviousPhoto()
            showControlsTemporarily()
        }

        // Initial state
        hideControls()
    }

    protected fun showNextPhoto() {
        currentPhotoIndex = (currentPhotoIndex + 1) % photoManager.getPhotoCount()
        loadPhoto(currentPhotoIndex, backgroundImageView)
    }

    protected fun showPreviousPhoto() {
        currentPhotoIndex = if (currentPhotoIndex > 0) currentPhotoIndex - 1
        else photoManager.getPhotoCount() - 1
        loadPhoto(currentPhotoIndex, backgroundImageView)
    }


    private fun initializeKioskMode() {
        if (kioskPolicyManager.isKioskModeAllowed()) {
            startLockTask()
        } else {
            Log.w(TAG, "Kiosk mode not allowed")
            finish()
        }
    }

    override fun onPhotoLoaded(mediaItem: MediaItem) {
        super.onPhotoLoaded(mediaItem)
        showControlsTemporarily()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    private fun showControlsTemporarily() {
        binding.kioskControls.visibility = View.VISIBLE
        binding.kioskExitHint.visibility = View.VISIBLE

        controlsHandler.removeCallbacksAndMessages(null)
        controlsHandler.postDelayed({
            hideControls()
        }, CONTROLS_HIDE_DELAY)
    }

    private fun hideControls() {
        binding.kioskControls.visibility = View.GONE
        binding.kioskExitHint.visibility = View.GONE
    }

    private fun updateBrightness(brightness: Int) {
        val window = window
        val params = window.attributes
        params.screenBrightness = brightness / 100f
        window.attributes = params
    }

    override fun onBackPressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - backPressedTime > exitDelay * 1000) {
            backPressCount = 1
            backPressedTime = currentTime
            binding.kioskExitHint.text = getString(R.string.kiosk_exit_hint, exitDelay)
            showControlsTemporarily()
        } else {
            backPressCount++
            if (backPressCount >= exitDelay) {
                disableKioskMode()
            }
        }
    }

    private fun disableKioskMode() {
        try {
            stopLockTask()
            kioskPolicyManager.setKioskPolicies(false)

            // Update preferences
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean("kiosk_mode_enabled", false)
                .apply()

            // Notify service
            Intent(this, PhotoLockScreenService::class.java).also { intent ->
                intent.action = "STOP_KIOSK"
                startService(intent)
            }

            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling kiosk mode", e)
        }
    }

    override fun onDestroy() {
        controlsHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}