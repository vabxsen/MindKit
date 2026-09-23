package com.localai.toolkit.data.repository

import com.localai.toolkit.data.local.prefs.SettingsDataStore
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: SettingsDataStore,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.settings

    override suspend fun setThemeMode(mode: ThemeMode) = dataStore.setThemeMode(mode)

    override suspend fun setDynamicColor(enabled: Boolean) = dataStore.setDynamicColor(enabled)

    override suspend fun setSaveHistory(enabled: Boolean) = dataStore.setSaveHistory(enabled)

    override suspend fun setOnboardingCompleted(completed: Boolean) =
        dataStore.setOnboardingCompleted(completed)

    override suspend fun setVerboseErrors(enabled: Boolean) = dataStore.setVerboseErrors(enabled)

    override suspend fun setTranslateLanguages(source: String?, target: String?) =
        dataStore.setTranslateLanguages(source, target)

    override suspend fun clear() = dataStore.clear()
}
