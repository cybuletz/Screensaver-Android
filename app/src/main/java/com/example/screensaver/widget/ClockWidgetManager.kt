package com.example.screensaver.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

@Singleton
class ClockWidgetManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private var updateJob: Job? = null
    private val updateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "ClockWidgetManager"
        const val POSITION_TOP_LEFT = "top_left"
        const val POSITION_TOP_CENTER = "top_center"
        const val POSITION_TOP_RIGHT = "top_right"
        const val POSITION_BOTTOM_LEFT = "bottom_left"
        const val POSITION_BOTTOM_CENTER = "bottom_center"
        const val POSITION_BOTTOM_RIGHT = "bottom_right"
    }

    fun updateWidgetVisibility(
        container: View,
        clock: TextView?,
        date: TextView?
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val showClock = prefs.getBoolean("show_clock", false)
        val showDate = prefs.getBoolean("show_date", false)
        val position = prefs.getString("widget_position", POSITION_BOTTOM_LEFT) ?: POSITION_BOTTOM_LEFT

        Log.d(TAG, "Updating widget visibility - clock: $showClock, date: $showDate, position: $position")

        // Stop existing updates if neither widget is visible
        if (!showClock && !showDate) {
            stopTimeUpdates()
            container.visibility = View.GONE
            return
        }

        // Configure container
        container.apply {
            visibility = View.VISIBLE
            bringToFront()
        }

        // Update clock visibility and position
        clock?.apply {
            visibility = if (showClock) View.VISIBLE else View.GONE
            if (showClock) {
                bringToFront()
                updateClockPosition(this, position)
            }
        }

        // Update date visibility and position
        date?.apply {
            visibility = if (showDate) View.VISIBLE else View.GONE
            if (showDate) {
                bringToFront()
                updateDatePosition(this, position)
            }
        }

        // Start time updates if needed
        if (showClock || showDate) {
            startTimeUpdates(clock, date)
        }
    }

    private fun updateClockPosition(clock: TextView, position: String) {
        val params = clock.layoutParams as ViewGroup.MarginLayoutParams

        // Reset any existing margins
        params.setMargins(0, 0, 0, 0)

        when (position) {
            POSITION_TOP_LEFT, POSITION_TOP_CENTER, POSITION_TOP_RIGHT -> {
                params.topMargin = 32
            }
            POSITION_BOTTOM_LEFT, POSITION_BOTTOM_CENTER, POSITION_BOTTOM_RIGHT -> {
                params.bottomMargin = 32
            }
        }

        when (position) {
            POSITION_TOP_LEFT, POSITION_BOTTOM_LEFT -> {
                params.leftMargin = 32
            }
            POSITION_TOP_RIGHT, POSITION_BOTTOM_RIGHT -> {
                params.rightMargin = 32
            }
            POSITION_TOP_CENTER, POSITION_BOTTOM_CENTER -> {
                // Center horizontally
                clock.parent?.let { parent ->
                    if (parent is ViewGroup) {
                        params.leftMargin = (parent.width - clock.width) / 2
                    }
                }
            }
        }

        clock.layoutParams = params
    }

    private fun updateDatePosition(date: TextView, position: String) {
        val params = date.layoutParams as ViewGroup.MarginLayoutParams

        // Reset any existing margins
        params.setMargins(0, 0, 0, 0)

        when (position) {
            POSITION_TOP_LEFT, POSITION_TOP_CENTER, POSITION_TOP_RIGHT -> {
                params.topMargin = 80  // Below clock
            }
            POSITION_BOTTOM_LEFT, POSITION_BOTTOM_CENTER, POSITION_BOTTOM_RIGHT -> {
                params.bottomMargin = 80  // Above clock
            }
        }

        when (position) {
            POSITION_TOP_LEFT, POSITION_BOTTOM_LEFT -> {
                params.leftMargin = 32
            }
            POSITION_TOP_RIGHT, POSITION_BOTTOM_RIGHT -> {
                params.rightMargin = 32
            }
            POSITION_TOP_CENTER, POSITION_BOTTOM_CENTER -> {
                // Center horizontally
                date.parent?.let { parent ->
                    if (parent is ViewGroup) {
                        params.leftMargin = (parent.width - date.width) / 2
                    }
                }
            }
        }

        date.layoutParams = params
    }

    private fun startTimeUpdates(clock: TextView?, date: TextView?) {
        stopTimeUpdates()

        updateJob = updateScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                clock?.text = timeFormat.format(now)
                date?.text = dateFormat.format(now)
                delay(1000)
            }
        }
    }

    private fun stopTimeUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    fun cleanup() {
        stopTimeUpdates()
        updateScope.cancel()
    }
}