package com.example.screensaver.di

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.example.screensaver.analytics.PhotoAnalytics
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.utils.NotificationHelper
import com.example.screensaver.PhotoSourceState
import com.example.screensaver.ui.PhotoDisplayManager
import com.example.screensaver.utils.PhotoLoadingManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.example.screensaver.PhotoRepository
import com.example.screensaver.ads.AdManager
import com.example.screensaver.auth.GoogleAuthManager
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.recovery.StateRecoveryManager
import com.example.screensaver.recovery.StateRestoration
import com.example.screensaver.data.PhotoCache
import com.example.screensaver.music.SpotifyManager
import com.example.screensaver.music.SpotifyPreferences
import com.example.screensaver.photos.PersistentPhotoCache
import com.example.screensaver.photos.PhotoUriManager
import com.example.screensaver.security.AppAuthManager
import com.example.screensaver.security.BiometricHelper
import com.example.screensaver.security.SecurityPreferences
import com.example.screensaver.version.AppVersionManager
import com.example.screensaver.version.FeatureManager

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
    fun provideGlide(@ApplicationContext context: Context): RequestManager {
        return Glide.with(context)
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
    fun providePhotoDisplayManager(
        photoRepository: PhotoRepository,
        photoCache: PhotoCache,
        @ApplicationContext context: Context,
        spotifyManager: SpotifyManager,
        spotifyPreferences: SpotifyPreferences
    ): PhotoDisplayManager {
        return PhotoDisplayManager(
            photoManager = photoRepository,
            photoCache = photoCache,
            context = context,
            spotifyManager = spotifyManager,
            spotifyPreferences = spotifyPreferences
        )
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
}