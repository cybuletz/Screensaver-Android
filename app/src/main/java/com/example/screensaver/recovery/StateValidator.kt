package com.example.screensaver.recovery

import com.example.screensaver.data.AppDataState
import java.time.Instant

class StateValidator {
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList()
    )

    fun validate(state: AppDataState): ValidationResult {
        val errors = mutableListOf<String>()

        // Validate transitions
        if (state.transitionInterval < 5) {
            errors.add("Transition interval too short")
        }

        // Validate preview state
        if (state.isInPreviewMode && state.lastPreviewTimestamp == 0L) {
            errors.add("Invalid preview state")
        }

        // Validate photo sources
        if (state.isScreensaverReady && state.photoSources.isEmpty()) {
            errors.add("No photo sources selected")
        }

        // Validate timestamps
        if (state.lastModified < state.lastSyncTimestamp) {
            errors.add("Invalid timestamp order")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors
        )
    }
}