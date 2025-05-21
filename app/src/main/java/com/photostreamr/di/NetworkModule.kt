package com.photostreamr.di

import android.content.Context
import androidx.work.WorkManager
import com.photostreamr.PhotoRepository
import com.photostreamr.photos.network.NetworkPhotoManager
import com.photostreamr.photos.network.PhotoDownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideNetworkPhotoManager(
        @ApplicationContext context: Context,
        photoRepository: PhotoRepository
    ): NetworkPhotoManager {
        return NetworkPhotoManager(context, photoRepository)
    }

    @Provides
    @Singleton
    fun providePhotoDownloadManager(
        @ApplicationContext context: Context,
        networkPhotoManager: NetworkPhotoManager,
        photoRepository: PhotoRepository,
        workManager: WorkManager
    ): PhotoDownloadManager {
        return PhotoDownloadManager(context, networkPhotoManager, photoRepository, workManager)
    }
}