package com.photostreamr.binding

import android.view.View
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import com.photostreamr.utils.OnPhotoLoadListener
import coil.load
import coil.request.CachePolicy
import coil.size.Scale

object ImageBindingAdapter {
    private const val CROSSFADE_DURATION = 300

    @JvmStatic
    @BindingAdapter(value = ["photoUrl", "quality", "onLoadingComplete"], requireAll = false)
    fun loadImage(
        view: ImageView,
        url: String?,
        quality: Int?,
        listener: OnPhotoLoadListener?
    ) {
        if (url == null) {
            listener?.onPhotoLoadComplete(false)
            return
        }

        view.load(url) {
            crossfade(CROSSFADE_DURATION)
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.ENABLED)

            // Handle size based on quality
            when (quality) {
                1 -> size(720, 720)
                2 -> size(1080, 1080)
                3 -> size(coil.size.Size.ORIGINAL)
                else -> size(coil.size.Size.ORIGINAL)
            }

            // Set up listeners
            listener(
                onSuccess = { _, _ ->
                    listener?.onPhotoLoadComplete(true)
                },
                onError = { _, _ ->
                    listener?.onPhotoLoadComplete(false)
                }
            )
        }
    }

    @JvmStatic
    @BindingAdapter("android:visibility")
    fun setVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}