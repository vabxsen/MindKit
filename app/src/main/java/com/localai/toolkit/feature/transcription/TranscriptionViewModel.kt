package com.localai.toolkit.feature.transcription

import com.localai.toolkit.feature.common.HistorySaveController

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.ModelDownloadState
import com.localai.toolkit.ai.gemini.TranscriptChunk
import com.localai.toolkit.ai.gemini.TranscriptionEngine
import com.localai.toolkit.ai.gemini.TranscriptionMode
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.core.util.titleOf
import com.localai.toolkit.domain.model.AiCapabilityStatus
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiProvider
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TranscriptionUiState(
    val mode: TranscriptionMode = TranscriptionMode.BASIC,
    val resultMode: TranscriptionMode = TranscriptionMode.BASIC,
    val basicStatus: AiCapabilityStatus = AiCapabilityStatus.UNKNOWN,
    val advancedStatus: AiCapabilityStatus = AiCapabilityStatus.UNKNOWN,
    val provider: AiProvider = AiProvider.NONE,
    val supportsFileInput: Boolean = false,
    val isRecording: Boolean = false,
    val isTranscribingFile: Boolean = false,
    /** Segments the recogniser has settled on. */
    val finalText: String = "",
    /** The in-flight segment, shown but not yet committed. */
    val partialText: String = "",
    val downloadState: ModelDownloadState = ModelDownloadState.Idle,
    val downloadCheckFailure: AiFailure? = null,
    val isCheckingDownload: Boolean = false,
    val failure: AiFailure? = null,
    val savedToHistory: Boolean = false,
) {
    /** What the user should see: settled text plus whatever is being heard right now. */
    val transcript: String
        get() = listOf(finalText, partialText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

    val hasTranscript: Boolean get() = transcript.isNotBlank()

    val isBusy: Boolean get() = isRecording || isTranscribingFile

    val currentStatus: AiCapabilityStatus
        get() = if (mode == TranscriptionMode.ADVANCED) advancedStatus else basicStatus

    val canStart: Boolean
        get() = currentStatus == AiCapabilityStatus.AVAILABLE && !isBusy &&
            downloadState !is ModelDownloadState.Started &&
            downloadState !is ModelDownloadState.InProgress &&
            downloadState !is ModelDownloadState.Failed
}

@HiltViewModel
class TranscriptionViewModel @Inject constructor(
    private val engine: TranscriptionEngine,
    private val historyRepository: HistoryRepository,
    private val toolHandoff: ToolHandoff,
    private val capabilityManager: DeviceAiCapabilityManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranscriptionUiState())
    val uiState: StateFlow<TranscriptionUiState> = _uiState.asStateFlow()

    private val historySave = HistorySaveController(historyRepository, viewModelScope)
    val saveFeedback = historySave.feedback

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var job: Job? = null
    private val recognitionLock = Mutex()
    private var availabilityJob: Job? = null
    private val downloadJobs = mutableMapOf<TranscriptionMode, Job>()
    private val downloads = mutableMapOf<TranscriptionMode, ModelDownloadState>()
    private val downloadCheckJobs = mutableMapOf<TranscriptionMode, Job>()
    private val downloadCheckFailures = mutableMapOf<TranscriptionMode, AiFailure>()
    private var stopJob: Job? = null
    private var session = 0L
    private var pendingAudio: Uri? = null
    private var lastAudio: Uri? = null
    private var hasCheckedAvailability = false

    init {
        pendingAudio = toolHandoff.consume(ToolId.TRANSCRIBE)?.audioUri
        refreshAvailability()
    }

    fun refreshAvailability() {
        availabilityJob?.cancel()
        val failureAtStart = _uiState.value.failure
        val downloadsAtStart = downloads.toMap()
        val downloadJobsAtStart = downloadJobs.toMap()
        availabilityJob = viewModelScope.launch {
            try {
                val basic = engine.status(TranscriptionMode.BASIC)
                val advanced = engine.status(TranscriptionMode.ADVANCED)
                // Default to the better mode the device can actually serve, rather than
                // starting on one that is unavailable.
                val mode = if (hasCheckedAvailability) {
                    _uiState.value.mode
                } else if (advanced == AiCapabilityStatus.AVAILABLE) {
                    TranscriptionMode.ADVANCED
                } else {
                    TranscriptionMode.BASIC
                }
                val provider = engine.provider(mode)
                val supportsFileInput = engine.supportsFileInput(mode)
                currentCoroutineContext().ensureActive()
                for (checkedMode in TranscriptionMode.entries) {
                    val status = if (checkedMode == TranscriptionMode.BASIC) basic else advanced
                    val previous = downloadsAtStart[checkedMode]
                    val current = downloads[checkedMode]
                    if ((previous.isActiveDownload() || previous is ModelDownloadState.Failed) &&
                        (current.isActiveDownload() || current is ModelDownloadState.Failed) &&
                        downloadJobs[checkedMode] === downloadJobsAtStart[checkedMode] &&
                        (status == AiCapabilityStatus.AVAILABLE || status == AiCapabilityStatus.UNSUPPORTED)
                    ) {
                        // A status check can recover a lost terminal callback. Wait for
                        // that attempt's cleanup before enabling inference or another download.
                        downloadJobsAtStart[checkedMode]?.cancelAndJoin()
                        currentCoroutineContext().ensureActive()
                        if (downloadJobs[checkedMode] === downloadJobsAtStart[checkedMode]) {
                            updateDownload(checkedMode, if (status == AiCapabilityStatus.AVAILABLE) {
                                ModelDownloadState.Completed
                            } else {
                                ModelDownloadState.Idle
                            })
                            refreshDownloadedCapability(checkedMode)
                        }
                    }
                }
                _uiState.value = _uiState.value.copy(
                    basicStatus = basic,
                    advancedStatus = advanced,
                    mode = mode,
                    provider = provider,
                    supportsFileInput = supportsFileInput,
                    // A slow check must not dismiss a newer recognition/permission error.
                    failure = if (_uiState.value.failure !== failureAtStart) {
                        _uiState.value.failure
                    } else {
                        (_uiState.value.downloadState as? ModelDownloadState.Failed)?.reason
                    },
                )
                hasCheckedAvailability = true
                pendingAudio?.let { uri ->
                    if (_uiState.value.canStart && _uiState.value.supportsFileInput) {
                        pendingAudio = null
                        onFileSelected(uri)
                    } else if (_uiState.value.currentStatus == AiCapabilityStatus.UNSUPPORTED ||
                        (_uiState.value.currentStatus == AiCapabilityStatus.AVAILABLE &&
                            !_uiState.value.supportsFileInput)
                    ) {
                        _uiState.value = _uiState.value.copy(
                            failure = AiFailure.Unsupported("File transcription is not supported by this recognizer"),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(
                    basicStatus = AiCapabilityStatus.ERROR,
                    advancedStatus = AiCapabilityStatus.ERROR,
                    provider = AiProvider.NONE,
                    supportsFileInput = false,
                    failure = _uiState.value.failure?.takeIf { it !== failureAtStart }
                        ?: (error as? AiException)?.failure ?: AiFailure.Unknown(error.message),
                )
            }
        }
    }

    fun onModeChange(mode: TranscriptionMode) {
        if (_uiState.value.isBusy) return
        hasCheckedAvailability = true
        val download = downloads[mode] ?: ModelDownloadState.Idle
        _uiState.value = _uiState.value.copy(
            mode = mode,
            provider = AiProvider.NONE,
            supportsFileInput = false,
            downloadState = download,
            downloadCheckFailure = downloadCheckFailures[mode],
            isCheckingDownload = downloadCheckJobs[mode]?.isCompleted == false,
            failure = (download as? ModelDownloadState.Failed)?.reason,
        )
        refreshAvailability()
    }

    /**
     * Starts microphone transcription.
     *
     * The screen requests RECORD_AUDIO immediately before calling this; the permission is
     * never requested on screen entry, only when the user taps record.
     */
    fun onStartRecording() {
        val state = _uiState.value
        if (!state.canStart) return

        val activeSession = ++session
        stopJob?.cancel()
        // An accepted recording replaces any shared file waiting for a model.
        // Permission denial or a disabled Record attempt never reaches this point.
        pendingAudio = null
        lastAudio = null
        job?.cancel()
        _uiState.value = state.copy(
            isRecording = true,
            resultMode = state.mode,
            failure = null,
            finalText = "",
            partialText = "",
            savedToHistory = false,
        )

        job = viewModelScope.launch {
            collectTranscript({ engine.transcribeMicrophone(state.mode) }, activeSession) {
                if (session == activeSession) {
                    _uiState.value = _uiState.value.copy(isRecording = false)
                }
            }
        }
    }

    fun onStopRecording() {
        if (!_uiState.value.isRecording || stopJob?.isActive == true) return
        val recording = job
        val activeSession = session
        stopJob = viewModelScope.launch {
            try {
                // Allow the final utterance to arrive, but never leave Stop stuck forever.
                withTimeoutOrNull(2_000) {
                    engine.stop()
                    recording?.join()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (session == activeSession) {
                    _uiState.value = _uiState.value.copy(
                        failure = (error as? AiException)?.failure ?: AiFailure.Unknown(error.message),
                    )
                }
            } finally {
                recording?.cancel()
                if (session == activeSession) {
                    _uiState.value = _uiState.value.copy(isRecording = false)
                }
            }
        }
    }

    fun onFileSelected(uri: Uri) {
        val state = _uiState.value
        if (state.isBusy) return
        // Keep only the newest accepted source, including late picker results that
        // arrive while availability is being checked or a model is downloading.
        pendingAudio = null
        lastAudio = null
        if (!state.canStart || !state.supportsFileInput) {
            pendingAudio = uri
            return
        }

        val activeSession = ++session
        stopJob?.cancel()
        lastAudio = uri
        job?.cancel()
        _uiState.value = state.copy(
            isTranscribingFile = true,
            resultMode = state.mode,
            failure = null,
            finalText = "",
            partialText = "",
            savedToHistory = false,
        )

        job = viewModelScope.launch {
            collectTranscript({ engine.transcribeFile(uri, state.mode) }, activeSession) {
                if (session == activeSession) {
                    _uiState.value = _uiState.value.copy(isTranscribingFile = false)
                }
            }
        }
    }

    private suspend fun collectTranscript(
        createFlow: () -> kotlinx.coroutines.flow.Flow<TranscriptChunk>,
        activeSession: Long,
        onFinished: () -> Unit,
    ) {
        try {
            // Cancellation can take time to release native resources. Replacement work
            // waits for that collection to unwind; cancelled queued requests never start.
            recognitionLock.withLock {
                // Include synchronous setup in the failure boundary. Completed is terminal.
                createFlow().takeWhile { it != TranscriptChunk.Completed }.collect { chunk ->
                    if (session != activeSession) return@collect
                    when (chunk) {
                        is TranscriptChunk.Partial ->
                            _uiState.value = _uiState.value.copy(partialText = chunk.text)

                        is TranscriptChunk.Final -> {
                            val merged = listOf(_uiState.value.finalText, chunk.text)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                            _uiState.value = _uiState.value.copy(
                                finalText = merged,
                                partialText = "",
                            )
                        }

                        TranscriptChunk.Completed -> Unit
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (session == activeSession) {
                _uiState.value = _uiState.value.copy(
                    failure = (error as? AiException)?.failure ?: AiFailure.Unknown(error.message),
                )
            }
        } finally {
            onFinished()
        }
    }

    fun onDownloadModel() {
        val mode = _uiState.value.mode
        if (downloadJobs[mode]?.isCompleted == false) return
        downloadCheckJobs.remove(mode)?.cancel()
        updateDownloadCheck(mode, null, false)
        updateDownload(mode, ModelDownloadState.Started(null))
        val downloadJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var terminal: ModelDownloadState? = null
            try {
                engine.downloadModel(mode).takeWhile { state ->
                    currentCoroutineContext().ensureActive()
                    if (state is ModelDownloadState.Completed || state is ModelDownloadState.Failed) {
                        terminal = state
                        false
                    } else {
                        updateDownload(mode, state)
                        true
                    }
                }.collect {}
                currentCoroutineContext().ensureActive()
                // Publish only after the upstream collector has released its client.
                updateDownload(mode, terminal ?: ModelDownloadState.Failed(
                    AiFailure.DownloadFailed("Download ended without a completion result"),
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                updateDownload(mode, ModelDownloadState.Failed(
                    (error as? AiException)?.failure ?: AiFailure.DownloadFailed(error.message),
                ))
                return@launch
            }
            if (terminal is ModelDownloadState.Completed) {
                refreshAvailability()
                // This failure belongs to Check status, never to the completed download.
                refreshDownloadedCapability(mode)
            }
        }
        downloadJobs[mode] = downloadJob
        downloadJob.start()
    }

    private fun updateDownload(mode: TranscriptionMode, state: ModelDownloadState) {
        val previousFailure = (downloads[mode] as? ModelDownloadState.Failed)?.reason
        downloads[mode] = state
        if (_uiState.value.mode == mode) {
            val failure = _uiState.value.failure
            _uiState.value = _uiState.value.copy(
                downloadState = state,
                failure = if (failure != null && failure !== previousFailure) failure
                    else (state as? ModelDownloadState.Failed)?.reason,
            )
        }
    }

    private fun refreshDownloadedCapability(mode: TranscriptionMode) {
        if (downloadCheckJobs[mode]?.isCompleted == false) return
        updateDownloadCheck(mode, null, true)
        val checkJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                capabilityManager.refresh(
                    if (mode == TranscriptionMode.ADVANCED) AiTask.ADVANCED_TRANSCRIPTION
                    else AiTask.BASIC_TRANSCRIPTION,
                )
                currentCoroutineContext().ensureActive()
                updateDownloadCheck(mode, null, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                updateDownloadCheck(mode, (error as? AiException)?.failure ?: AiFailure.Unknown(error.message), false)
            } finally {
                if (downloadCheckJobs[mode] === currentCoroutineContext()[Job] && _uiState.value.mode == mode) {
                    _uiState.value = _uiState.value.copy(isCheckingDownload = false)
                }
            }
        }
        downloadCheckJobs[mode] = checkJob
        checkJob.start()
    }

    private fun updateDownloadCheck(mode: TranscriptionMode, failure: AiFailure?, checking: Boolean) {
        if (failure == null) downloadCheckFailures.remove(mode) else downloadCheckFailures[mode] = failure
        if (_uiState.value.mode == mode) {
            _uiState.value = _uiState.value.copy(downloadCheckFailure = failure, isCheckingDownload = checking)
        }
    }

    private fun ModelDownloadState?.isActiveDownload() =
        this is ModelDownloadState.Started || this is ModelDownloadState.InProgress

    fun onSave() {
        val state = _uiState.value
        if (!state.hasTranscript || state.isBusy || state.savedToHistory) return
        historySave.save(
            item = HistoryItem(
                type = HistoryType.TRANSCRIPTION,
                title = titleOf(state.transcript),
                inputPreview = previewOf(state.transcript),
                output = state.transcript,
                createdAtEpochMillis = System.currentTimeMillis(),
                metadata = state.resultMode.name,
            ),
            isCurrent = { _uiState.value.transcript == state.transcript && !_uiState.value.isBusy },
        ) { saved ->
            _uiState.value = _uiState.value.copy(savedToHistory = saved)
        }
    }

    fun onClear() {
        ++session
        stopJob?.cancel()
        job?.cancel()
        engine.release()
        pendingAudio = null
        lastAudio = null
        _uiState.value = _uiState.value.copy(
            finalText = "",
            partialText = "",
            failure = null,
            savedToHistory = false,
            isRecording = false,
            isTranscribingFile = false,
        )
    }

    /** Leaving the recording UI ends microphone ownership, preserving captured text. */
    fun onScreenHidden() {
        if (!_uiState.value.isRecording) return
        ++session
        stopJob?.cancel()
        job?.cancel()
        engine.release()
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    fun onRetry() {
        val state = _uiState.value
        if (state.isBusy) return
        if (state.downloadState.isActiveDownload()) {
            refreshAvailability()
            return
        }
        if (state.downloadCheckFailure != null && state.failure == null) {
            refreshDownloadedCapability(state.mode)
            refreshAvailability()
            return
        }
        if (state.currentStatus == AiCapabilityStatus.ERROR ||
            state.currentStatus == AiCapabilityStatus.TEMPORARILY_UNAVAILABLE ||
            state.currentStatus == AiCapabilityStatus.UNKNOWN
        ) {
            refreshAvailability()
            if (state.downloadCheckFailure != null) refreshDownloadedCapability(state.mode)
            return
        }
        if (state.downloadState is ModelDownloadState.Failed) {
            onDownloadModel()
            return
        }
        if (!state.canStart || !state.supportsFileInput || pendingAudio != null) {
            refreshAvailability()
            return
        }
        val audio = lastAudio
        if (audio != null) onFileSelected(audio) else refreshAvailability()
    }

    fun sendTo(target: ToolId) {
        val text = _uiState.value.transcript
        if (text.isBlank()) return
        toolHandoff.send(HandoffPayload(target = target, text = text))
    }

    /** Reports that the user declined the microphone permission. */
    fun onMicrophoneDenied() {
        _uiState.value = _uiState.value.copy(
            failure = AiFailure.Unsupported("RECORD_AUDIO not granted"),
        )
    }

    override fun onCleared() {
        job?.cancel()
        // Releases the recogniser so the microphone is not held by a screen that is gone.
        engine.release()
    }
}
