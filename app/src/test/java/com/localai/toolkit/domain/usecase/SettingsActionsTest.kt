package com.localai.toolkit.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.testing.MemoryHistory
import com.localai.toolkit.testing.MemorySettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsActionsTest {
    // Match the production application's SupervisorJob. TestScope's special
    // background reporting job also records expected async failures as test errors.
    private fun TestScope.applicationScope() = CoroutineScope(
        backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
    )
    @Test fun `all preference actions enter one ordered queue before dispatch`() = runTest {
        val memory = MemorySettings()
        val firstWrite = CompletableDeferred<Unit>()
        val prefs = object : SettingsRepository by memory {
            override suspend fun setThemeMode(mode: ThemeMode) {
                firstWrite.await()
                memory.setThemeMode(mode)
            }
        }
        val actions = SettingsActions(prefs, MemoryHistory(), applicationScope())
        val writes = listOf(
            actions.setThemeMode(ThemeMode.DARK),
            actions.setDynamicColor(false),
            actions.setSaveHistory(false),
            actions.setVerboseErrors(true),
            actions.setOnboardingCompleted(true),
            actions.setTranslateLanguages("fr", "de"),
        )
        runCurrent()
        assertThat(memory.settings.value).isEqualTo(AppSettings())
        firstWrite.complete(Unit)
        runCurrent()
        writes.forEach { it.await() }
        assertThat(memory.settings.value).isEqualTo(AppSettings(
            themeMode = ThemeMode.DARK, dynamicColor = false, saveHistory = false,
            verboseErrors = true, onboardingCompleted = true,
            lastTranslateSource = "fr", lastTranslateTarget = "de",
        ))
    }

    @Test fun `cancelling a screen waiting for a queued write does not cancel the write`() = runTest {
        val memory = MemorySettings()
        val firstWrite = CompletableDeferred<Unit>()
        val prefs = object : SettingsRepository by memory {
            override suspend fun setThemeMode(mode: ThemeMode) {
                firstWrite.await()
                memory.setThemeMode(mode)
            }
        }
        val actions = SettingsActions(prefs, MemoryHistory(), applicationScope())
        actions.setThemeMode(ThemeMode.DARK)
        val write = actions.setSaveHistory(false)
        val screen = launch { write.await() }
        runCurrent()
        screen.cancel()
        firstWrite.complete(Unit)
        runCurrent()
        assertThat(write.isCancelled).isFalse()
        assertThat(memory.settings.value.saveHistory).isFalse()
    }

    @Test fun `later language selection waits for both reset stages then remains stored`() = runTest {
        val memory = MemorySettings()
        memory.setThemeMode(ThemeMode.DARK)
        val historyClear = CompletableDeferred<Unit>()
        val preferenceClear = CompletableDeferred<Unit>()
        val prefs = object : SettingsRepository by memory {
            override suspend fun clear() { preferenceClear.await(); memory.clear() }
        }
        val history = object : HistoryRepository by MemoryHistory() {
            override suspend fun deleteAll() { historyClear.await() }
        }
        val actions = SettingsActions(prefs, history, applicationScope())
        val reset = actions.clearAllLocalData()
        val selection = actions.setTranslateLanguages("fr", "de")
        runCurrent()
        assertThat(memory.settings.value.lastTranslateSource).isNull()
        historyClear.complete(Unit)
        runCurrent()
        assertThat(memory.settings.value.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(selection.isCompleted).isFalse()
        preferenceClear.complete(Unit)
        runCurrent()
        reset.await()
        selection.await()
        assertThat(memory.settings.value).isEqualTo(AppSettings(
            lastTranslateSource = "fr", lastTranslateTarget = "de",
        ))
    }

    @Test fun `failed write is reported without cancelling the next queued action`() = runTest {
        val memory = MemorySettings()
        val firstWrite = CompletableDeferred<Unit>()
        val prefs = object : SettingsRepository by memory {
            override suspend fun setThemeMode(mode: ThemeMode) {
                firstWrite.await()
                throw IOException("disk failure")
            }
        }
        val actions = SettingsActions(prefs, MemoryHistory(), applicationScope())
        val failed = actions.setThemeMode(ThemeMode.DARK)
        val next = actions.setSaveHistory(false)
        firstWrite.complete(Unit)
        runCurrent()
        assertThat(runCatching { failed.await() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        next.await()
        assertThat(memory.settings.value.saveHistory).isFalse()
        assertThat(memory.settings.value.themeMode).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test fun `failed reset does not claim success or block a retry`() = runTest {
        val memory = MemorySettings()
        memory.setThemeMode(ThemeMode.DARK)
        var fail = true
        var clears = 0
        val prefs = object : SettingsRepository by memory {
            override suspend fun clear() {
                if (fail) throw IOException("preferences unavailable")
                memory.clear()
            }
        }
        val history = object : HistoryRepository by MemoryHistory() {
            override suspend fun deleteAll() { clears++ }
        }
        val actions = SettingsActions(prefs, history, applicationScope())
        val failed = actions.clearAllLocalData()
        assertThat(runCatching { failed.await() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(memory.settings.value.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(clears).isEqualTo(1)
        fail = false
        actions.clearAllLocalData().await()
        assertThat(clears).isEqualTo(2)
        assertThat(memory.settings.value).isEqualTo(AppSettings())
    }

    @Test fun `application shutdown cancels pending work and rejects new writes`() = runTest {
        val memory = MemorySettings()
        val firstWrite = CompletableDeferred<Unit>()
        val prefs = object : SettingsRepository by memory {
            override suspend fun setThemeMode(mode: ThemeMode) {
                firstWrite.await()
                memory.setThemeMode(mode)
            }
        }
        val actions = SettingsActions(prefs, MemoryHistory(), applicationScope())
        val first = actions.setThemeMode(ThemeMode.DARK)
        val next = actions.setSaveHistory(false)
        backgroundScope.cancel()
        firstWrite.complete(Unit)
        val rejected = actions.setVerboseErrors(true)
        runCurrent()
        assertThat(first.isCancelled).isTrue()
        assertThat(next.isCancelled).isTrue()
        assertThat(rejected.isCancelled).isTrue()
        assertThat(memory.settings.value).isEqualTo(AppSettings())
    }
}
