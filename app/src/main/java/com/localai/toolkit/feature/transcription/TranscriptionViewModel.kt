package com.localai.toolkit.feature.transcription

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranscriptionUiState(
    val mode: TranscriptionMode = TranscriptionMode.BASIC,
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
        get() = currentStatus == AiCapabilityStatus.AVAILABLE && !isBusy
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

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var job: Job? = null

    init {
        refreshAvailability()
        // An audio file shared into the app, or handed over from the share router.
        toolHandoff.consume(ToolId.TRANSCRIBE)?.audioUri?.let { uri ->
            viewModelScope.launch {
                // Availability has to resolve before a file can be transcribed.
                refreshAvailability()
                onFileSelected(uri)
            }
        }
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            val basic = engine.status(TranscriptionMode.BASIC)
            val advanced = engine.status(TranscriptionMode.ADVANCED)
            // Default to the better mode the device can actually serve, rather than
            // starting on one that is unavailable.
            val mode = if (advanced == AiCapabilityStatus.AVAILABLE) {
                TranscriptionMode.ADVANCED
            } else {
                TranscriptionMode.BASIC
            }
            _uiState.value = _uiState.value.copy(
                basicStatus = basic,
                advancedStatus = advanced,
                mode = mode,
                provider = engine.provider(mode),
                supportsFileInput = engine.supportsFileInput(mode),
            )
        }
    }

    fun onModeChange(mode: TranscriptionMode) {
        if (_uiState.value.isBusy) return
        _uiState.value = _uiState.value.copy(mode = mode)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                provider = engine.provider(mode),
                supportsFileInput = engine.supportsFileInput(mode),
            )
        }
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

        job?.cancel()
        _uiState.value = state.copy(
            isRecording = true,
            failure = null,
            finalText = "",
            partialText = "",
            savedToHistory = false,
        )

        job = viewModelScope.launch {
            collectTranscript(engine.transcribeMicrophone(state.mode)) {
                _uiState.value = _uiState.value.copy(isRecording = false)
            }
        }
    }

    fun onStopRecording() {
        viewModelScope.launch {
            engine.stop()
            // Cancelling outright would drop the final segment the recogniser is about
            // to deliver, so the flow is left to finish on its own.
            _uiState.value = _uiState.value.copy(isRecording = false)
        }
    }

    fun onFileSelected(uri: Uri) {
        val state = _uiState.value
        if (state.isBusy) return

        job?.cancel()
        _uiState.value = state.copy(
            isTranscribingFile = true,
            failure = null,
            finalText = "",
            partialText = "",
            savedToHistory = false,
        )

        job = viewModelScope.launch {
            collectTranscript(engine.transcribeFile(uri, state.mode)) {
                _uiState.value = _uiState.value.copy(isTranscribingFile = false)
            }
        }
    }

    private suspend fun collectTranscript(
        flow: kotlinx.coroutines.flow.Flow<TranscriptChunk>,
        onFinished: () -> Unit,
    ) {
        try {
            flow.collect { chunk ->
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

                    TranscriptChunk.Completed -> onFinished()
                }
            }
        } catch (e: AiException) {
            _uiState.value = _uiState.value.copy(failure = e.failure)
        } finally {
            onFinished()
        }
    }

    fun onDownloadModel() {
        val mode = _uiState.value.mode
        viewModelScope.launch {
            engine.downloadModel(mode).collect { state ->
                _uiState.value = _uiState.value.copy(downloadState = state)
                if (state is ModelDownloadState.Completed) {
                    refreshAvailability()
                    capabilityManager.refresh(
                        if (mode == TranscriptionMode.ADVANCED) {
                            AiTask.ADVANCED_TRANSCRIPTION
                        } else {
                            AiTask.BASIC_TRANSCRIPTION
                        },
                    )
                }
            }
        }
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasTranscript) return
        viewModelScope.launch {
            val saved = historyRepository.save(
                HistoryItem(
                    type = HistoryType.TRANSCRIPTION,
                    title = titleOf(state.transcript),
                    inputPreview = previewOf(state.transcript),
                    output = state.transcript,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    metadata = state.mode.name,
                ),
            )
            _uiState.value = _uiState.value.copy(savedToHistory = saved != null)
        }
    }

    fun onClear() {
        job?.cancel()
        _uiState.value = _uiState.value.copy(
            finalText = "",
            partialText = "",
            failure = null,
            savedToHistory = false,
            isRecording = false,
            isTranscribingFile = false,
        )
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
        super.onCleared()
    }
}
