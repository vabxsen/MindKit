package com.localai.toolkit.di

import android.content.Context
import androidx.room.Room
import com.localai.toolkit.data.local.db.HistoryDao
import com.localai.toolkit.data.local.db.LocalAiDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LocalAiDatabase =
        Room.databaseBuilder(context, LocalAiDatabase::class.java, LocalAiDatabase.NAME)
            .build()

    @Provides
    fun provideHistoryDao(database: LocalAiDatabase): HistoryDao = database.historyDao()
}
