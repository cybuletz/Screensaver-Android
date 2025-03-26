package com.photostreamr.di

import android.content.Context
import com.photostreamr.data.AppDataManager
import com.photostreamr.data.PhotoCache
import com.photostreamr.data.SecureStorage
import com.photostreamr.utils.AppPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

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
        photoCache: PhotoCache,
        coroutineScope: CoroutineScope
    ): AppDataManager {
        return AppDataManager(
            context,
            gson,
            appPreferences,
            secureStorage,
            photoCache,
            coroutineScope
        )
    }
}