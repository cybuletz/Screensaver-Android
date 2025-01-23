package com.example.screensaver.binding

import android.view.View
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.screensaver.utils.OnPhotoLoadListener

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

        Glide.with(view.context)
            .load(url)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_DURATION))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(false)
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource?,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(true)
                    return false
                }
            })
            .let { request ->
                when (quality) {
                    1 -> request.override(720, 720)
                    2 -> request.override(1080, 1080)
                    else -> request
                }.into(view)
            }
    }

    @JvmStatic
    @BindingAdapter("android:visibility")
    fun setVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}