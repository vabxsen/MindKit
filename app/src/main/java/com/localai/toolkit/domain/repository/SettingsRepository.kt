package com.localai.toolkit.domain.repository

import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Persisted user preferences. Backed by DataStore; never leaves the device. */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setSaveHistory(enabled: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setVerboseErrors(enabled: Boolean)
    suspend fun setTranslateLanguages(source: String?, target: String?)

    /** Restores every preference to its default. Used by "Clear all local data". */
    suspend fun clear()
}
