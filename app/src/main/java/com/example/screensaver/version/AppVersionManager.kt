package com.example.screensaver.version

import android.content.Context
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVersionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_IS_PRO_VERSION = "is_pro_version"
        private const val KEY_LAST_AD_SHOWN_TIME = "last_ad_shown_time"
        private const val DEFAULT_AD_INTERVAL = 10 * 60 * 1000L // 10 minutes in milliseconds
    }

    private val _versionState = MutableStateFlow<VersionState>(VersionState.Free)
    val versionState: StateFlow<VersionState> = _versionState.asStateFlow()

    private val preferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    init {
        loadVersionState()
    }

    private fun loadVersionState() {
        val isPro = preferences.getBoolean(KEY_IS_PRO_VERSION, false)
        _versionState.value = if (isPro) VersionState.Pro else VersionState.Free
    }

    fun isProVersion(): Boolean = _versionState.value is VersionState.Pro

    fun setProVersion(isPro: Boolean) {
        preferences.edit().putBoolean(KEY_IS_PRO_VERSION, isPro).apply()
        _versionState.value = if (isPro) VersionState.Pro else VersionState.Free
    }

    fun shouldShowAd(): Boolean {
        if (isProVersion()) return false

        val currentTime = System.currentTimeMillis()
        val lastAdShownTime = preferences.getLong(KEY_LAST_AD_SHOWN_TIME, 0L)
        val adInterval = preferences.getLong("ad_interval", DEFAULT_AD_INTERVAL)

        return currentTime - lastAdShownTime >= adInterval
    }

    fun updateLastAdShownTime() {
        preferences.edit().putLong(KEY_LAST_AD_SHOWN_TIME, System.currentTimeMillis()).apply()
    }

    fun getAdInterval(): Long {
        return preferences.getLong("ad_interval", DEFAULT_AD_INTERVAL)
    }

    fun setAdInterval(intervalMillis: Long) {
        preferences.edit().putLong("ad_interval", intervalMillis).apply()
    }

    sealed class VersionState {
        object Free : VersionState()
        object Pro : VersionState()
    }
}