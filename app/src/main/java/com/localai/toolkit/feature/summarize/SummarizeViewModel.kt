package com.localai.toolkit.feature.summarize

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
        _uiState.value = _uiState.value.copy(input = value, savedToHistory = false)
    }

    fun onLengthChange(length: SummaryLength) {
        _uiState.value = _uiState.value.copy(length = length)
    }

    fun onInputTypeChange(type: SummaryInputType) {
        _uiState.value = _uiState.value.copy(inputType = type)
    }

    fun onSummarize() {
        val state = _uiState.value
        if (!state.canSummarize) return

        job?.cancel()
        _uiState.value = state.copy(isSummarizing = true, failure = null, savedToHistory = false)

        job = viewModelScope.launch {
            try {
                val summary = engine.summarize(
                    SummarizeRequest(
                        text = state.input,
                        length = state.length,
                        inputType = state.inputType,
                    ),
                )
                _uiState.value = _uiState.value.copy(isSummarizing = false, summary = summary)
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(isSummarizing = false, failure = e.failure)
            }
        }
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasResult) return
        viewModelScope.launch {
            val saved = historyRepository.save(
                HistoryItem(
                    type = HistoryType.SUMMARY,
                    title = titleOf(state.input),
                    inputPreview = previewOf(state.input),
                    output = state.summary,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            _uiState.value = _uiState.value.copy(savedToHistory = saved != null)
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
        super.onCleared()
    }
}
