package com.example.screensaver.settings

interface WizardStep {
    fun isValid(): Boolean
    fun getTitle(): String
    fun getDescription(): String
}
