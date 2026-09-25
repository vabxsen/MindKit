package com.localai.toolkit.feature.home

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.capability.CapabilitySnapshotCache
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `Home fills a partially checked cache and Refresh bypasses fresh results`() = runTest(main.dispatcher) {
        val calls = mutableListOf<AiTask>()
        var status = AiCapabilityStatus.AVAILABLE
        val capabilities = CapabilitySnapshotCache(
            ioDispatcher = main.dispatcher,
            resolve = { task ->
                calls += task
                AiCapability(task, status, AiProvider.GEMINI_NANO)
            },
            wallClockMillis = { 1_000L },
            elapsedRealtimeMillis = { 0L },
        )
        capabilities.refresh(AiTask.ASK)
        val model = main.own(HomeViewModel(capabilities, MemoryHistory()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertThat(model.uiState.value.tools.map { it.status }.toSet())
            .containsExactly(AiCapabilityStatus.AVAILABLE)
        assertThat(calls).hasSize(AiTask.entries.size + 1)

        status = AiCapabilityStatus.DOWNLOADABLE
        model.refresh()
        runCurrent()
        assertThat(calls).hasSize(AiTask.entries.size * 2 + 1)
        assertThat(model.uiState.value.tools.first { it.toolId == ToolId.ASK }.status)
            .isEqualTo(AiCapabilityStatus.DOWNLOADABLE)
        assertThat(model.uiState.value.capabilityCheckFailed).isFalse()
    }

    @Test fun `history failure never removes tools and Retry resumes recent items`() = runTest(main.dispatcher) {
        val history = MemoryHistory()
        val pending = CompletableDeferred<Unit>()
        var fail = true
        val repository = object : HistoryRepository by history {
            override fun observe(query: String, types: Set<HistoryType>): Flow<List<HistoryItem>> = flow {
                pending.await()
                if (fail) error("database read failed")
                emitAll(history.observe(query, types))
            }
        }
        val capabilities = TestCapabilities()
        val model = main.own(HomeViewModel(capabilities, repository))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        assertThat(model.uiState.value.tools).hasSize(ToolId.entries.size)
        assertThat(model.uiState.value.historyLoading).isTrue()
        pending.complete(Unit)
        runCurrent()
        assertThat(model.uiState.value.historyFailed).isTrue()
        assertThat(model.uiState.value.tools).hasSize(ToolId.entries.size)
        capabilities.snapshot.value = DeviceAiSnapshot(capabilities = mapOf(
            AiTask.ASK to AiCapability(AiTask.ASK, AiCapabilityStatus.AVAILABLE, AiProvider.GEMINI_NANO),
        ))
        runCurrent()
        assertThat(model.uiState.value.readiness).isEqualTo(DeviceReadiness.READY)
        fail = false
        history.items.value = (1L..3).map { HistoryItem(it, HistoryType.ASK, "Item $it", "in", "out", it) }
        model.retryHistory()
        runCurrent()
        assertThat(model.uiState.value.historyFailed).isFalse()
        assertThat(model.uiState.value.recentItems.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test fun `capability failure is recoverable and repeated refresh taps coalesce`() = runTest(main.dispatcher) {
        var fail = true
        val pending = CompletableDeferred<Unit>()
        val calls = mutableListOf<Boolean>()
        val capabilities = object : DeviceAiCapabilityManager {
            override val snapshot = MutableStateFlow(DeviceAiSnapshot())
            override suspend fun refresh(task: AiTask) = Unit
            override suspend fun refresh(force: Boolean) {
                calls += force
                pending.await()
                if (fail) error("platform")
            }
        }
        val model = main.own(HomeViewModel(capabilities, MemoryHistory()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        model.refresh()
        model.refresh()
        pending.complete(Unit)
        runCurrent()
        assertThat(calls).containsExactly(false)
        assertThat(model.uiState.value.capabilityCheckFailed).isTrue()
        fail = false
        model.refresh()
        runCurrent()
        assertThat(calls).containsExactly(false, true).inOrder()
        assertThat(model.uiState.value.capabilityCheckFailed).isFalse()
    }
}
