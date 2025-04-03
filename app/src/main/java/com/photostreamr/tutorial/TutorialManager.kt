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

    companion object {
        const val ID_MANAGE_PHOTOS = 1001
        const val ID_MUSIC_SOURCES = 1002
        const val ID_COMMON_SETTINGS = 1003
        const val ID_WIDGETS_SETTINGS = 1004
        const val ID_DISPLAY_SETTINGS = 1005
        const val ID_SECURITY_PREFERENCES = 1006
    }

    fun shouldShowTutorial(type: TutorialType): Boolean {
        return !prefs.getBoolean("tutorial_shown_${type.name}", false)
    }

    fun getTutorialSteps(type: TutorialType): List<TutorialStep> {
        return when (type) {
            TutorialType.SETTINGS -> getSettingsTutorialSteps()
        }
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
                ID_MANAGE_PHOTOS,
                context.getString(R.string.tutorial_manage_photos)
            ),
            TutorialStep(
                ID_MUSIC_SOURCES,
                context.getString(R.string.tutorial_music_sources)
            ),
            TutorialStep(
                ID_COMMON_SETTINGS,
                context.getString(R.string.tutorial_common_settings)
            ),
            TutorialStep(
                ID_WIDGETS_SETTINGS,
                context.getString(R.string.tutorial_widget_settings)
            ),
            TutorialStep(
                ID_DISPLAY_SETTINGS,
                context.getString(R.string.tutorial_display_settings)
            ),
            TutorialStep(
                ID_SECURITY_PREFERENCES,
                context.getString(R.string.tutorial_security_preferences)
            )
        )
    }
}