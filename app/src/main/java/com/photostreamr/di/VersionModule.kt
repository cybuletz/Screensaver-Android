package com.photostreamr.di

import android.content.Context
import com.photostreamr.ads.AdManager
import com.photostreamr.ads.ConsentManager
import com.photostreamr.billing.BillingRepository
import com.photostreamr.version.AppVersionManager
import com.photostreamr.version.FeatureManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton
import dagger.Lazy

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {

    @Provides
    @Singleton
    fun provideBillingRepository(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope,
        appVersionManagerLazy: Lazy<AppVersionManager>
    ): BillingRepository {
        return BillingRepository(context, coroutineScope, appVersionManagerLazy)
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
    fun provideConsentManager(
        @ApplicationContext context: Context
    ): ConsentManager {
        return ConsentManager(context)
    }

    @Provides
    @Singleton
    fun provideAdManager(
        @ApplicationContext context: Context,
        appVersionManager: AppVersionManager,
        consentManager: ConsentManager
    ): AdManager {
        return AdManager(context, appVersionManager, consentManager)
    }
}