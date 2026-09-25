package com.localai.toolkit.feature.capability

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceAiViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `refresh coalesces taps and failure can be retried`() = runTest(main.dispatcher) {
        val pending = CompletableDeferred<Unit>()
        val calls = mutableListOf<Boolean>()
        var fail = true
        val manager = object : DeviceAiCapabilityManager {
            override val snapshot = MutableStateFlow(DeviceAiSnapshot())
            override suspend fun refresh(task: AiTask) = Unit
            override suspend fun refresh(force: Boolean) {
                calls += force
                pending.await()
                if (fail) error("Unavailable")
            }
        }
        val model = main.own(DeviceAiViewModel(manager))
        model.refresh()
        runCurrent()
        model.refresh()
        assertThat(calls).containsExactly(false)
        assertThat(model.isRefreshing.value).isTrue()
        pending.complete(Unit)
        advanceUntilIdle()
        assertThat(model.refreshFailed.value).isTrue()
        assertThat(model.isRefreshing.value).isFalse()
        fail = false
        model.refresh()
        advanceUntilIdle()
        assertThat(calls).containsExactly(false, true).inOrder()
        assertThat(model.refreshFailed.value).isFalse()
    }

    @Test fun `Nano status preserves download error and unknown instead of claiming Available`() {
        assertThat(DeviceAiSnapshot().nanoSummaryStatus()).isEqualTo(AiCapabilityStatus.UNKNOWN)
        AiCapabilityStatus.entries.forEach { status ->
            val snapshot = DeviceAiSnapshot(capabilities = AiTask.entries.associateWith {
                AiCapability(it, status, AiProvider.GEMINI_NANO)
            })
            assertThat(snapshot.nanoSummaryStatus()).isEqualTo(status)
        }
        val classicOnly = DeviceAiSnapshot(capabilities = mapOf(
            AiTask.TEXT_RECOGNITION to AiCapability(AiTask.TEXT_RECOGNITION, AiCapabilityStatus.AVAILABLE, AiProvider.ML_KIT),
        ))
        assertThat(classicOnly.nanoSummaryStatus()).isEqualTo(AiCapabilityStatus.UNKNOWN)
    }
}
