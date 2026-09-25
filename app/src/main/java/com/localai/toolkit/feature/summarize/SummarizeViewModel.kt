package com.localai.toolkit.feature.summarize

import com.localai.toolkit.feature.common.HistorySaveController

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.SummarizeRequest
import com.localai.toolkit.ai.engine.SummaryInputType
import com.localai.toolkit.ai.engine.SummaryLength
import com.localai.toolkit.core.navigation.HandoffPayload
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.core.util.titleOf
import com.localai.toolkit.domain.model.AiCapability
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.AiTask
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.feature.common.GenAiFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SummarizeUiState(
    val input: String = "",
    val summary: String = "",
    val summarizedInput: String = "",
    val length: SummaryLength = SummaryLength.MEDIUM,
    val inputType: SummaryInputType = SummaryInputType.ARTICLE,
    val isSummarizing: Boolean = false,
    val failure: AiFailure? = null,
    val savedToHistory: Boolean = false,
) {
    val characterCount: Int get() = input.length
    val canSummarize: Boolean get() = input.isNotBlank() && !isSummarizing
    val hasResult: Boolean get() = summary.isNotBlank()
}

@HiltViewModel
class SummarizeViewModel @Inject constructor(
    private val engine: AiEngine,
    private val historyRepository: HistoryRepository,
    private val toolHandoff: ToolHandoff,
    capabilityManager: DeviceAiCapabilityManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val gate =
        GenAiFeatureGate(AiTask.SUMMARIZE, capabilityManager, engine, viewModelScope)

    val capability: StateFlow<AiCapability> = gate.capability
    val downloadState = gate.downloadState

    private val _uiState = MutableStateFlow(SummarizeUiState())
    val uiState: StateFlow<SummarizeUiState> = _uiState.asStateFlow()

    private val historySave = HistorySaveController(historyRepository, viewModelScope)
    val saveFeedback = historySave.feedback

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var job: Job? = null

    init {
        toolHandoff.consume(ToolId.SUMMARIZE)?.text?.let { text ->
            _uiState.value = _uiState.value.copy(input = text)
        }
    }

    fun onInputChange(value: String) {
        if (_uiState.value.input == value) return
        job?.cancel()
        _uiState.value = _uiState.value.copy(input = value, isSummarizing = false, failure = null)
    }

    fun onLengthChange(length: SummaryLength) {
        if (_uiState.value.length == length) return
        job?.cancel()
        _uiState.value = _uiState.value.copy(length = length, isSummarizing = false, failure = null)
    }

    fun onInputTypeChange(type: SummaryInputType) {
        if (_uiState.value.inputType == type) return
        job?.cancel()
        _uiState.value = _uiState.value.copy(inputType = type, isSummarizing = false, failure = null)
    }

    fun onSummarize() {
        val state = _uiState.value
        if (!state.canSummarize) return

        job?.cancel()
        _uiState.value = state.copy(isSummarizing = true, summary = "", failure = null, savedToHistory = false)

        job = viewModelScope.launch {
            try {
                val summary = engine.summarize(
                    SummarizeRequest(
                        text = state.input,
                        length = state.length,
                        inputType = state.inputType,
                    ),
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(isSummarizing = false, summary = summary, summarizedInput = state.input)
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(isSummarizing = false, failure = e.failure)
            }
        }
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasResult || state.isSummarizing || state.savedToHistory) return
        historySave.save(
            item = HistoryItem(
                type = HistoryType.SUMMARY,
                title = titleOf(state.summarizedInput),
                inputPreview = previewOf(state.summarizedInput),
                output = state.summary,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
            isCurrent = {
                _uiState.value.summary == state.summary &&
                    _uiState.value.summarizedInput == state.summarizedInput && !_uiState.value.isSummarizing
            },
        ) { saved ->
            _uiState.value = _uiState.value.copy(savedToHistory = saved)
        }
    }

    fun onClear() {
        job?.cancel()
        _uiState.value = SummarizeUiState(
            length = _uiState.value.length,
            inputType = _uiState.value.inputType,
        )
    }

    fun sendTo(target: ToolId) {
        val summary = _uiState.value.summary
        if (summary.isBlank()) return
        toolHandoff.send(HandoffPayload(target = target, text = summary))
    }

    fun onDownloadModel() = gate.download()

    fun onRetryCapabilityCheck() = gate.refresh()

    override fun onCleared() {
        job?.cancel()
        gate.release()
    }
}
