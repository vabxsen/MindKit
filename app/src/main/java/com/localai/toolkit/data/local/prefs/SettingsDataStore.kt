package com.localai.toolkit.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "local_ai_settings",
)

/**
 * Preference storage. Nothing here is transmitted anywhere; it is a private file inside
 * the app sandbox.
 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val VERBOSE_ERRORS = booleanPreferencesKey("verbose_errors")
        val TRANSLATE_SOURCE = stringPreferencesKey("translate_source")
        val TRANSLATE_TARGET = stringPreferencesKey("translate_target")
    }

    val settings: Flow<AppSettings> = context.preferencesDataStore.data
        .map { prefs ->
            val defaults = AppSettings()
            AppSettings(
                themeMode = prefs[Keys.THEME_MODE]
                    ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
                    ?: defaults.themeMode,
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
                saveHistory = prefs[Keys.SAVE_HISTORY] ?: defaults.saveHistory,
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED]
                    ?: defaults.onboardingCompleted,
                verboseErrors = prefs[Keys.VERBOSE_ERRORS] ?: defaults.verboseErrors,
                lastTranslateSource = prefs[Keys.TRANSLATE_SOURCE],
                lastTranslateTarget = prefs[Keys.TRANSLATE_TARGET],
            )
        }.recoverReadFailures()

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setSaveHistory(enabled: Boolean) = edit { it[Keys.SAVE_HISTORY] = enabled }

    suspend fun setOnboardingCompleted(completed: Boolean) =
        edit { it[Keys.ONBOARDING_COMPLETED] = completed }

    suspend fun setVerboseErrors(enabled: Boolean) = edit { it[Keys.VERBOSE_ERRORS] = enabled }

    suspend fun setTranslateLanguages(source: String?, target: String?) = edit { prefs ->
        if (source == null) prefs.remove(Keys.TRANSLATE_SOURCE) else prefs[Keys.TRANSLATE_SOURCE] = source
        if (target == null) prefs.remove(Keys.TRANSLATE_TARGET) else prefs[Keys.TRANSLATE_TARGET] = target
    }

    /** Wipes every preference. Backs "Clear all local data". */
    suspend fun clear() {
        context.preferencesDataStore.edit { it.clear() }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.preferencesDataStore.edit(block)
    }
}

/** Keep observing after a transient read error; never silently switch history on. */
internal fun Flow<AppSettings>.recoverReadFailures(): Flow<AppSettings> = flow {
    var lastKnown = AppSettings(saveHistory = false)
    emitAll(
        onEach { lastKnown = it }.retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            emit(lastKnown.copy(saveHistory = false, storageReadFailed = true))
            delay(5_000)
            true
        },
    )
}
