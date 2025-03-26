package com.photostreamr.di

import android.content.Context
import com.example.screensaver.ads.AdManager
import com.example.screensaver.billing.BillingRepository
import com.example.screensaver.version.AppVersionManager
import com.example.screensaver.version.FeatureManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {

    @Provides
    @Singleton
    fun provideBillingRepository(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope
    ): BillingRepository {
        return BillingRepository(context, coroutineScope)
    }

    @Provides
    @Singleton
    fun provideAppVersionManager(
        @ApplicationContext context: Context,
        billingRepository: BillingRepository,
        coroutineScope: CoroutineScope
    ): AppVersionManager {
        return AppVersionManager(context, billingRepository, coroutineScope)
    }

    @Provides
    @Singleton
    fun provideFeatureManager(
        @ApplicationContext context: Context,
        appVersionManager: AppVersionManager
    ): FeatureManager {
        return FeatureManager(context, appVersionManager)
    }

    @Provides
    @Singleton
    fun provideAdManager(
        @ApplicationContext context: Context,
        appVersionManager: AppVersionManager
    ): AdManager {
        return AdManager(context, appVersionManager)
    }
}