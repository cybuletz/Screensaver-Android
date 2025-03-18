package com.example.screensaver.glide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.photos.PhotoUriManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.bitmap_recycle.ArrayPool
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.signature.ObjectKey
import com.example.screensaver.auth.GoogleAuthManager
import org.json.JSONObject

@GlideModule
class PhotosGlideModule : AppGlideModule() {
    companion object {
        private const val TAG = "PhotosGlideModule"
        private const val CONNECTION_TIMEOUT = 20L // seconds
        private const val READ_TIMEOUT = 20L // seconds
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GlideModuleEntryPoint {
        fun photoUriManager(): PhotoUriManager
        fun googleAuthManager(): GoogleAuthManager
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GlideModuleEntryPoint::class.java
        )

        val photoUriManager = entryPoint.photoUriManager()
        val googleAuthManager = entryPoint.googleAuthManager()

        // Create OkHttpClient with auth interceptor
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(GoogleAuthInterceptor(googleAuthManager))
            .build()

        // Register URI loader
        registry.prepend(
            Uri::class.java,
            InputStream::class.java,
            UriLoaderFactory(context, photoUriManager, client)
        )

        // Standard URL loader
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false

    /**
     * Custom interceptor for handling Google Photos authentication
     */
    private class GoogleAuthInterceptor(
        private val googleAuthManager: GoogleAuthManager
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            // Only handle Google URLs
            if (url.contains("googleusercontent.com")) {
                try {
                    runBlocking {
                        if (!googleAuthManager.hasValidTokens()) {
                            googleAuthManager.refreshTokens()
                        }
                    }

                    val credentials = googleAuthManager.getCurrentCredentials()
                    val newRequest = request.newBuilder()
                        .addHeader("Authorization", "Bearer ${credentials?.accessToken}")
                        .build()

                    var response = chain.proceed(newRequest)

                    // If we get a 403, try refreshing the token once
                    if (response.code == 403) {
                        response.close()
                        runBlocking {
                            googleAuthManager.refreshTokens()
                        }
                        val freshCredentials = googleAuthManager.getCurrentCredentials()
                        val retryRequest = request.newBuilder()
                            .addHeader("Authorization", "Bearer ${freshCredentials?.accessToken}")
                            .build()
                        response = chain.proceed(retryRequest)
                    }

                    return response
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling Google auth", e)
                    return chain.proceed(request)
                }
            }

            return chain.proceed(request)
        }
    }

    /**
     * Factory for creating UriLoader instances
     */
    private class UriLoaderFactory(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient
    ) : ModelLoaderFactory<Uri, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, InputStream> {
            return UriLoader(context, photoUriManager, client)
        }

        override fun teardown() {}
    }

    /**
     * Custom loader for handling different URI types
     */
    private class UriLoader(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient
    ) : ModelLoader<Uri, InputStream> {
        override fun buildLoadData(uri: Uri, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
            val signature = ObjectKey(uri.toString())
            return ModelLoader.LoadData(signature, UriDataFetcher(context, uri, photoUriManager))
        }

        override fun handles(uri: Uri): Boolean = true
    }

    /**
     * Custom DataFetcher for handling different URI types
     */
    /**
     * Custom DataFetcher for handling different URI types across Android versions
     */
    private class UriDataFetcher(
        private val context: Context,
        private val uri: Uri,
        private val photoUriManager: PhotoUriManager
    ) : DataFetcher<InputStream> {
        private var stream: InputStream? = null
        private var cancelled = false

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                Log.d(TAG, "Loading image from URI: $uri")

                // First validate URI permission
                if (!photoUriManager.hasValidPermission(uri)) {
                    // Try to recover permission
                    if (!photoUriManager.takePersistablePermission(uri)) {
                        throw SecurityException("No permission to access $uri")
                    }
                }

                // Load based on URI type
                when (photoUriManager.getUriType(uri)) {
                    PhotoUriManager.URI_TYPE_PHOTO_PICKER -> {
                        handlePhotoPickerUri(callback)
                    }
                    PhotoUriManager.URI_TYPE_CONTENT -> {
                        handleContentUri(callback)
                    }
                    else -> {
                        handleStandardUri(callback)
                    }
                }
            } catch (e: Exception) {
                if (!cancelled) {
                    Log.e(TAG, "Error loading URI: $uri", e)
                    callback.onLoadFailed(e)
                }
            }
        }

        private fun handlePhotoPickerUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    stream = inputStream
                    if (!cancelled) {
                        callback.onDataReady(stream)
                    }
                } else {
                    throw IOException("Could not open input stream for $uri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling photo picker URI", e)
                if (!cancelled) {
                    callback.onLoadFailed(e)
                }
            }
        }

        private fun handleContentUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    stream = inputStream
                    if (!cancelled) {
                        callback.onDataReady(stream)
                    }
                } else {
                    throw IOException("Could not open input stream for $uri")
                }
            } catch (e: SecurityException) {
                // Try to recover permission
                if (photoUriManager.takePersistablePermission(uri)) {
                    // Retry with new permission
                    handleContentUri(callback)
                } else {
                    if (!cancelled) {
                        callback.onLoadFailed(e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling content URI", e)
                if (!cancelled) {
                    callback.onLoadFailed(e)
                }
            }
        }

        private fun handleStandardUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    stream = inputStream
                    if (!cancelled) {
                        callback.onDataReady(stream)
                    }
                } else {
                    throw IOException("Could not open input stream for $uri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling standard URI", e)
                if (!cancelled) {
                    callback.onLoadFailed(e)
                }
            }
        }

        override fun cleanup() {
            try {
                stream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error cleaning up stream", e)
            }
        }

        override fun cancel() {
            cancelled = true
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }
}