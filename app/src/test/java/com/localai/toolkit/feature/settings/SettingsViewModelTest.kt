package com.localai.toolkit.feature.settings

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val settings = MemorySettings()
    private val history = MemoryHistory()
    private fun model(prefs: SettingsRepository = settings, rows: HistoryRepository = history) =
        main.own(SettingsViewModel(prefs, main.settingsActions(prefs, rows)))
    private val item = HistoryItem(type = HistoryType.OCR, title = "Result", inputPreview = "Input", output = "Output", createdAtEpochMillis = 1)

    @Test fun `appearance and privacy switches persist the selected values`() = runTest {
        val vm = model()
        vm.setThemeMode(ThemeMode.DARK)
        vm.setDynamicColor(false)
        vm.setSaveHistory(false)
        vm.setVerboseErrors(true)
        runCurrent()
        assertThat(settings.settings.value.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(settings.settings.value.dynamicColor).isFalse()
        assertThat(settings.settings.value.saveHistory).isFalse()
        assertThat(settings.settings.value.verboseErrors).isTrue()
    }

    @Test fun `clear history preserves preferences while clear all resets them`() = runTest {
        settings.setThemeMode(ThemeMode.DARK)
        history.save(item)
        val vm = model()
        vm.clearHistory()
        runCurrent()
        assertThat(history.items.value).isEmpty()
        assertThat(settings.settings.value.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(vm.events.first()).isEqualTo(SettingsEvent.HISTORY_CLEARED)
        history.save(item)
        vm.clearAllLocalData()
        runCurrent()
        assertThat(history.items.value).isEmpty()
        assertThat(settings.settings.value).isEqualTo(AppSettings())
        assertThat(vm.events.first()).isEqualTo(SettingsEvent.DATA_CLEARED)
    }

    @Test fun `failed preference write is reported and the next tap can retry`() = runTest {
        var fail = true
        val prefs = object : SettingsRepository by settings {
            override suspend fun setSaveHistory(enabled: Boolean) {
                if (fail) error("storage")
                this@SettingsViewModelTest.settings.setSaveHistory(enabled)
            }
        }
        val vm = model(prefs)
        vm.setSaveHistory(false)
        runCurrent()
        assertThat(vm.events.first()).isEqualTo(SettingsEvent.SAVE_FAILED)
        assertThat(settings.settings.value.saveHistory).isTrue()
        fail = false
        vm.setSaveHistory(false)
        runCurrent()
        assertThat(settings.settings.value.saveHistory).isFalse()
    }

    @Test fun `failed clear reports failure rather than success and unlocks controls`() = runTest {
        val rows = object : HistoryRepository by history {
            override suspend fun deleteAll() { error("storage") }
        }
        val vm = model(rows = rows)
        vm.clearHistory()
        runCurrent()
        assertThat(vm.events.first()).isEqualTo(SettingsEvent.HISTORY_CLEAR_FAILED)
        assertThat(vm.isClearing.value).isFalse()
        vm.clearAllLocalData()
        runCurrent()
        assertThat(vm.events.first()).isEqualTo(SettingsEvent.DATA_CLEAR_FAILED)
        assertThat(vm.isClearing.value).isFalse()
    }

    @Test fun `clear coalesces taps and prevents preference changes during the reset`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var deletes = 0
        val rows = object : HistoryRepository by history {
            override suspend fun deleteAll() { deletes++; pending.await(); history.deleteAll() }
        }
        val vm = model(rows = rows)
        vm.clearAllLocalData()
        vm.clearAllLocalData()
        vm.setThemeMode(ThemeMode.DARK)
        runCurrent()
        assertThat(deletes).isEqualTo(1)
        assertThat(vm.isClearing.value).isTrue()
        pending.complete(Unit)
        runCurrent()
        assertThat(settings.settings.value.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(vm.isClearing.value).isFalse()
    }
}
