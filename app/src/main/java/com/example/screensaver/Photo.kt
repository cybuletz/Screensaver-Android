package com.example.screensaver

data class Photo(
    val id: String,
    val url: String,
    val title: String? = null,
    val description: String? = null
)