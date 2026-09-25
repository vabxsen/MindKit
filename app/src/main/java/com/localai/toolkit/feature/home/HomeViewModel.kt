package com.localai.toolkit.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.domain.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** The overall device readiness shown as a chip at the top of Home. */
enum class DeviceReadiness { CHECKING, READY, MODEL_REQUIRED, LIMITED }

/** One tool tile, already resolved against this device. */
data class ToolAvailability(
    val toolId: ToolId,
    val status: AiCapabilityStatus,
) {
    val enabled: Boolean
        get() = status != AiCapabilityStatus.UNSUPPORTED
}

data class HomeUiState(
    val readiness: DeviceReadiness = DeviceReadiness.CHECKING,
    val tools: List<ToolAvailability> = emptyList(),
    val recentItems: List<HistoryItem> = emptyList(),
    val historyLoading: Boolean = false,
    val historyFailed: Boolean = false,
    val capabilityCheckFailed: Boolean = false,
)

private data class RecentHistoryState(
    val items: List<HistoryItem> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val capabilityManager: DeviceAiCapabilityManager,
    historyRepository: HistoryRepository,
) : ViewModel() {
    private val historyRefresh = MutableStateFlow(0)
    private val capabilityCheckFailed = MutableStateFlow(false)
    private var checking = false
    private val history = historyRefresh.flatMapLatest {
        flow { emitAll(historyRepository.observe()) }
            .map { RecentHistoryState(items = it.take(2)) }
            .onStart { emit(RecentHistoryState(loading = true)) }
            .catch { emit(RecentHistoryState(failed = true)) }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        capabilityManager.snapshot,
        history,
        capabilityCheckFailed,
    ) { snapshot, recent, checkFailed ->
        snapshot.toUiState(recent.items).copy(
            historyLoading = recent.loading,
            historyFailed = recent.failed,
            capabilityCheckFailed = checkFailed,
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DeviceAiSnapshot().toUiState().copy(historyLoading = true),
        )

    init {
        // Availability is resolved when Home first needs it rather than at process start,
        // so a cold launch does not wait on AICore.
        refreshCapabilities(force = false)
    }

    fun refresh() {
        refreshCapabilities(force = true)
    }

    fun retryHistory() { historyRefresh.value++ }

    private fun refreshCapabilities(force: Boolean) {
        if (checking) return
        checking = true
        capabilityCheckFailed.value = false
        viewModelScope.launch {
            try {
                capabilityManager.refresh(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                capabilityCheckFailed.value = true
            } finally {
                checking = false
            }
        }
    }
}

internal fun DeviceAiSnapshot.toUiState(
    recentItems: List<HistoryItem> = emptyList(),
): HomeUiState {
    val tools = ToolId.entries.map { toolId ->
        val task = toolId.requiredTask
        ToolAvailability(
            toolId = toolId,
            // A tool with no capability requirement, such as the deterministic developer
            // utilities, is always available.
            status = if (task == null) AiCapabilityStatus.AVAILABLE else this[task].status,
        )
    }

    val readiness = when {
        capabilities.isEmpty() -> DeviceReadiness.CHECKING
        hasReadyGenAi -> DeviceReadiness.READY
        genAiNeedsDownload -> DeviceReadiness.MODEL_REQUIRED
        else -> DeviceReadiness.LIMITED
    }

    return HomeUiState(readiness = readiness, tools = tools, recentItems = recentItems)
}
