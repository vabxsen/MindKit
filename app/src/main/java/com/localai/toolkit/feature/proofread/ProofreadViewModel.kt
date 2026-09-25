package com.localai.toolkit.feature.proofread

import com.localai.toolkit.feature.common.HistorySaveController

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.ProofreadInputType
import com.localai.toolkit.ai.engine.ProofreadRequest
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

data class ProofreadUiState(
    val input: String = "",
    val corrected: String = "",
    val comparedOriginal: String = "",
    val inputType: ProofreadInputType = ProofreadInputType.KEYBOARD,
    val isProofreading: Boolean = false,
    val failure: AiFailure? = null,
    val savedToHistory: Boolean = false,
    /** Set when the model returned text identical to the input. */
    val noChangesSuggested: Boolean = false,
) {
    val canProofread: Boolean get() = input.isNotBlank() && !isProofreading
    val hasResult: Boolean get() = corrected.isNotBlank() && !noChangesSuggested
}

@HiltViewModel
class ProofreadViewModel @Inject constructor(
    private val engine: AiEngine,
    private val historyRepository: HistoryRepository,
    private val toolHandoff: ToolHandoff,
    capabilityManager: DeviceAiCapabilityManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val gate =
        GenAiFeatureGate(AiTask.PROOFREAD, capabilityManager, engine, viewModelScope)

    val capability: StateFlow<AiCapability> = gate.capability
    val downloadState = gate.downloadState

    private val _uiState = MutableStateFlow(ProofreadUiState())
    val uiState: StateFlow<ProofreadUiState> = _uiState.asStateFlow()

    private val historySave = HistorySaveController(historyRepository, viewModelScope)
    val saveFeedback = historySave.feedback

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var job: Job? = null

    init {
        toolHandoff.consume(ToolId.PROOFREAD)?.text?.let { text ->
            _uiState.value = _uiState.value.copy(input = text)
        }
    }

    fun onInputChange(value: String) {
        if (_uiState.value.input == value) return
        job?.cancel()
        _uiState.value = _uiState.value.copy(
            input = value,
            corrected = if (_uiState.value.noChangesSuggested) "" else _uiState.value.corrected,
            isProofreading = false,
            failure = null,
            noChangesSuggested = false,
        )
    }

    fun onInputTypeChange(type: ProofreadInputType) {
        if (_uiState.value.inputType == type) return
        job?.cancel()
        _uiState.value = _uiState.value.copy(inputType = type, isProofreading = false, failure = null)
    }

    fun onProofread() {
        val state = _uiState.value
        if (!state.canProofread) return

        job?.cancel()
        _uiState.value = state.copy(
            isProofreading = true,
            corrected = "",
            failure = null,
            savedToHistory = false,
            noChangesSuggested = false,
        )

        job = viewModelScope.launch {
            try {
                val result = engine.proofread(
                    ProofreadRequest(text = state.input, inputType = state.inputType),
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // A model that returns the input unchanged has found nothing to fix; that
                // is a useful answer, not an empty result.
                val unchanged = result.isBlank() || result.trim() == state.input.trim()
                _uiState.value = _uiState.value.copy(
                    isProofreading = false,
                    corrected = result,
                    comparedOriginal = state.input,
                    noChangesSuggested = unchanged,
                )
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(isProofreading = false, failure = e.failure)
            }
        }
    }

    /** Replaces the input with the corrected version, on an explicit tap only. */
    fun onApply() {
        val state = _uiState.value
        if (!state.hasResult) return
        _uiState.value = state.copy(input = state.corrected)
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasResult || state.isProofreading || state.savedToHistory) return
        historySave.save(
            item = HistoryItem(
                type = HistoryType.PROOFREAD,
                title = titleOf(state.comparedOriginal),
                inputPreview = previewOf(state.comparedOriginal),
                output = state.corrected,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
            isCurrent = { _uiState.value.corrected == state.corrected && _uiState.value.comparedOriginal == state.comparedOriginal && !_uiState.value.isProofreading },
        ) { saved ->
            _uiState.value = _uiState.value.copy(savedToHistory = saved)
        }
    }

    fun onClear() {
        job?.cancel()
        _uiState.value = ProofreadUiState(inputType = _uiState.value.inputType)
    }

    fun sendTo(target: ToolId) {
        val text = _uiState.value.corrected
        if (text.isBlank()) return
        toolHandoff.send(HandoffPayload(target = target, text = text))
    }

    fun onDownloadModel() = gate.download()

    fun onRetryCapabilityCheck() = gate.refresh()

    override fun onCleared() {
        job?.cancel()
        gate.release()
    }
}
