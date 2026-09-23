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
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val capabilityManager: DeviceAiCapabilityManager,
    historyRepository: HistoryRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        capabilityManager.snapshot,
        historyRepository.observe(),
    ) { snapshot, history -> snapshot.toUiState(history.take(2)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeUiState(),
        )

    init {
        // Availability is resolved when Home first needs it rather than at process start,
        // so a cold launch does not wait on AICore.
        viewModelScope.launch { capabilityManager.refresh(force = false) }
    }

    fun refresh() {
        viewModelScope.launch { capabilityManager.refresh(force = true) }
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
