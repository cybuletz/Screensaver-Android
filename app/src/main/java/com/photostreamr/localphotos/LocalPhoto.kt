package com.photostreamr.localphotos

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LocalPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    var isSelected: Boolean = false
) : Parcelable