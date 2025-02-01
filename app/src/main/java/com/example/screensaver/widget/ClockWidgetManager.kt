package com.example.screensaver.widget

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClockWidgetManager @Inject constructor(private val context: Context) {
    companion object {
        const val POSITION_TOP_LEFT = "top_left"
        const val POSITION_TOP_RIGHT = "top_right"
        const val POSITION_BOTTOM_LEFT = "bottom_left"
        const val POSITION_BOTTOM_RIGHT = "bottom_right"
        const val POSITION_CENTER = "center"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    data class WidgetState(
        val showClock: Boolean = false,
        val showDate: Boolean = false,
        val position: String = POSITION_BOTTOM_LEFT
    )

    fun getCurrentState(): WidgetState {
        return WidgetState(
            showClock = prefs.getBoolean("show_clock", false),
            showDate = prefs.getBoolean("show_date", false),
            position = prefs.getString("widget_position", POSITION_BOTTOM_LEFT) ?: POSITION_BOTTOM_LEFT
        )
    }

    fun updateWidgetVisibility(container: View, clock: TextClock?, date: TextView?) {
        val state = getCurrentState()

        clock?.visibility = if (state.showClock) View.VISIBLE else View.GONE
        date?.visibility = if (state.showDate) View.VISIBLE else View.GONE

        updateWidgetPosition(container, state.position)
    }

    private fun updateWidgetPosition(container: View, position: String) {
        val params = container.layoutParams as? FrameLayout.LayoutParams ?: return

        params.gravity = when(position) {
            POSITION_TOP_LEFT -> Gravity.TOP or Gravity.START
            POSITION_TOP_RIGHT -> Gravity.TOP or Gravity.END
            POSITION_BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            POSITION_BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            POSITION_CENTER -> Gravity.CENTER
            else -> Gravity.BOTTOM or Gravity.START
        }

        container.layoutParams = params
    }
}