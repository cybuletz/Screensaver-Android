package com.photostreamr.photos.network

data class NetworkResource(
    val server: NetworkServer,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isImage: Boolean,
    val size: Long,
    val lastModified: Long
)