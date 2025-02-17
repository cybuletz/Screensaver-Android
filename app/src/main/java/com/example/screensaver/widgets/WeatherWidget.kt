package com.example.screensaver.widgets

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.screensaver.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import android.graphics.Color
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.OnTokenCanceledListener
import org.json.JSONArray
import android.view.animation.*
import android.widget.ImageView
import java.util.Calendar
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager



class WeatherWidget(
    private val container: ViewGroup,
    var config: WidgetConfig.WeatherConfig
) : ScreenWidget {
    private var binding: WeatherWidgetBinding? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isVisible = false
    private var weatherUpdateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownLocation: Location? = null

    private var lastKnownWeatherState: WeatherState? = null
    private var lastKnownIconCode: Int = -1

    var currentWeatherState: WeatherState? = null
        private set

    var currentWeatherCode: Int = -1
        private set
    private var animationInProgress = false

    private val fadeIn by lazy { AnimationUtils.loadAnimation(container.context, R.anim.fade_in) }
    private val fadeOut by lazy { AnimationUtils.loadAnimation(container.context, R.anim.fade_out) }

    companion object {
        private const val TAG = "WeatherWidget"
        private const val MIN_UPDATE_INTERVAL = 900000L // 15 minutes minimum
        private const val WEATHER_API_URL = "https://api.open-meteo.com/v1/forecast"
    }

    data class WeatherState(
        val temperature: String,
        val weatherCode: Int,
        val description: String,
        val iconResource: Int? = null
    )

    init {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(container.context)
    }

    private fun setupViews() {
        binding?.let { binding ->
            binding.getTemperatureView()?.apply {
                setTextColor(Color.WHITE)
                textSize = 24f
            }
            binding.getWeatherIcon()?.apply {
                setImageResource(R.drawable.ic_weather_default)
            }
        }
    }

    override fun init() {
        try {
            Log.d(TAG, "Initializing WeatherWidget with config: $config")
            binding = WeatherWidgetBinding(container).apply {
                inflate()
                setupViews()
            }
            updateConfiguration(config)

            // Remove any existing instances of this view from the container
            binding?.getRootView()?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }

            // Show if enabled
            if (config.enabled) {
                show()
            } else {
                hide()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WeatherWidget", e)
        }
    }

    override fun update() {
        if (!isVisible) return
        updateWeather()
    }

    override fun show() {
        isVisible = true
        binding?.let { binding ->
            Log.d(TAG, "Showing weather widget")
            val rootView = binding.getRootView()
            rootView?.apply {
                // Remove from current parent if exists
                (parent as? ViewGroup)?.removeView(this)

                // Only add to container if not already added
                if (parent == null) {
                    container.addView(this)
                }

                post {
                    visibility = View.VISIBLE
                    background = ContextCompat.getDrawable(context, R.drawable.widget_background)
                    alpha = 1f
                    bringToFront()

                    // Update position with clean constraints
                    updatePosition(config.position)

                    // Request layout update
                    requestLayout()
                    invalidate()
                }
            }

            // Restore last known state if available
            currentWeatherState?.let { state ->
                binding.apply {
                    getTemperatureView()?.text = "${state.temperature}° ${if (config.useCelsius) "C" else "F"}"
                    if (currentWeatherCode != -1) {
                        updateWeatherIcon(currentWeatherCode)
                    }
                }
            }

            // Start updates if enabled
            if (config.enabled) {
                startWeatherUpdates()
            }
        }
    }

    fun restoreState(temperature: String, weatherCode: Int) {
        val iconResource = getWeatherIconResource(weatherCode)
        val state = WeatherState(
            temperature = temperature,
            weatherCode = weatherCode,
            description = getWeatherDescription(weatherCode),
            iconResource = iconResource
        )
        updateState(state)
        this.currentWeatherCode = weatherCode
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
            binding.getWeatherIcon()?.visibility = View.GONE
            binding.getTemperatureView()?.visibility = View.GONE
        }

        // Stop weather updates
        stopWeatherUpdates()
        stopAllAnimations()
        Log.d(TAG, "Weather widget hidden and removed from parent")
    }

    override fun cleanup() {
        try {
            Log.d(TAG, "Starting weather widget cleanup")
            // Stop all async operations
            stopWeatherUpdates()
            stopAllAnimations()
            weatherUpdateJob?.cancel()

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
            binding?.apply {
                cleanup()
                getWeatherIcon()?.clearAnimation()
                getTemperatureView()?.visibility = View.GONE
            }
            binding = null
            isVisible = false
            currentWeatherCode = -1
            animationInProgress = false

            Log.d(TAG, "Weather widget cleanup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during weather widget cleanup", e)
        }
    }

    override fun updateConfiguration(config: WidgetConfig) {
        if (config !is WidgetConfig.WeatherConfig) return

        // Store old position to check if it changed
        val oldPosition = this.config.position

        // Update config
        this.config = config

        // Only update position if it changed
        if (oldPosition != config.position) {
            updatePosition(config.position)
        }

        if (config.enabled) show() else hide()
    }

    private fun startWeatherUpdates() {
        stopWeatherUpdates()
        weatherUpdateJob = scope.launch {
            while (isVisible) {
                updateWeather()
                kotlinx.coroutines.delay(config.updateInterval.coerceAtLeast(MIN_UPDATE_INTERVAL))
            }
        }
    }

    private fun stopWeatherUpdates() {
        weatherUpdateJob?.cancel()
        weatherUpdateJob = null
    }

    private fun updateWeather() {
        scope.launch {
            try {
                val weatherData = fetchWeatherData()
                updateUI(weatherData)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating weather", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun updateLocation(): Location? = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        try {
            if (!config.useDeviceLocation) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            // Check for permissions
            val context = container.context
            val hasCoarseLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasFineLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (!hasCoarseLocation && !hasFineLocation) {
                Log.w(TAG, "Location permissions not granted, using default location")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val cancellationTokenSource = CancellationTokenSource()
            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location ->
                lastKnownLocation = location
                continuation.resume(location)
            }.addOnFailureListener { exception ->
                Log.e(TAG, "Error getting location", exception)
                continuation.resume(lastKnownLocation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateLocation", e)
            continuation.resume(lastKnownLocation)
        }
    }

    private suspend fun fetchWeatherData(): WeatherData = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val coordinates = try {
            when {
                config.useDeviceLocation -> {
                    updateLocation()?.let { location ->
                        Pair(location.latitude, location.longitude)
                    }
                }
                config.manualLocation.isNotEmpty() -> {
                    getCoordinatesFromLocation(config.manualLocation)
                }
                else -> null
            } ?: Pair(51.5085, -0.1257) // Default to London if null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting coordinates", e)
            Pair(51.5085, -0.1257) // Default to London on error
        }

        val url = "$WEATHER_API_URL?latitude=${coordinates.first}&longitude=${coordinates.second}&current_weather=true"

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            val response = connection.inputStream.bufferedReader().readText()
            parseWeatherData(response)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun getCoordinatesFromLocation(location: String): Pair<Double, Double>? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                // Using Nominatim API for geocoding (OpenStreetMap)
                val encodedLocation = java.net.URLEncoder.encode(location, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?q=$encodedLocation&format=json&limit=1"

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "ScreensaverApp")

                try {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonArray = JSONArray(response)
                    if (jsonArray.length() > 0) {
                        val result = jsonArray.getJSONObject(0)
                        val lat = result.getDouble("lat")
                        val lon = result.getDouble("lon")
                        Pair(lat, lon)
                    } else {
                        null
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error geocoding location: $location", e)
                null
            }
        }


    private fun parseWeatherData(jsonString: String): WeatherData {
        val json = JSONObject(jsonString)
        val current = json.getJSONObject("current_weather")
        return WeatherData(
            temperature = current.getDouble("temperature").toFloat(),
            weatherCode = current.getInt("weathercode")
        )
    }

    private fun updateUI(weatherData: WeatherData) {
        val iconResource = getWeatherIconResource(weatherData.weatherCode)

        val state = WeatherState(
            temperature = weatherData.temperature.toString(),
            weatherCode = weatherData.weatherCode,
            description = getWeatherDescription(weatherData.weatherCode),
            iconResource = iconResource
        )

        currentWeatherState = state

        binding?.apply {
            val tempText = String.format("%.1f° %s",
                weatherData.temperature,
                if (config.useCelsius) "C" else "F")

            getTemperatureView()?.text = tempText
            getWeatherIcon()?.setImageResource(iconResource)
        }
    }

    fun getWeatherIconResource(weatherCode: Int): Int {
        return when (weatherCode) {
            0 -> if (isDaytime()) R.drawable.ic_weather_clear else R.drawable.ic_weather_clear_night
            1, 2 -> if (isDaytime()) R.drawable.ic_weather_cloudy else R.drawable.ic_weather_cloudy_night
            3 -> R.drawable.ic_weather_overcast
            45, 48 -> R.drawable.ic_weather_foggy
            51, 53, 55 -> R.drawable.ic_weather_drizzle
            61, 63, 65 -> R.drawable.ic_weather_rain
            71, 73, 75 -> R.drawable.ic_weather_snow
            95 -> R.drawable.ic_weather_thunderstorm
            else -> R.drawable.ic_weather_default
        }
    }

    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> if (isDaytime()) "Clear sky" else "Clear night"
            1, 2 -> if (isDaytime()) "Partly cloudy" else "Partly cloudy night"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow"
            95 -> "Thunderstorm"
            else -> "Unknown"
        }
    }

    fun updateState(state: WeatherState) {
        currentWeatherState = state
        binding?.apply {
            getTemperatureView()?.text = "${state.temperature}° ${if (config.useCelsius) "C" else "F"}"
            state.iconResource?.let { resource ->
                getWeatherIcon()?.setImageResource(resource)
            } ?: updateWeatherIcon(state.weatherCode)
        }
    }

    private fun isDaytime(): Boolean {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return hour in 6..18 // Consider 6 AM to 6 PM as daytime
    }

    override fun updatePosition(position: WidgetPosition) {
        // Save current state
        lastKnownWeatherState = currentWeatherState
        lastKnownIconCode = currentWeatherCode

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

            // Restore the state after position update
            lastKnownWeatherState?.let { state ->
                binding?.apply {
                    getTemperatureView()?.text = "${state.temperature}° ${if (config.useCelsius) "C" else "F"}"
                    state.iconResource?.let { resource ->
                        getWeatherIcon()?.setImageResource(resource)
                    } ?: lastKnownIconCode.takeIf { it != -1 }?.let { code ->
                        updateWeatherIcon(code)
                    }
                }
            }
        }
    }

    private fun updateWeatherIcon(weatherCode: Int) {
        if (currentWeatherCode == weatherCode || animationInProgress) return

        animationInProgress = true
        currentWeatherCode = weatherCode

        val newIconResource = when (weatherCode) {
            0 -> if (isDaytime()) R.drawable.ic_weather_clear else R.drawable.ic_weather_clear_night
            1, 2 -> if (isDaytime()) R.drawable.ic_weather_cloudy else R.drawable.ic_weather_cloudy_night
            3 -> R.drawable.ic_weather_overcast
            45, 48 -> R.drawable.ic_weather_foggy
            51, 53, 55 -> R.drawable.ic_weather_drizzle
            61, 63, 65 -> R.drawable.ic_weather_rain
            71, 73, 75 -> R.drawable.ic_weather_snow
            95 -> R.drawable.ic_weather_thunderstorm
            else -> R.drawable.ic_weather_default
        }

        binding?.getWeatherIcon()?.let { iconView ->
            fadeOut.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    iconView.setImageResource(newIconResource)

                    // Apply continuous animation for certain weather conditions
                    when (weatherCode) {
                        51, 53, 55, // Drizzle
                        61, 63, 65, // Rain
                        71, 73, 75 -> { // Snow
                            startPrecipitationAnimation(iconView)
                        }
                        95 -> { // Thunderstorm
                            startThunderstormAnimation(iconView)
                        }
                        45, 48 -> { // Foggy
                            startFogAnimation(iconView)
                        }
                        else -> {
                            iconView.startAnimation(fadeIn)
                        }
                    }
                    animationInProgress = false
                }
            })
            iconView.startAnimation(fadeOut)
        }
    }

    private fun startPrecipitationAnimation(view: ImageView) {
        val precipitationSet = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, -0.1f,
                Animation.RELATIVE_TO_SELF, 0.1f
            ).apply {
                duration = 1500
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
                interpolator = LinearInterpolator()
            })
        }
        view.startAnimation(precipitationSet)
    }

    private fun startThunderstormAnimation(view: ImageView) {
        val thunderSet = AnimationSet(true).apply {
            addAnimation(fadeIn)
            // Flash effect
            addAnimation(AlphaAnimation(1f, 0.6f).apply {
                duration = 100
                startOffset = 1000
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
        }
        view.startAnimation(thunderSet)
    }

    private fun startFogAnimation(view: ImageView) {
        val fogSet = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(TranslateAnimation(
                Animation.RELATIVE_TO_SELF, -0.1f,
                Animation.RELATIVE_TO_SELF, 0.1f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f
            ).apply {
                duration = 3000
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
                interpolator = LinearInterpolator()
            })
        }
        view.startAnimation(fogSet)
    }

    private fun stopAllAnimations() {
        binding?.getWeatherIcon()?.clearAnimation()
        animationInProgress = false
    }

    data class WeatherData(
        val temperature: Float,
        val weatherCode: Int
    )
}