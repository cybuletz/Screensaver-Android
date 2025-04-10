package com.photostreamr.photos.network

data class NetworkServer(
    val id: String,
    val name: String,
    val address: String,
    val username: String? = null,
    val password: String? = null,
    val isManual: Boolean = false
)