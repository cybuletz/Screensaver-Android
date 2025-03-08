package com.example.screensaver.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SourceSelectionState @Inject constructor() : ViewModel() {
    private val _selectedSources = MutableStateFlow<Set<String>>(emptySet())
    val selectedSources: StateFlow<Set<String>> = _selectedSources

    fun updateSources(sources: Set<String>) {
        _selectedSources.value = sources
    }

    fun isValid(): Boolean = _selectedSources.value.isNotEmpty()
}