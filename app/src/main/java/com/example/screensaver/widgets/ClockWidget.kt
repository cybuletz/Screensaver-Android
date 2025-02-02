package com.example.screensaver.widgets

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.*
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.screensaver.widgets.WidgetConfig
import com.example.screensaver.R
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale


class ClockWidget(
    private val container: ViewGroup,
    var config: WidgetConfig.ClockConfig
) : ScreenWidget {
    private var binding: ClockWidgetBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormatter = SimpleDateFormat("", Locale.getDefault())
    private var isVisible = false

    companion object {
        private const val TAG = "ClockWidget"
        private const val UPDATE_INTERVAL = 1000L // 1 second
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDateTime()
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }

    override fun init() {
        try {
            Log.d(TAG, "Initializing ClockWidget with config: $config")
            binding = ClockWidgetBinding(container).apply {
                Log.d(TAG, "Creating binding for container: $container")
                inflate()
                Log.d(TAG, "Binding inflated")
                setupViews()
                Log.d(TAG, "Views setup complete")
            }
            updateConfiguration(config)

            // Force show if needed
            if (config.showClock) {
                Log.d(TAG, "Config shows clock is enabled, showing widget")
                show()
            } else {
                Log.d(TAG, "Config shows clock is disabled, hiding widget")
                hide()
            }

            Log.d(TAG, "Clock widget initialization complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ClockWidget", e)
        }
    }

    private fun setupViews() {
        binding?.let { binding ->
            val rootView = binding.getRootView() ?: return
            val layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rootView.layoutParams = layoutParams
            container.addView(rootView)
            updatePosition(config.position)
            rootView.visibility = if (isVisible) View.VISIBLE else View.GONE
        }
    }

    private fun updatePosition(position: WidgetPosition) {
        val params = binding?.getRootView()?.layoutParams as? ConstraintLayout.LayoutParams ?: return

        // Clear existing constraints
        params.apply {
            topToTop = ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            startToStart = ConstraintLayout.LayoutParams.UNSET
            endToEnd = ConstraintLayout.LayoutParams.UNSET
            horizontalBias = 0.5f
        }

        // Apply new constraints based on position
        when (position) {
            WidgetPosition.TOP_START -> {
                params.apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
            WidgetPosition.TOP_CENTER -> {
                params.apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
            WidgetPosition.TOP_END -> {
                params.apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
            WidgetPosition.BOTTOM_START -> {
                params.apply {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
            WidgetPosition.BOTTOM_CENTER -> {
                params.apply {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
            WidgetPosition.BOTTOM_END -> {
                params.apply {
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
        }

        // Apply margins
        val margin = binding?.getRootView()?.resources?.getDimensionPixelSize(R.dimen.widget_margin) ?: 16
        params.setMargins(margin, margin, margin, margin)

        binding?.getRootView()?.layoutParams = params
    }

    override fun update() {
        updateDateTime()
    }

    override fun updateConfiguration(config: WidgetConfig) {
        Log.d(TAG, "Updating configuration: $config")
        try {
            if (config !is WidgetConfig.ClockConfig) {
                Log.e(TAG, "Invalid config type")
                return
            }

            this.config = config

            binding?.getClockView()?.apply {
                // Set clock format based on 12/24 hour preference
                format24Hour = "HH:mm"
                format12Hour = "hh:mm a"
                // Use DateFormat to set 24-hour mode
                setFormat12Hour(if (config.use24Hour) null else "hh:mm a")
                setFormat24Hour(if (config.use24Hour) "HH:mm" else null)
                Log.d(TAG, "Clock format updated - 24hour: ${config.use24Hour}")
            }

            binding?.getDateView()?.apply {
                try {
                    val dateFormat = SimpleDateFormat(config.dateFormat, Locale.getDefault())
                    text = dateFormat.format(Date())
                    Log.d(TAG, "Date format updated: ${config.dateFormat}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting date format", e)
                    // Use safe default
                    text = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())
                }
            }

            if (isVisible) {
                show()
            } else {
                hide()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating configuration", e)
        }
    }

    private fun updateDateTime() {
        if (!isVisible) return

        binding?.let { binding ->
            binding.getDateView()?.text = dateFormatter.format(Date())
        }
    }

    override fun show() {
        Log.d(TAG, "show() called with config: $config")
        isVisible = true
        binding?.let { binding ->
            Log.d(TAG, "Binding exists")
            val rootView = binding.getRootView()
            rootView?.apply {
                post {
                    visibility = View.VISIBLE
                    bringToFront()

                    val params = layoutParams as? ConstraintLayout.LayoutParams
                    params?.apply {
                        // Update constraints based on position
                        clearAllConstraints()

                    // Set new constraints based on position
                    when (config.position) {
                        WidgetPosition.TOP_START -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.TOP_CENTER -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.TOP_END -> {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.BOTTOM_START -> {
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.BOTTOM_CENTER -> {
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        WidgetPosition.BOTTOM_END -> {
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                    }

                    // Set margins for all positions
                        setMargins(32, 32, 32, 32)
                    }
                    layoutParams = params

                    requestLayout()
                    invalidate()

                    (parent as? ViewGroup)?.invalidate()
                    Log.d(TAG, "Root view layout updated on UI thread")
                }
            } ?: Log.e(TAG, "Root view is null")

            binding.getClockView()?.apply {
                visibility = if (config.showClock) View.VISIBLE else View.GONE
                Log.d(TAG, "Clock visibility set to: ${if (config.showClock) "VISIBLE" else "GONE"}")
            }

            binding.getDateView()?.apply {
                visibility = if (config.showDate) View.VISIBLE else View.GONE
                Log.d(TAG, "Date visibility set to: ${if (config.showDate) "VISIBLE" else "GONE"}")
            }
        } ?: Log.e(TAG, "Binding is null in show()")
        startUpdates()
    }

    private fun ConstraintLayout.LayoutParams.clearAllConstraints() {
        topToTop = ConstraintLayout.LayoutParams.UNSET
        topToBottom = ConstraintLayout.LayoutParams.UNSET
        bottomToTop = ConstraintLayout.LayoutParams.UNSET
        bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        startToStart = ConstraintLayout.LayoutParams.UNSET
        startToEnd = ConstraintLayout.LayoutParams.UNSET
        endToStart = ConstraintLayout.LayoutParams.UNSET
        endToEnd = ConstraintLayout.LayoutParams.UNSET
    }


    override fun hide() {
        isVisible = false
        binding?.getRootView()?.visibility = View.GONE
        stopUpdates()
        Log.d(TAG, "Widget hidden")
    }

    private fun startUpdates() {
        stopUpdates()
        handler.post(updateRunnable)
    }

    private fun stopUpdates() {
        handler.removeCallbacks(updateRunnable)
    }

    override fun cleanup() {
        stopUpdates()
        binding?.cleanup()
        binding = null
        Log.d(TAG, "Widget cleaned up")
    }
}