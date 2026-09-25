package com.localai.toolkit.feature.common

import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private data class CheckState(val checking: Boolean = false, val failure: AiFailure? = null)
    private val checkState = MutableStateFlow(CheckState())
    val capability: StateFlow<AiCapability> = combine(capabilityManager.snapshot, checkState) { snapshot, check ->
        val actual = snapshot[task]
        when {
            check.checking -> actual.copy(status = AiCapabilityStatus.UNKNOWN)
            check.failure != null -> actual.copy(status = AiCapabilityStatus.ERROR, detail = check.failure.technicalDetail)
            else -> actual
        }
    }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), AiCapability.unknown(task))

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()
    private var downloadJob: Job? = null
    private var checkJob: Job? = null
    private val checkLock = Mutex()
    private var released = false

    init {
        // Resolved when the screen opens rather than at app start, so a cold launch does
        // not wait on AICore.
        startCheck(initial = true)
    }

    fun refresh() = startCheck()

    private fun startCheck(initial: Boolean = false, restart: Boolean = false) {
        if (released || (!restart && checkJob?.isActive == true)) return
        checkJob?.cancel()
        val downloadAtStart = downloadJob
        checkState.value = CheckState(checking = true)
        checkJob = scope.launch {
            try {
                // A post-download check supersedes an older check, but waits for its
                // cancellation cleanup so that an old snapshot cannot win afterward.
                checkLock.withLock {
                    if (initial) capabilityManager.refresh(force = false) else capabilityManager.refresh(task)
                    currentCoroutineContext().ensureActive()
                    val actual = capabilityManager.snapshot.value[task]
                    checkState.value = if (actual.status == AiCapabilityStatus.UNKNOWN) {
                        CheckState(failure = AiFailure.Unknown("Availability check returned no result"))
                    } else CheckState()
                    // Check status can recover a download whose terminal callback was lost.
                    // It must not cancel a newer download started after this check began.
                    if (!initial && downloadAtStart != null && downloadJob === downloadAtStart &&
                        downloadAtStart.isActive && _downloadState.value.isInProgress()
                    ) {
                        when (actual.status) {
                            AiCapabilityStatus.AVAILABLE -> {
                                downloadAtStart.cancel()
                                _downloadState.value = ModelDownloadState.Completed
                            }
                            AiCapabilityStatus.UNSUPPORTED -> {
                                downloadAtStart.cancel()
                                _downloadState.value = ModelDownloadState.Idle
                            }
                            else -> Unit
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                checkState.value = CheckState(failure = (error as? AiException)?.failure ?: AiFailure.Unknown(error.message))
            }
        }
    }

    /**
     * Starts the model download for this feature.
     *
     * Always user-initiated. On completion the capability is re-checked so the screen
     * flips from "download required" to usable without the user having to back out.
     */
    fun download() {
        if (released || downloadJob?.isActive == true) return
        _downloadState.value = ModelDownloadState.Started(null)
        downloadJob = scope.launch {
            var terminal: ModelDownloadState? = null
            try {
                engine.downloadModel(task).takeWhile { state ->
                    if (state is ModelDownloadState.Completed || state is ModelDownloadState.Failed) {
                        terminal = state
                        false
                    } else {
                        _downloadState.value = state
                        true
                    }
                }.collect { }
                currentCoroutineContext().ensureActive()
                _downloadState.value = terminal ?: ModelDownloadState.Failed(
                    AiFailure.DownloadFailed("Download ended without a completion result"),
                )
                if (terminal is ModelDownloadState.Completed) {
                    startCheck(restart = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _downloadState.value = terminal as? ModelDownloadState.Failed ?: ModelDownloadState.Failed(
                    (error as? AiException)?.failure ?: AiFailure.DownloadFailed(error.message),
                )
            }
        }
    }

    /** Releases inference resources for this feature. Call from onCleared. */
    fun release() {
        if (released) return
        released = true
        checkJob?.cancel()
        downloadJob?.cancel()
        checkState.value = CheckState()
        if (_downloadState.value.isInProgress()) _downloadState.value = ModelDownloadState.Idle
        // Disposal must not crash the owning ViewModel if a platform close fails.
        runCatching { engine.release(task) }
    }
}

private fun ModelDownloadState.isInProgress() =
    this is ModelDownloadState.InProgress || this is ModelDownloadState.Started

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

    /** Download progress continues, but an availability check failed and can be retried. */
    DOWNLOADING_CHECK_FAILED,

    /** This device cannot run the feature. */
    UNSUPPORTED,

    /** The availability check itself failed. */
    ERROR,
}

fun gateStateOf(
    capability: AiCapability,
    downloadState: ModelDownloadState,
): GateState = when {
    downloadState.isInProgress() && (capability.status == AiCapabilityStatus.ERROR ||
        capability.status == AiCapabilityStatus.TEMPORARILY_UNAVAILABLE) -> GateState.DOWNLOADING_CHECK_FAILED

    downloadState is ModelDownloadState.InProgress || downloadState is ModelDownloadState.Started ->
        GateState.DOWNLOADING

    capability.status == AiCapabilityStatus.AVAILABLE -> GateState.READY
    capability.status == AiCapabilityStatus.UNSUPPORTED -> GateState.UNSUPPORTED
    downloadState is ModelDownloadState.Failed -> GateState.NEEDS_DOWNLOAD
    capability.status == AiCapabilityStatus.DOWNLOADING -> GateState.DOWNLOADING
    capability.status == AiCapabilityStatus.DOWNLOADABLE -> GateState.NEEDS_DOWNLOAD
    capability.status == AiCapabilityStatus.ERROR -> GateState.ERROR
    capability.status == AiCapabilityStatus.TEMPORARILY_UNAVAILABLE -> GateState.ERROR
    else -> GateState.CHECKING
}
