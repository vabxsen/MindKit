package com.localai.toolkit.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `leaving onboarding lets its accepted save finish without late navigation`() = runTest {
        val memory = MemorySettings()
        val pending = CompletableDeferred<Unit>()
        val settings = object : SettingsRepository by memory {
            override suspend fun setOnboardingCompleted(completed: Boolean) {
                pending.await()
                memory.setOnboardingCompleted(completed)
            }
        }
        val vm = main.own(OnboardingViewModel(TestCapabilities(), main.settingsActions(settings)))
        var navigations = 0
        vm.complete { navigations++ }
        runCurrent()
        main.clearViewModels()
        pending.complete(Unit)
        runCurrent()
        assertThat(memory.settings.value.onboardingCompleted).isTrue()
        assertThat(navigations).isEqualTo(0)
    }

    @Test fun `finish persists once and navigates once despite repeated taps`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var writes = 0
        var navigations = 0
        val settings = object : SettingsRepository by MemorySettings() {
            override suspend fun setOnboardingCompleted(completed: Boolean) { writes++; pending.await() }
        }
        val vm = main.own(OnboardingViewModel(TestCapabilities(), main.settingsActions(settings)))
        vm.complete { navigations++ }
        vm.complete { navigations++ }
        runCurrent()
        assertThat(writes).isEqualTo(1)
        assertThat(navigations).isEqualTo(0)
        pending.complete(Unit)
        runCurrent()
        vm.complete { navigations++ }
        runCurrent()
        assertThat(navigations).isEqualTo(1)
    }

    @Test fun `failed preferences write stays on setup and can be retried`() = runTest {
        var fail = true
        val settings = object : SettingsRepository by MemorySettings() {
            override suspend fun setOnboardingCompleted(completed: Boolean) { if (fail) error("disk") }
        }
        val vm = main.own(OnboardingViewModel(TestCapabilities(), main.settingsActions(settings)))
        var navigations = 0
        vm.complete { navigations++ }
        runCurrent()
        assertThat(vm.finishFailed.value).isTrue()
        assertThat(vm.isCompleting.value).isFalse()
        assertThat(navigations).isEqualTo(0)
        fail = false
        vm.complete { navigations++ }
        runCurrent()
        assertThat(navigations).isEqualTo(1)
    }

    @Test fun `device check failure has a retryable stage`() = runTest {
        var fail = true
        val capabilities = object : DeviceAiCapabilityManager by TestCapabilities() {
            override suspend fun refresh(force: Boolean) { if (fail) error("unavailable") }
        }
        val vm = main.own(OnboardingViewModel(capabilities, main.settingsActions(MemorySettings())))
        vm.checkDevice()
        runCurrent()
        assertThat(vm.stage.value).isEqualTo(OnboardingStage.ERROR)
        fail = false
        vm.checkDevice()
        runCurrent()
        assertThat(vm.stage.value).isEqualTo(OnboardingStage.RESULTS)
    }

    @Test fun `checking coalesces repeated taps and can be skipped without waiting for AI`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var checks = 0
        val capabilities = object : DeviceAiCapabilityManager by TestCapabilities() {
            override suspend fun refresh(force: Boolean) { checks++; pending.await() }
        }
        val settings = MemorySettings()
        val vm = main.own(OnboardingViewModel(capabilities, main.settingsActions(settings)))
        vm.checkDevice()
        vm.checkDevice()
        runCurrent()
        assertThat(checks).isEqualTo(1)
        var finished = false
        vm.complete { finished = true }
        runCurrent()
        assertThat(finished).isTrue()
        assertThat(settings.settings.value.onboardingCompleted).isTrue()
        pending.complete(Unit)
        runCurrent()
        assertThat(vm.stage.value).isEqualTo(OnboardingStage.CHECKING)
    }
}
