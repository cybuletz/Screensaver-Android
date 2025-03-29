package com.photostreamr.widgets

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.*
import androidx.constraintlayout.widget.ConstraintLayout
import com.photostreamr.R
import java.util.Date
import java.util.Locale
import java.util.Calendar
import androidx.core.content.ContextCompat

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
            // Make sure any existing binding is properly cleaned up
            binding?.cleanup()
            binding = null

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

            // Don't add to container here - that will be done in show()

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
                    set(Calendar.MILLISECOND, 0)
                }
                val delay = calendar.timeInMillis - System.currentTimeMillis()
                dateUpdateHandler?.postDelayed(this, delay)
            }
        }

        // Run first update
        updateRunnable.run()
    }

    override fun updateConfiguration(config: WidgetConfig) {
        if (config !is WidgetConfig.ClockConfig) return

        val oldConfig = this.config
        this.config = config

        // Only update the clock format and date visibility
        binding?.getClockView()?.apply {
            format24Hour = if (config.use24Hour) "HH:mm" else "HH:mm"
            format12Hour = if (config.use24Hour) "HH:mm" else "hh:mm a"
        }

        binding?.getDateView()?.apply {
            visibility = if (config.showDate) View.VISIBLE else View.GONE
        }

        // Update position if changed
        if (oldConfig.position != config.position) {
            updatePosition(config.position)
        }

        // Update visibility if changed
        if (oldConfig.showClock != config.showClock) {
            if (config.showClock) show() else hide()
        }
    }

    override fun updatePosition(position: WidgetPosition) {
        binding?.getRootView()?.let { view ->
            // Create new params for the view
            val params = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            // Clear all constraints
            params.topToTop = ConstraintLayout.LayoutParams.UNSET
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.endToEnd = ConstraintLayout.LayoutParams.UNSET

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

            // Apply the new layout params
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun updateDateText() {
        binding?.getDateView()?.text = getCurrentDate()
    }

    private fun updateDateTime() {
        // Clock updates automatically via TextClock, only need to update date if configured
        if (config.showDate) {
            binding?.getDateView()?.apply {
                visibility = View.VISIBLE
                text = getCurrentDate()
            }
        } else {
            binding?.getDateView()?.visibility = View.GONE
        }
    }

    override fun update() {
        updateDateTime()
    }

    override fun show() {
        if (isVisible) {
            Log.d(TAG, "Clock widget already visible")
            return
        }

        isVisible = true
        binding?.let { binding ->
            Log.d(TAG, "Showing clock widget")
            val rootView = binding.getRootView()
            rootView?.apply {
                // First check if the view is already in a parent
                val parent = parent as? ViewGroup
                if (parent != null) {
                    // If it's already in the container, just make sure it's visible
                    if (parent == container) {
                        visibility = View.VISIBLE
                        background = ContextCompat.getDrawable(context, R.drawable.widget_background)
                        alpha = 1f
                        requestLayout()
                        invalidate()

                        Log.d(TAG, "Clock widget already in container, made visible")
                        return@apply
                    }

                    // If it's in another container, remove it first
                    parent.removeView(this)
                    Log.d(TAG, "Removed clock widget from previous parent")
                }

                // Add to container only if not already there
                container.addView(this)
                Log.d(TAG, "Added clock widget to container")

                // Configure appearance
                post {
                    visibility = View.VISIBLE
                    background = ContextCompat.getDrawable(context, R.drawable.widget_background)
                    alpha = 1f
                    requestLayout()
                    invalidate()
                    Log.d(TAG, "Clock widget configured and made visible")
                }
            }

            // Start time updates
            handler.removeCallbacks(updateRunnable)
            handler.post(updateRunnable)
        }
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

    private fun startUpdates() {
        stopUpdates()
        handler.post(updateRunnable)
    }

    private fun stopUpdates() {
        handler.removeCallbacks(updateRunnable)
    }

    override fun hide() {
        isVisible = false
        binding?.let { binding ->
            // Stop updates
            handler.removeCallbacks(updateRunnable)

            binding.getRootView()?.apply {
                // Remove from parent when hiding
                (parent as? ViewGroup)?.removeView(this)
                visibility = View.GONE
                background = null
                alpha = 0f
            }
        }
        Log.d(TAG, "Clock widget hidden and removed from parent")
    }

    override fun cleanup() {
        try {
            Log.d(TAG, "Starting clock widget cleanup")
            // Stop all handlers
            handler.removeCallbacksAndMessages(null)
            dateUpdateHandler?.removeCallbacksAndMessages(null)

            // Get the parent ViewGroup and remove our view
            binding?.getRootView()?.let { view ->
                try {
                    val parent = view.parent as? ViewGroup
                    if (parent != null) {
                        parent.removeView(view)
                        Log.d(TAG, "Successfully removed view from parent")
                    }
                    // Clear background and reset alpha
                    view.background = null
                    view.alpha = 0f
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing view from parent", e)
                }
            }

            // Clean up binding and reset all state
            binding?.cleanup()
            binding = null
            isVisible = false

            Log.d(TAG, "Clock widget cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during clock widget cleanup", e)
        }
    }
}