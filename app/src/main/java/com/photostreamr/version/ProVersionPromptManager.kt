package com.photostreamr.version

import android.content.Context
import androidx.preference.PreferenceManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProVersionPromptManager @Inject constructor(
    private val context: Context,
    private val appVersionManager: AppVersionManager
) {
    companion object {
        private const val KEY_LAST_PRO_PROMPT_TIME = "last_pro_prompt_time"
        private const val DEFAULT_PROMPT_INTERVAL = 3 * 24 * 60 * 60 * 1000L // 3 days in milliseconds
        private const val MIN_APP_LAUNCHES = 3 // Minimum app launches before showing prompt
        private const val KEY_APP_LAUNCH_COUNT = "app_launch_count"
    }

    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun shouldShowProVersionPrompt(): Boolean {
        // Don't show if already Pro version
        if (appVersionManager.isProVersion()) return false

        // Check app launch count
        val launchCount = preferences.getInt(KEY_APP_LAUNCH_COUNT, 0)
        if (launchCount < MIN_APP_LAUNCHES) {
            // Increment launch count
            preferences.edit().putInt(KEY_APP_LAUNCH_COUNT, launchCount + 1).apply()
            return false
        }

        // Check time since last prompt
        val currentTime = System.currentTimeMillis()
        val lastPromptTime = preferences.getLong(KEY_LAST_PRO_PROMPT_TIME, 0L)
        val promptInterval = preferences.getLong("pro_prompt_interval", DEFAULT_PROMPT_INTERVAL)

        return currentTime - lastPromptTime >= promptInterval
    }

    fun updateLastPromptTime() {
        preferences.edit()
            .putLong(KEY_LAST_PRO_PROMPT_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getPromptInterval(): Long =
        preferences.getLong("pro_prompt_interval", DEFAULT_PROMPT_INTERVAL)

    fun setPromptInterval(interval: Long) {
        preferences.edit().putLong("pro_prompt_interval", interval).apply()
    }
}