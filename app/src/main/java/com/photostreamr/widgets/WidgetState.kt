package com.photostreamr.widgets

sealed class WidgetState {
    object Loading : WidgetState()
    object Active : WidgetState()
    object Hidden : WidgetState()
    data class Error(val message: String) : WidgetState()
}

data class WidgetData(
    val type: WidgetType,
    val state: WidgetState = WidgetState.Loading,
    val config: WidgetConfig? = null
)