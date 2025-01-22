package com.example.screensaver.models

data class Album(
    val id: String,
    val title: String,
    val coverPhotoUrl: String?,
    val mediaItemsCount: Int,
    var isSelected: Boolean = false
)