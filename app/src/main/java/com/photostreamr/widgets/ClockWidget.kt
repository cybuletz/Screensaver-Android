package com.photostreamr.widgets

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.*
import androidx.constraintlayout.widget.ConstraintLayout
import com.photostreamr.widgets.WidgetConfig
import java.util.Date
import java.util.Locale
import java.util.Calendar
import androidx.core.content.ContextCompat
import com.photostreamr.R
import com.photostreamr.widgets.ClockWidgetBinding
import com.photostreamr.widgets.ScreenWidget
import com.photostreamr.widgets.WidgetPosition

class ClockWidget(
    private val container: ViewGroup,
    var config: WidgetConfig.ClockConfig
) : ScreenWidget {
    private var binding: ClockWidgetBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormatter = SimpleDateFormat("", Locale.getDefault())
    private var isVisible = false
    private var dateUpdateHandler: Handler? = null

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
        Log.d(TAG, "Setting up views")
        binding?.let { binding ->
            // First set up the layout
            val rootView = binding.getRootView() ?: return
            val layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rootView.layoutParams = layoutParams
            container.addView(rootView)
            updatePosition(config.position)
            rootView.visibility = if (isVisible) View.VISIBLE else View.GONE

            // Then set up the clock and date views
            binding.getClockView()?.apply {
                format24Hour = "HH:mm"
                format12Hour = "hh:mm a"
                Log.d(TAG, "Clock formats initialized")
            }

            // Setup date view with auto-updating
            binding.getDateView()?.apply {
                text = getCurrentDate()
                // Add a handler to update the date at midnight
                startDateUpdates()
                Log.d(TAG, "Date view initialized with: ${text}")
            }
        }
    }

    private fun getCurrentDate(): String {
        return try {
            SimpleDateFormat(config.dateFormat, Locale.getDefault()).format(Date())
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting date", e)
            SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date())
        }
    }

    private fun startDateUpdates() {
        dateUpdateHandler?.removeCallbacksAndMessages(null)
        dateUpdateHandler = Handler(Looper.getMainLooper())

        val updateRunnable = object : Runnable {
            override fun run() {
                updateDateText()

                // Schedule next update for midnight
                val calendar = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                }

                dateUpdateHandler?.postAtTime(this, calendar.timeInMillis)
            }
        }

        // Start the updates
        updateRunnable.run()
    }

    override fun updatePosition(position: WidgetPosition) {
        binding?.getRootView()?.let { view ->
            val params = view.layoutParams as ConstraintLayout.LayoutParams

            // Clear existing constraints
            params.apply {
                topToTop = ConstraintLayout.LayoutParams.UNSET
                bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                startToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.UNSET
            }

            // Get standard margin
            val margin = view.resources.getDimensionPixelSize(R.dimen.widget_margin)

            // Apply new constraints based on position
            when (position) {
                WidgetPosition.TOP_START -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, margin, 0, 0)
                    }
                }
                WidgetPosition.TOP_CENTER -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, margin, margin, 0)
                    }
                }
                WidgetPosition.TOP_END -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(0, margin, margin, 0)
                    }
                }
                WidgetPosition.CENTER_START -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, 0, 0, 0)
                    }
                }
                WidgetPosition.CENTER -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, 0, margin, 0)
                    }
                }
                WidgetPosition.CENTER_END -> {
                    params.apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(0, 0, margin, 0)
                    }
                }
                WidgetPosition.BOTTOM_START -> {
                    params.apply {
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, 0, 0, margin)
                    }
                }
                WidgetPosition.BOTTOM_CENTER -> {
                    params.apply {
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(margin, 0, margin, margin)
                    }
                }
                WidgetPosition.BOTTOM_END -> {
                    params.apply {
                        bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        setMargins(0, 0, margin, margin)
                    }
                }
            }

            // Ensure widget stays within bounds
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT

            view.layoutParams = params
            view.requestLayout()
        }
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
                format24Hour = "HH:mm"
                format12Hour = "hh:mm a"
                // Use TextClock's built-in 24-hour format setting
                setFormat12Hour(if (config.use24Hour) null else "hh:mm a")
                setFormat24Hour(if (config.use24Hour) "HH:mm" else null)
                visibility = if (config.showClock) View.VISIBLE else View.GONE
                Log.d(TAG, "Clock format updated - 24hour: ${config.use24Hour}")
            }

            // Update date format
            dateFormatter.applyPattern(config.dateFormat)

            binding?.getDateView()?.apply {
                visibility = if (config.showDate) View.VISIBLE else View.GONE
                if (config.showDate) {
                    text = getCurrentDate()
                    Log.d(TAG, "Date updated to: $text")
                }
            }

            // Update position if widget is visible
            if (isVisible) {
                updatePosition(config.position)
            }

            // Start or stop updates based on visibility
            if (isVisible) {
                startUpdates()
                startDateUpdates()
            } else {
                stopUpdates()
                dateUpdateHandler?.removeCallbacksAndMessages(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating configuration", e)
        }
    }

    private fun updateDateText() {
        binding?.getDateView()?.apply {
            try {
                val dateStr = SimpleDateFormat(config.dateFormat, Locale.getDefault()).format(Date())
                text = dateStr
                visibility = if (config.showDate) View.VISIBLE else View.GONE
                Log.d(TAG, "Date updated to: $dateStr (TextView text is now: $text, visibility: $visibility)")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating date text", e)
            }
        } ?: Log.e(TAG, "Date view is null")
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
                // Ensure the view is added to container if it was removed
                if (parent == null) {
                    container.addView(this)
                }

                post {
                    visibility = View.VISIBLE
                    background = ContextCompat.getDrawable(context, R.drawable.widget_background)
                    alpha = 1f
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
                            WidgetPosition.CENTER_START -> {
                                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                            }
                            WidgetPosition.CENTER -> {
                                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                            }
                            WidgetPosition.CENTER_END -> {
                                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
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

            // Show clock if enabled
            binding.getClockView()?.apply {
                visibility = if (config.showClock) View.VISIBLE else View.GONE
                Log.d(TAG, "Clock visibility set to: ${if (config.showClock) "VISIBLE" else "GONE"}")
            }

            // Update date view with current date and proper visibility
            binding.getDateView()?.apply {
                text = getCurrentDate() // Make sure the date is set before showing
                visibility = if (config.showDate) View.VISIBLE else View.GONE
                Log.d(TAG, "Date visibility set to: ${if (config.showDate) "VISIBLE" else "GONE"}, text: $text")
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
        binding?.let { binding ->
            binding.getRootView()?.apply {
                // Remove the view from parent when hiding
                (parent as? ViewGroup)?.removeView(this)
                visibility = View.GONE
                background = null // Clear the background
                alpha = 0f
            }

            // Hide all child views
            binding.getClockView()?.visibility = View.GONE
            binding.getDateView()?.visibility = View.GONE
        }
        stopUpdates()
        dateUpdateHandler?.removeCallbacksAndMessages(null)
        Log.d(TAG, "Clock widget hidden and removed from parent")
    }


    private fun startUpdates() {
        stopUpdates()
        handler.post(updateRunnable)
    }

    private fun stopUpdates() {
        handler.removeCallbacks(updateRunnable)
    }

    override fun cleanup() {
        Log.d(TAG, "Starting cleanup")
        // Stop all updates
        stopUpdates()
        dateUpdateHandler?.removeCallbacksAndMessages(null)
        dateUpdateHandler = null
        handler.removeCallbacksAndMessages(null)

        // Clean up binding
        binding?.apply {
            getRootView()?.visibility = View.GONE
            cleanup()
        }
        binding = null

        isVisible = false
        Log.d(TAG, "Widget cleaned up")
    }
}