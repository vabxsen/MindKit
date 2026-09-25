package com.localai.toolkit.di

import android.content.Context
import com.localai.toolkit.data.local.prefs.SettingsDataStore
import com.localai.toolkit.data.repository.HistoryRepositoryImpl
import com.localai.toolkit.data.repository.SettingsRepositoryImpl
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): SettingsDataStore = SettingsDataStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindImageLoader(
        impl: com.localai.toolkit.core.util.ContentImageLoader,
    ): com.localai.toolkit.core.util.ImageLoader

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository
}
