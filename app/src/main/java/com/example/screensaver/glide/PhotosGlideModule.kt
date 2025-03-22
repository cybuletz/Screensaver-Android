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
            UriLoaderFactory(context, photoUriManager, googleAuthManager)
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

            if (url.contains("googleusercontent.com")) {
                try {
                    runBlocking {
                        when (googleAuthManager.authState.value) {
                            GoogleAuthManager.AuthState.ERROR -> {
                                // Try to initialize auth
                                if (!googleAuthManager.initialize()) {
                                    throw IOException("Authentication required")
                                }
                            }
                            GoogleAuthManager.AuthState.IDLE -> {
                                if (!googleAuthManager.initialize()) {
                                    throw IOException("Failed to initialize auth")
                                }
                            }
                            else -> {
                                if (!googleAuthManager.hasValidTokens() && !googleAuthManager.refreshTokens()) {
                                    throw IOException("Failed to refresh tokens")
                                }
                            }
                        }
                    }

                    val credentials = googleAuthManager.getCurrentCredentials()
                        ?: throw IOException("No credentials available")

                    val newRequest = request.newBuilder()
                        .addHeader("Authorization", "Bearer ${credentials.accessToken}")
                        .build()

                    return chain.proceed(newRequest)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling Google auth", e)
                    throw e
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
        private val googleAuthManager: GoogleAuthManager
    ) : ModelLoaderFactory<Uri, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, InputStream> {
            return UriLoader(context, photoUriManager, googleAuthManager)
        }

        override fun teardown() {}
    }

    private class UriLoader(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val googleAuthManager: GoogleAuthManager
    ) : ModelLoader<Uri, InputStream> {
        override fun buildLoadData(uri: Uri, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
            val signature = ObjectKey(uri.toString())
            return ModelLoader.LoadData(signature, UriDataFetcher(context, uri, photoUriManager, googleAuthManager))
        }

        override fun handles(uri: Uri): Boolean = true
    }

    private class UriDataFetcher(
        private val context: Context,
        private val uri: Uri,
        private val photoUriManager: PhotoUriManager,
        private val googleAuthManager: GoogleAuthManager
    ) : DataFetcher<InputStream> {

        companion object {
            private const val TAG = "UriDataFetcher"
        }

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // First try to get persistable permission if needed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    photoUriManager.isGooglePhotosUri(uri)
                ) {
                    // For Google Photos URIs on Android 11+, use a different approach
                    loadGooglePhotosUri(callback)
                    return
                }

                // For other URIs, try normal access
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        callback.onDataReady(inputStream)
                    } else {
                        callback.onLoadFailed(IOException("Could not open input stream for URI: $uri"))
                    }
                } catch (e: SecurityException) {
                    // If security exception, try alternative access
                    handleAlternativeAccess(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading URI: $uri", e)
                callback.onLoadFailed(e)
            }
        }

        private var isCallbackHandled = false

        private fun safeCallback(callback: DataFetcher.DataCallback<in InputStream>, stream: InputStream?) {
            if (!isCallbackHandled) {
                isCallbackHandled = true
                if (stream != null) {
                    callback.onDataReady(stream)
                } else {
                    callback.onLoadFailed(IOException("Could not open input stream for URI: $uri"))
                }
            }
        }

        private fun loadGooglePhotosUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // Check if this is a Google Photos URI that needs authentication
                if (photoUriManager.isGooglePhotosUri(uri)) {
                    runBlocking {
                        when (googleAuthManager.authState.value) {
                            GoogleAuthManager.AuthState.ERROR -> {
                                // Try standard content resolver access first
                                try {
                                    val inputStream = context.contentResolver.openInputStream(uri)
                                    if (inputStream != null) {
                                        callback.onDataReady(inputStream)
                                        return@runBlocking
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Standard access failed, attempting to refresh auth", e)
                                }

                                // If that fails and we're in error state, throw exception
                                throw IOException("Authentication required for Google Photos access")
                            }
                            GoogleAuthManager.AuthState.IDLE -> {
                                if (!googleAuthManager.initialize()) {
                                    throw IOException("Failed to initialize Google auth")
                                }
                            }
                            else -> {
                                if (!googleAuthManager.hasValidTokens() && !googleAuthManager.refreshTokens()) {
                                    throw IOException("Failed to refresh Google auth tokens")
                                }
                            }
                        }
                    }
                }

                // First try to take persistable permission
                photoUriManager.takePersistablePermission(uri)

                // Special handling for Android 11
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    try {
                        // For Google Photos URIs on Android 11, try to open with temporary permissions
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            setDataAndType(uri, "image/*")
                        }

                        // Grant temporary permission to ourselves
                        context.grantUriPermission(
                            context.packageName,
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )

                        // Try to open the stream with fresh permissions
                        context.contentResolver.openInputStream(uri)?.let { stream ->
                            safeCallback(callback, stream)
                            return
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Android 11 special handling failed, trying standard approach", e)
                    }
                }

                // Try standard approach
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        callback.onDataReady(inputStream)
                        return
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Standard approach failed with security exception", e)
                }

                // If we got here, both approaches failed
                throw IOException("Could not open input stream for Google Photos URI: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Google Photos URI: $uri", e)
                callback.onLoadFailed(e)
            }
        }

        private fun handleAlternativeAccess(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // Try to take persistable permission first
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not take persistable permission for $uri", e)

                    // For Android 11, record the URI even if we couldn't take permission
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                        photoUriManager.takePersistablePermission(uri)
                    }
                }

                // Try to open the stream again
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    callback.onDataReady(inputStream)
                } else {
                    callback.onLoadFailed(IOException("Could not open input stream for URI: $uri"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Alternative access failed for URI: $uri", e)
                callback.onLoadFailed(e)
            }
        }

        override fun cleanup() {
            // Nothing to clean up
        }

        override fun cancel() {
            // Cannot cancel
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }
}