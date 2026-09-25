package com.localai.toolkit.feature.settings

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.mlkit.TranslationEngine
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.feature.translate.TranslateViewModel
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesLifecycleTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `tap immediately followed by navigation still accepts the preference`() = runTest {
        val prefs = MemorySettings()
        val vm = main.own(SettingsViewModel(prefs, main.settingsActions(prefs)))
        vm.setSaveHistory(false)
        // Don't pump the ViewModel dispatcher before leaving.
        main.clearViewModels()
        runCurrent()
        assertThat(prefs.settings.value.saveHistory).isFalse()
    }

    @Test fun `accepted theme change survives leaving Settings`() = runTest {
        val memory = MemorySettings()
        val write = CompletableDeferred<Unit>()
        val prefs = object : SettingsRepository by memory {
            override suspend fun setThemeMode(mode: ThemeMode) {
                write.await()
                memory.setThemeMode(mode)
            }
        }
        val vm = main.own(SettingsViewModel(prefs, main.settingsActions(prefs)))
        vm.setThemeMode(ThemeMode.DARK)
        runCurrent()
        main.clearViewModels()
        write.complete(Unit)
        runCurrent()
        assertThat(memory.settings.value.themeMode).isEqualTo(ThemeMode.DARK)
    }

    @Test fun `queued language selections survive leaving Translate`() = runTest {
        val memory = MemorySettings()
        val write = CompletableDeferred<Unit>()
        var writes = 0
        val prefs = object : SettingsRepository by memory {
            override suspend fun setTranslateLanguages(source: String?, target: String?) {
                if (++writes == 1) write.await()
                memory.setTranslateLanguages(source, target)
            }
        }
        val vm = main.own(TranslateViewModel(Translator(), MemoryHistory(), prefs, ToolHandoff(), main.settingsActions(prefs)))
        runCurrent()
        vm.onSourceChange("fr")
        runCurrent()
        vm.onTargetChange("de")
        runCurrent()
        main.clearViewModels()
        write.complete(Unit)
        runCurrent()
        assertThat(memory.settings.value.lastTranslateSource).isEqualTo("fr")
        assertThat(memory.settings.value.lastTranslateTarget).isEqualTo("de")
    }

    @Test fun `older queued language selection cannot undo Clear all local data`() = runTest {
        val memory = MemorySettings()
        val write = CompletableDeferred<Unit>()
        // DataStore serializes calls that reach storage, but a screen-local queue
        // can keep an older selection outside storage until after another screen's Clear.
        val storage = Mutex()
        var writes = 0
        val prefs = object : SettingsRepository by memory {
            override suspend fun setTranslateLanguages(source: String?, target: String?) = storage.withLock {
                if (++writes == 1) write.await()
                memory.setTranslateLanguages(source, target)
            }
            override suspend fun clear() = storage.withLock { memory.clear() }
        }
        val history = MemoryHistory()
        val actions = main.settingsActions(prefs, history)
        val translate = main.own(TranslateViewModel(Translator(), history, prefs, ToolHandoff(), actions))
        val settings = main.own(SettingsViewModel(prefs, actions))
        runCurrent()
        translate.onSourceChange("fr")
        runCurrent()
        translate.onTargetChange("de")
        runCurrent()
        settings.clearAllLocalData()
        runCurrent()
        write.complete(Unit)
        runCurrent()
        assertThat(memory.settings.value).isEqualTo(AppSettings())
        assertThat(settings.isClearing.value).isFalse()
    }

    @Test fun `confirmed Clear all finishes even after Settings is removed`() = runTest {
        val prefs = MemorySettings()
        prefs.setThemeMode(ThemeMode.DARK)
        val memory = MemoryHistory()
        memory.save(HistoryItem(type = HistoryType.ASK, title = "Saved", inputPreview = "",
            output = "Answer", createdAtEpochMillis = 1))
        val clear = CompletableDeferred<Unit>()
        val history = object : HistoryRepository by memory {
            override suspend fun deleteAll() { clear.await(); memory.deleteAll() }
        }
        val vm = main.own(SettingsViewModel(prefs, main.settingsActions(prefs, history)))
        vm.clearAllLocalData()
        runCurrent()
        main.clearViewModels()
        clear.complete(Unit)
        runCurrent()
        assertThat(memory.items.value).isEmpty()
        assertThat(prefs.settings.value).isEqualTo(AppSettings())
    }

    private class Translator : TranslationEngine {
        override fun supportedLanguages() = listOf(TranslationLanguage("en", "English"))
        override suspend fun downloadedLanguages() = setOf("en", "fr", "de")
        override suspend fun downloadLanguage(code: String, requireWifi: Boolean) = Unit
        override suspend fun deleteLanguage(code: String) = Unit
        override suspend fun translate(text: String, source: String, target: String) = text
        override suspend fun detectLanguage(text: String): String? = null
        override fun close() = Unit
    }
}
