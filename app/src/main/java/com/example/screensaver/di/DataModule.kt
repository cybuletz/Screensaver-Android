package com.example.screensaver.di

import android.content.Context
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.PhotoCache
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.utils.AppPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import com.example.screensaver.shared.GooglePhotosManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .create()

    @Provides
    @Singleton
    fun provideSecureStorage(
        @ApplicationContext context: Context
    ): SecureStorage = SecureStorage(context)

    @Provides
    @Singleton
    fun provideAppDataManager(
        @ApplicationContext context: Context,
        gson: Gson,
        appPreferences: AppPreferences,
        secureStorage: SecureStorage,
        googlePhotosManager: GooglePhotosManager,
        photoCache: PhotoCache,
        coroutineScope: CoroutineScope
    ): AppDataManager {
        return AppDataManager(
            context,
            gson,
            appPreferences,
            secureStorage,
            googlePhotosManager,
            photoCache,
            coroutineScope
        )
    }
}