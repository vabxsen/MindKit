package com.localai.toolkit.feature.rewrite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.RewriteRequest
import com.localai.toolkit.ai.engine.RewriteStyle
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

data class RewriteUiState(
    val input: String = "",
    val rewritten: String = "",
    /**
     * The text as it was when the rewrite ran.
     *
     * Kept so the comparison view stays meaningful even after the user edits the input,
     * and so accepting a rewrite is reversible in the user's head: their original is
     * still on screen rather than silently overwritten.
     */
    val comparedOriginal: String = "",
    val style: RewriteStyle = RewriteStyle.REPHRASE,
    val isRewriting: Boolean = false,
    val showOriginal: Boolean = false,
    val failure: AiFailure? = null,
    val savedToHistory: Boolean = false,
) {
    val canRewrite: Boolean get() = input.isNotBlank() && !isRewriting
    val hasResult: Boolean get() = rewritten.isNotBlank()
}

@HiltViewModel
class RewriteViewModel @Inject constructor(
    private val engine: AiEngine,
    private val historyRepository: HistoryRepository,
    private val toolHandoff: ToolHandoff,
    capabilityManager: DeviceAiCapabilityManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val gate = GenAiFeatureGate(AiTask.REWRITE, capabilityManager, engine, viewModelScope)

    val capability: StateFlow<AiCapability> = gate.capability
    val downloadState = gate.downloadState

    private val _uiState = MutableStateFlow(RewriteUiState())
    val uiState: StateFlow<RewriteUiState> = _uiState.asStateFlow()

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var job: Job? = null

    init {
        toolHandoff.consume(ToolId.REWRITE)?.text?.let { text ->
            _uiState.value = _uiState.value.copy(input = text)
        }
    }

    fun onInputChange(value: String) {
        _uiState.value = _uiState.value.copy(input = value, savedToHistory = false)
    }

    fun onStyleChange(style: RewriteStyle) {
        _uiState.value = _uiState.value.copy(style = style)
    }

    fun onToggleComparison() {
        _uiState.value = _uiState.value.copy(showOriginal = !_uiState.value.showOriginal)
    }

    fun onRewrite() {
        val state = _uiState.value
        if (!state.canRewrite) return

        job?.cancel()
        _uiState.value = state.copy(isRewriting = true, failure = null, savedToHistory = false)

        job = viewModelScope.launch {
            try {
                val result = engine.rewrite(
                    RewriteRequest(text = state.input, style = state.style),
                )
                _uiState.value = _uiState.value.copy(
                    isRewriting = false,
                    rewritten = result,
                    comparedOriginal = state.input,
                )
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(isRewriting = false, failure = e.failure)
            }
        }
    }

    /**
     * Replaces the input with the rewritten version.
     *
     * Only ever on an explicit tap: the original is never overwritten automatically.
     */
    fun onAccept() {
        val state = _uiState.value
        if (!state.hasResult) return
        _uiState.value = state.copy(input = state.rewritten)
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasResult) return
        viewModelScope.launch {
            val saved = historyRepository.save(
                HistoryItem(
                    type = HistoryType.REWRITE,
                    title = titleOf(state.comparedOriginal),
                    inputPreview = previewOf(state.comparedOriginal),
                    output = state.rewritten,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    metadata = state.style.name,
                ),
            )
            _uiState.value = _uiState.value.copy(savedToHistory = saved != null)
        }
    }

    fun onClear() {
        job?.cancel()
        _uiState.value = RewriteUiState(style = _uiState.value.style)
    }

    fun sendTo(target: ToolId) {
        val text = _uiState.value.rewritten
        if (text.isBlank()) return
        toolHandoff.send(HandoffPayload(target = target, text = text))
    }

    fun onDownloadModel() = gate.download()

    fun onRetryCapabilityCheck() = gate.refresh()

    override fun onCleared() {
        job?.cancel()
        gate.release()
        super.onCleared()
    }
}
