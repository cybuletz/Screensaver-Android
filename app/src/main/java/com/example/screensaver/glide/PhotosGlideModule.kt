package com.example.screensaver.glide

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.shared.GooglePhotosManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

@GlideModule
class PhotosGlideModule : AppGlideModule() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GlideModuleEntryPoint {
        fun secureStorage(): SecureStorage
        fun googlePhotosManager(): GooglePhotosManager
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GlideModuleEntryPoint::class.java
        )

        val secureStorage = entryPoint.secureStorage()
        val googlePhotosManager = entryPoint.googlePhotosManager()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()

                // Only handle Google Photos URLs
                if (originalRequest.url.toString().contains("googleusercontent.com")) {
                    // Ensure token is fresh before proceeding
                    runBlocking {
                        if (!googlePhotosManager.hasValidTokens()) {
                            googlePhotosManager.refreshTokens()
                        }
                    }

                    val credentials = secureStorage.getGoogleCredentials()
                    val newRequest = originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer ${credentials?.accessToken}")
                        .build()

                    var response = chain.proceed(newRequest)

                    // If we get a 403, try refreshing the token once
                    if (response.code == 403) {
                        response.close()
                        runBlocking {
                            googlePhotosManager.refreshTokens()
                        }
                        val freshCredentials = secureStorage.getGoogleCredentials()
                        val retryRequest = originalRequest.newBuilder()
                            .addHeader("Authorization", "Bearer ${freshCredentials?.accessToken}")
                            .build()
                        response = chain.proceed(retryRequest)
                    }

                    response
                } else {
                    chain.proceed(originalRequest)
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        registry.replace(GlideUrl::class.java, InputStream::class.java,
            OkHttpUrlLoader.Factory(client))
    }

    override fun isManifestParsingEnabled(): Boolean = false
}