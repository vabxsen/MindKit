package com.localai.toolkit

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.testing.MainDispatcherRule
import com.localai.toolkit.testing.MemorySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `failed startup read has a manual retry and loads stored preferences afterward`() = runTest {
        var fail = true
        val expected = AppSettings(onboardingCompleted = true)
        val repository = object : SettingsRepository by MemorySettings() {
            override val settings = flow {
                if (fail) error("read failed")
                emit(expected)
            }
        }
        val model = main.own(MainViewModel(repository))
        runCurrent()
        assertThat(model.uiState.value).isEqualTo(AppUiState.Failed())
        fail = false
        model.retry()
        runCurrent()
        assertThat(model.uiState.value).isEqualTo(AppUiState.Ready(expected))
    }

    @Test fun `later read failure retains known settings instead of resetting the navigation graph`() = runTest {
        val repository = MemorySettings()
        val expected = AppSettings(onboardingCompleted = true)
        repository.settings.value = expected
        val model = main.own(MainViewModel(repository))
        runCurrent()
        repository.settings.value = expected.copy(storageReadFailed = true, saveHistory = false)
        runCurrent()
        assertThat(model.uiState.value).isEqualTo(AppUiState.Failed(expected))
        repository.settings.value = expected
        runCurrent()
        assertThat(model.uiState.value).isEqualTo(AppUiState.Ready(expected))
    }
}
