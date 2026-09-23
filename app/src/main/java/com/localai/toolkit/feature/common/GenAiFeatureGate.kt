package com.localai.toolkit.feature.common

import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The availability and download concerns every Gemini Nano tool shares.
 *
 * Each of Ask, Summarize, Rewrite, Proofread and Image AI has to answer the same three
 * questions - is this supported, does a model need downloading, and how is that download
 * going - so the logic lives here once and each ViewModel composes it rather than
 * reimplementing it.
 *
 * @param scope the owning ViewModel's scope, so work stops when the screen does.
 */
class GenAiFeatureGate(
    private val task: AiTask,
    private val capabilityManager: DeviceAiCapabilityManager,
    private val engine: AiEngine,
    private val scope: CoroutineScope,
) {

    val capability: StateFlow<AiCapability> = capabilityManager.snapshot
        .map { it[task] }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AiCapability.unknown(task))

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    init {
        // Resolved when the screen opens rather than at app start, so a cold launch does
        // not wait on AICore.
        scope.launch { capabilityManager.refresh(force = false) }
    }

    fun refresh() {
        scope.launch { capabilityManager.refresh(task) }
    }

    /**
     * Starts the model download for this feature.
     *
     * Always user-initiated. On completion the capability is re-checked so the screen
     * flips from "download required" to usable without the user having to back out.
     */
    fun download() {
        if (_downloadState.value is ModelDownloadState.InProgress) return
        scope.launch {
            engine.downloadModel(task).collect { state ->
                _downloadState.value = state
                if (state is ModelDownloadState.Completed) {
                    capabilityManager.refresh(task)
                }
            }
        }
    }

    /** Releases inference resources for this feature. Call from onCleared. */
    fun release() {
        engine.release(task)
    }
}

/**
 * What the shared gate UI should show.
 *
 * Derived rather than stored so it cannot drift out of sync with the capability.
 */
enum class GateState {
    /** Still resolving; show a labelled check rather than the tool. */
    CHECKING,

    /** Usable now. */
    READY,

    /** Supported, but the model has to be fetched first. */
    NEEDS_DOWNLOAD,

    /** A download is running. */
    DOWNLOADING,

    /** This device cannot run the feature. */
    UNSUPPORTED,

    /** The availability check itself failed. */
    ERROR,
}

fun gateStateOf(
    capability: AiCapability,
    downloadState: ModelDownloadState,
): GateState = when {
    downloadState is ModelDownloadState.InProgress || downloadState is ModelDownloadState.Started ->
        GateState.DOWNLOADING

    capability.status == AiCapabilityStatus.AVAILABLE -> GateState.READY
    capability.status == AiCapabilityStatus.DOWNLOADING -> GateState.DOWNLOADING
    capability.status == AiCapabilityStatus.DOWNLOADABLE -> GateState.NEEDS_DOWNLOAD
    capability.status == AiCapabilityStatus.UNSUPPORTED -> GateState.UNSUPPORTED
    capability.status == AiCapabilityStatus.ERROR -> GateState.ERROR
    capability.status == AiCapabilityStatus.TEMPORARILY_UNAVAILABLE -> GateState.ERROR
    else -> GateState.CHECKING
}
