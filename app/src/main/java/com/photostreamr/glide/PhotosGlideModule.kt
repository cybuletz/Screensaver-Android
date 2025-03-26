package com.photostreamr.glide

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
import com.example.screensaver.photos.PhotoUriManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import com.bumptech.glide.Priority
import com.bumptech.glide.signature.ObjectKey
import com.example.screensaver.auth.GoogleAuthManager
import com.example.screensaver.photos.PersistentPhotoCache

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
        fun persistentPhotoCache(): PersistentPhotoCache
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GlideModuleEntryPoint::class.java
        )

        val photoUriManager = entryPoint.photoUriManager()
        val googleAuthManager = entryPoint.googleAuthManager()
        val persistentPhotoCache = entryPoint.persistentPhotoCache()

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
            UriLoaderFactory(context, photoUriManager, client, persistentPhotoCache)
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
        private val client: OkHttpClient,
        private val persistentPhotoCache: PersistentPhotoCache
    ) : ModelLoaderFactory<Uri, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, InputStream> {
            return UriLoader(context, photoUriManager, client, persistentPhotoCache)
        }

        override fun teardown() {}
    }

    private class UriLoader(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient,
        private val persistentPhotoCache: PersistentPhotoCache
    ) : ModelLoader<Uri, InputStream> {
        override fun buildLoadData(uri: Uri, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
            // Check if this is a Google Photos URI and if we have a cached version
            val uriString = uri.toString()
            var finalUri = uri

            if (photoUriManager.isGooglePhotosUri(uri)) {
                val cachedUri = persistentPhotoCache.getCachedPhotoUri(uriString)
                if (cachedUri != null) {
                    // Use cached version instead
                    finalUri = Uri.parse(cachedUri)
                    Log.d(TAG, "Using cached URI for Google Photos: $uriString -> $cachedUri")
                }
            }

            val signature = ObjectKey(finalUri.toString())
            return ModelLoader.LoadData(signature, UriDataFetcher(context, finalUri, persistentPhotoCache))
        }

        override fun handles(uri: Uri): Boolean = true
    }

    private class UriDataFetcher(
        private val context: Context,
        private val uri: Uri,
        private val persistentPhotoCache: PersistentPhotoCache
    ) : DataFetcher<InputStream> {

        companion object {
            private const val TAG = "UriDataFetcher"
        }

        private var currentInputStream: InputStream? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val uriString = uri.toString()

                // First check if this URI is from Google Photos and has a cached version
                if (uriString.contains("com.google.android.apps.photos")) {
                    val cachedUri = persistentPhotoCache.getCachedPhotoUri(uriString)
                    if (cachedUri != null) {
                        try {
                            Log.d(TAG, "Using cached version for Google Photos URI: $uriString -> $cachedUri")
                            val cachedUriObj = Uri.parse(cachedUri)
                            val inputStream = context.contentResolver.openInputStream(cachedUriObj)
                            if (inputStream != null) {
                                currentInputStream = inputStream
                                callback.onDataReady(inputStream)
                                return
                            } else {
                                Log.w(TAG, "Cached URI exists but couldn't open stream, falling back to original")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading from cached URI, falling back to original", e)
                            currentInputStream?.close()
                            currentInputStream = null
                        }
                    }
                }

                // Continue with original Google Photos URI handling if no cache or cache failed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    uriString.contains("com.google.android.apps.photos")
                ) {
                    loadGooglePhotosUri(callback)
                    return
                }

                // For other URIs, try normal access
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        currentInputStream = inputStream
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

        private fun loadGooglePhotosUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // For Google Photos URIs, create a new intent to get fresh access
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setDataAndType(uri, "image/*")
                }

                // Get fresh access to the content
                context.grantUriPermission(
                    context.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                // Try to open the stream with fresh permissions
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    currentInputStream = stream
                    callback.onDataReady(stream)
                } else {
                    throw IOException("Could not open input stream for URI: $uri")
                }
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
                }

                // Try to open the stream again
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    currentInputStream = inputStream
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
            // Properly close the input stream in cleanup
            try {
                currentInputStream?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing stream during cleanup", e)
            } finally {
                currentInputStream = null
            }
        }

        override fun cancel() {
            // Close the input stream if cancelled
            try {
                currentInputStream?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing stream during cancel", e)
            } finally {
                currentInputStream = null
            }
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }
}