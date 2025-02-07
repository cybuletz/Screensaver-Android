package com.example.screensaver.widgets

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.screensaver.R

class WeatherWidgetBinding(
    private val container: ViewGroup
) {
    private var rootView: View? = null
    private var weatherIcon: ImageView? = null
    private var temperatureView: TextView? = null

    companion object {
        private const val TAG = "WeatherWidgetBinding"
    }

    fun inflate(): View {
        try {
            Log.d(TAG, "Starting inflate() for WeatherWidgetBinding")
            if (rootView == null) {
                rootView = LayoutInflater.from(container.context)
                    .inflate(R.layout.widget_weather, container, false)
                Log.d(TAG, "Layout inflated")

                weatherIcon = rootView?.findViewById(R.id.weatherIcon)
                temperatureView = rootView?.findViewById(R.id.temperatureView)
                Log.d(TAG, "Views found - Icon: ${weatherIcon != null}, Temperature: ${temperatureView != null}")

                // Add the view to container with default TOP_END position
                val params = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    setMargins(32, 32, 32, 32)
                }

                rootView?.layoutParams = params
                container.addView(rootView)
                Log.d(TAG, "Root view added to container with params")
            }
            return rootView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error in inflate()", e)
            throw e
        }
    }

    fun getWeatherIcon(): ImageView? = weatherIcon
    fun getTemperatureView(): TextView? = temperatureView
    fun getRootView(): View? = rootView

    fun cleanup() {
        rootView = null
        weatherIcon = null
        temperatureView = null
    }
}