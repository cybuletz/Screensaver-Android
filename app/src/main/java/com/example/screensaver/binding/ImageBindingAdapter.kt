package com.example.screensaver.binding

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.databinding.BindingAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
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

        val request = Glide.with(view.context)
            .load(url as String)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_DURATION))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(false)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(true)
                    return false
                }
            })

        when (quality) {
            1 -> request.override(720, 720)
            2 -> request.override(1080, 1080)
            3 -> request.override(Target.SIZE_ORIGINAL)
            else -> request.override(Target.SIZE_ORIGINAL)
        }.into(view)
    }

    @JvmStatic
    @BindingAdapter("android:visibility")
    fun setVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}