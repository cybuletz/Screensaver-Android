package com.photostreamr.di

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import coil.ImageLoader
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageResult
import com.photostreamr.analytics.PhotoAnalytics
import com.photostreamr.utils.AppPreferences
import com.photostreamr.utils.NotificationHelper
import com.photostreamr.PhotoSourceState
import com.photostreamr.ui.PhotoDisplayManager
import com.photostreamr.utils.PhotoLoadingManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.photostreamr.PhotoRepository
import com.photostreamr.R
import com.photostreamr.ads.AdManager
import com.photostreamr.auth.GoogleAuthManager
import com.photostreamr.data.AppDataManager
import com.photostreamr.data.SecureStorage
import com.photostreamr.recovery.StateRecoveryManager
import com.photostreamr.recovery.StateRestoration
import com.photostreamr.data.PhotoCache
import com.photostreamr.feedback.PlayStoreReviewManager
import com.photostreamr.music.SpotifyManager
import com.photostreamr.music.SpotifyPreferences
import com.photostreamr.photos.CoilImageLoadStrategy
import com.photostreamr.photos.PersistentPhotoCache
import com.photostreamr.photos.PhotoUriManager
import com.photostreamr.security.AppAuthManager
import com.photostreamr.security.BiometricHelper
import com.photostreamr.security.SecurityPreferences
import com.photostreamr.ui.BitmapMemoryManager
import com.photostreamr.ui.DiskCacheManager
import com.photostreamr.ui.EnhancedMultiPhotoLayoutManager
import com.photostreamr.ui.PhotoPreloader
import com.photostreamr.ui.PhotoResizeManager
import com.photostreamr.ui.SmartPhotoLayoutManager
import com.photostreamr.ui.SmartTemplateHelper
import com.photostreamr.version.AppVersionManager

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Singleton
    @Provides
    fun provideContext(@ApplicationContext appContext: Context): Context {
        return appContext
    }

    @Singleton
    @Provides
    fun providePhotoAnalytics(@ApplicationContext context: Context): PhotoAnalytics {
        return PhotoAnalytics(context)
    }

    @Provides
    @Singleton
    fun provideAppPreferences(
        @ApplicationContext context: Context
    ): AppPreferences {
        return AppPreferences(context)
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        bitmapMemoryManager: BitmapMemoryManager
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .allowHardware(Build.VERSION.SDK_INT > Build.VERSION_CODES.O)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .components {
                // Add an interceptor that optimizes local content URIs
                add(LocalContentUriInterceptor(context))
            }
            .crossfade(true)
            .build()
    }

    /**
     * Custom interceptor to optimize local content:// URIs
     */
    private class LocalContentUriInterceptor(val context: Context) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            val data = request.data

            // Check if this is a content URI for local photos
            if (data is String && data.startsWith("content://")) {
                val uri = Uri.parse(data)

                // Special handling for local content URIs
                val newRequest = request.newBuilder()
                    .memoryCacheKey(data) // Use the URI string as the cache key
                    .diskCachePolicy(CachePolicy.DISABLED) // Don't cache local URIs on disk
                    .allowHardware(Build.VERSION.SDK_INT > Build.VERSION_CODES.O) // Disable hardware bitmaps on Android 8
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .build()

                return chain.proceed(newRequest)
            }

            return chain.proceed(request)
        }
    }

    @Provides
    @Singleton
    fun provideGoogleAuthManager(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage
    ): GoogleAuthManager = GoogleAuthManager(context, secureStorage)

    @Provides
    @Singleton
    fun provideNotificationHelper(@ApplicationContext context: Context): NotificationHelper {
        return NotificationHelper(context)
    }

    @Provides
    @Singleton
    fun providePhotoLoadingManager(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope
    ): PhotoLoadingManager {
        return PhotoLoadingManager(context, coroutineScope)
    }

    @Provides
    @Singleton
    fun providePhotoResizeManager(
        @ApplicationContext context: Context
    ): PhotoResizeManager {
        return PhotoResizeManager(context)
    }

    @Provides
    @Singleton
    fun providePhotoDisplayManager(
        photoRepository: PhotoRepository,
        photoCache: PhotoCache,
        @ApplicationContext context: Context,
        spotifyManager: SpotifyManager,
        spotifyPreferences: SpotifyPreferences,
        adManager: AdManager,
        appVersionManager: AppVersionManager,
        photoResizeManager: PhotoResizeManager,
        photoPreloader: PhotoPreloader,
        enhancedMultiPhotoLayoutManager: EnhancedMultiPhotoLayoutManager,
        bitmapMemoryManager: BitmapMemoryManager,
        smartTemplateHelper: SmartTemplateHelper,
        smartPhotoLayoutManager: SmartPhotoLayoutManager,
        diskCacheManager: DiskCacheManager,
        imageLoadStrategy: CoilImageLoadStrategy
    ): PhotoDisplayManager {
        return PhotoDisplayManager(
            photoManager = photoRepository,
            photoCache = photoCache,
            context = context,
            spotifyManager = spotifyManager,
            spotifyPreferences = spotifyPreferences,
            adManager = adManager,
            appVersionManager = appVersionManager,
            photoResizeManager = photoResizeManager,
            photoPreloader = photoPreloader,
            enhancedMultiPhotoLayoutManager = enhancedMultiPhotoLayoutManager,
            bitmapMemoryManager = bitmapMemoryManager,
            smartTemplateHelper = smartTemplateHelper,
            smartPhotoLayoutManager = smartPhotoLayoutManager,
            diskCacheManager = diskCacheManager,
            imageLoadStrategy
        )
    }

    @Provides
    @Singleton
    fun provideBitmapMemoryManager(
        @ApplicationContext context: Context,
        diskCacheManager: DiskCacheManager
    ): BitmapMemoryManager {
        return BitmapMemoryManager(context, diskCacheManager)
    }

    @Provides
    @Singleton
    fun provideSmartPhotoLayoutManager(context: Context, imageLoadStrategy: CoilImageLoadStrategy): SmartPhotoLayoutManager {
        return SmartPhotoLayoutManager(context, imageLoadStrategy)
    }

    @Singleton
    @Provides
    fun provideImageLoadStrategy(
        @ApplicationContext context: Context,
        bitmapMemoryManager: BitmapMemoryManager
    ): CoilImageLoadStrategy {
        return CoilImageLoadStrategy(context, bitmapMemoryManager)
    }

    @Provides
    @Singleton
    fun provideSmartTemplateHelper(
        context: Context,
        smartPhotoLayoutManager: SmartPhotoLayoutManager,
        bitmapMemoryManager: BitmapMemoryManager
    ): SmartTemplateHelper {
        return SmartTemplateHelper(context, smartPhotoLayoutManager, bitmapMemoryManager)
    }

    @Provides
    @Singleton
    fun provideEnhancedMultiPhotoLayoutManager(
        context: Context,
        photoRepository: PhotoRepository,
        photoPreloader: PhotoPreloader,
        smartPhotoLayoutManager: SmartPhotoLayoutManager,
        smartTemplateHelper: SmartTemplateHelper,
        imageLoadStrategy: CoilImageLoadStrategy
    ): EnhancedMultiPhotoLayoutManager {
        return EnhancedMultiPhotoLayoutManager(
            context,
            photoRepository,
            photoPreloader,
            smartPhotoLayoutManager,
            smartTemplateHelper,
            imageLoadStrategy
        )
    }

    @Provides
    @Singleton
    fun providePhotoPreloader(
        @ApplicationContext context: Context,
        photoRepository: PhotoRepository,
        bitmapMemoryManager: BitmapMemoryManager,
        imageLoadStrategy: CoilImageLoadStrategy
    ): PhotoPreloader {
        return PhotoPreloader(context, photoRepository, bitmapMemoryManager, imageLoadStrategy)
    }

    @Provides
    @Singleton
    fun provideStateRecoveryManager(
        @ApplicationContext context: Context,
        appDataManager: AppDataManager
    ): StateRecoveryManager {
        return StateRecoveryManager(context, appDataManager)
    }

    @Provides
    @Singleton
    fun provideStateRestoration(
        @ApplicationContext context: Context,
        appDataManager: AppDataManager,
        secureStorage: SecureStorage
    ): StateRestoration {
        return StateRestoration(context, appDataManager, secureStorage)
    }

    @Provides
    @Singleton
    fun providePhotoSourceState(
        @ApplicationContext context: Context,
        appDataManager: AppDataManager
    ): PhotoSourceState {
        return PhotoSourceState(context, appDataManager)
    }

    @Provides
    @Singleton
    fun providePhotoCache(
        @ApplicationContext context: Context
    ): PhotoCache {
        return PhotoCache(context)
    }

    @Provides
    @Singleton
    fun provideSecurityPreferences(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage
    ): SecurityPreferences {
        return SecurityPreferences(context, secureStorage)
    }

    @Provides
    @Singleton
    fun provideBiometricHelper(
        @ApplicationContext context: Context
    ): BiometricHelper {
        return BiometricHelper(context)
    }

    @Provides
    @Singleton
    fun provideAppAuthManager(
        @ApplicationContext context: Context,
        securityPreferences: SecurityPreferences,
        biometricHelper: BiometricHelper,
        secureStorage: SecureStorage
    ): AppAuthManager {
        return AppAuthManager(context, securityPreferences, biometricHelper, secureStorage)
    }

    @Provides
    @Singleton
    fun providePersistentPhotoCache(
        @ApplicationContext context: Context
    ): PersistentPhotoCache {
        return PersistentPhotoCache(context)
    }

    @Provides
    @Singleton
    fun providePhotoUriManager(
        @ApplicationContext context: Context,
        persistentPhotoCache: PersistentPhotoCache
    ): PhotoUriManager {
        return PhotoUriManager(context, persistentPhotoCache)
    }

    @Provides
    @Singleton
    fun providePhotoRepository(
        @ApplicationContext context: Context,
        googleAuthManager: GoogleAuthManager,
        photoUriManager: PhotoUriManager
    ): PhotoRepository {
        return PhotoRepository(context, googleAuthManager, photoUriManager)
    }

    @Provides
    @Singleton
    fun provideDiskCacheManager(@ApplicationContext context: Context): DiskCacheManager {
        return DiskCacheManager(context)
    }

    @Provides
    @Singleton
    fun providePlayStoreReviewManager(@ApplicationContext context: Context): PlayStoreReviewManager {
        return PlayStoreReviewManager(context)
    }
}