package com.photostreamr.tutorial

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.photostreamr.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class TutorialType {
    SETTINGS
}

data class TutorialStep(
    val targetViewId: Int,
    val description: String
)

@Singleton
class TutorialManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun shouldShowTutorial(type: TutorialType): Boolean {
        return !prefs.getBoolean("tutorial_shown_${type.name}", false)
    }

    fun getTutorialSteps(type: TutorialType): List<TutorialStep> {
        return when (type) {
            TutorialType.SETTINGS -> getSettingsTutorialSteps()
        }
    }

    fun markTutorialAsShown(type: TutorialType) {
        prefs.edit().putBoolean("tutorial_shown_${type.name}", true).apply()
    }

    fun resetTutorials() {
        val editor = prefs.edit()
        TutorialType.values().forEach { type ->
            editor.putBoolean("tutorial_shown_${type.name}", false)
        }
        editor.apply()
    }

    fun isFirstLogin(): Boolean {
        val key = "is_first_login"
        val isFirst = prefs.getBoolean(key, true)
        if (isFirst) {
            prefs.edit().putBoolean(key, false).apply()
        }
        return isFirst
    }

    private fun getSettingsTutorialSteps(): List<TutorialStep> {
        return listOf(
            TutorialStep(
                R.id.manage_photos,
                context.getString(R.string.tutorial_manage_photos)
            ),
            TutorialStep(
                R.id.common_settings,
                context.getString(R.string.tutorial_common_settings)
            ),
            TutorialStep(
                R.id.display_settings,
                context.getString(R.string.tutorial_display_settings)
            ),
            TutorialStep(
                R.id.security_preferences,
                context.getString(R.string.tutorial_security_preferences)
            )
        )
    }
}