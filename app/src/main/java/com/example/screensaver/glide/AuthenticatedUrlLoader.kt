package com.example.screensaver.glide

import android.util.Log
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.Priority
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class AuthenticatedUrlLoader(
    private val model: GlideUrl
) : DataFetcher<InputStream> {

    companion object {
        private const val TAG = "AuthenticatedUrlLoader"
    }

    class Factory : ModelLoaderFactory<GlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> {
            return object : ModelLoader<GlideUrl, InputStream> {
                override fun buildLoadData(
                    model: GlideUrl,
                    width: Int,
                    height: Int,
                    options: Options
                ): ModelLoader.LoadData<InputStream>? {
                    return ModelLoader.LoadData(model, AuthenticatedUrlLoader(model))
                }

                override fun handles(model: GlideUrl): Boolean = true
            }
        }

        override fun teardown() {
            // Nothing to clean up
        }
    }

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            val connection = model.toURL().openConnection() as HttpURLConnection
            connection.doInput = true

            // Headers are now handled by the OkHttp interceptor in PhotosGlideModule

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                callback.onDataReady(connection.inputStream)
            } else {
                callback.onLoadFailed(Exception("Failed to load image: ${connection.responseCode}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        // Nothing to clean up
    }

    override fun cancel() {
        // Nothing to cancel
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java
    override fun getDataSource(): DataSource = DataSource.REMOTE
}