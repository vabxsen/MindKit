package com.localai.toolkit.feature.ask

import com.localai.toolkit.feature.common.HistorySaveController

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.ai.engine.AiEngine
import com.localai.toolkit.ai.engine.AskRequest
import com.localai.toolkit.ai.engine.AskRole
import com.localai.toolkit.ai.engine.AskTurn
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

/** One entry in the conversation. */
data class AskMessage(
    val id: Long,
    val role: AskRole,
    val text: String,
    /** True while the model is still producing this message. */
    val isStreaming: Boolean = false,
    val failure: AiFailure? = null,
)

data class AskUiState(
    val input: String = "",
    val messages: List<AskMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val savedMessageIds: Set<Long> = emptySet(),
) {
    val canSend: Boolean get() = input.isNotBlank() && !isGenerating
    val isEmpty: Boolean get() = messages.isEmpty()
}

@HiltViewModel
class AskViewModel @Inject constructor(
    private val engine: AiEngine,
    private val historyRepository: HistoryRepository,
    private val toolHandoff: ToolHandoff,
    capabilityManager: DeviceAiCapabilityManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val gate = GenAiFeatureGate(AiTask.ASK, capabilityManager, engine, viewModelScope)

    val capability: StateFlow<AiCapability> = gate.capability
    val downloadState = gate.downloadState

    private val _uiState = MutableStateFlow(AskUiState())
    val uiState: StateFlow<AskUiState> = _uiState.asStateFlow()

    private val historySave = HistorySaveController(historyRepository, viewModelScope)
    val saveFeedback = historySave.feedback

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var generationJob: Job? = null
    private var nextMessageId = 0L
    private var generation = 0L

    init {
        // Text sent here from another tool or a share becomes the first prompt, ready to
        // send rather than sent automatically.
        toolHandoff.consume(ToolId.ASK)?.text?.let { text ->
            _uiState.value = _uiState.value.copy(input = text)
        }
    }

    fun onInputChange(value: String) {
        _uiState.value = _uiState.value.copy(input = value)
    }

    fun onSend() {
        val state = _uiState.value
        if (!state.canSend) return

        val prompt = state.input.trim()
        // The conversation so far, captured before the new turn is appended.
        val history = state.messages
            .filter { it.failure == null && it.text.isNotBlank() }
            .map { AskTurn(it.role, it.text) }

        val userMessage = AskMessage(nextMessageId++, AskRole.USER, prompt)
        val modelMessage = AskMessage(nextMessageId++, AskRole.MODEL, "", isStreaming = true)

        _uiState.value = state.copy(
            input = "",
            messages = state.messages + userMessage + modelMessage,
            isGenerating = true,
        )

        generate(modelMessage.id, AskRequest(prompt = prompt, history = history))
    }

    private fun generate(messageId: Long, request: AskRequest) {
        val activeGeneration = ++generation
        generationJob = viewModelScope.launch {
            try {
                engine.generateText(request)
                    .collect { chunk ->
                        if (generation != activeGeneration) return@collect
                        updateMessage(messageId) {
                            it.copy(text = chunk.text, isStreaming = !chunk.isFinal)
                        }
                    }
                if (generation == activeGeneration) updateMessage(messageId) { it.copy(isStreaming = false) }
            } catch (e: AiException) {
                if (generation == activeGeneration) updateMessage(messageId) {
                    it.copy(isStreaming = false, failure = e.failure)
                }
            } finally {
                if (generation == activeGeneration) {
                    _uiState.value = _uiState.value.copy(isGenerating = false)
                }
            }
        }
    }

    /**
     * Stops generation at the user's request.
     *
     * Whatever the model produced so far is kept rather than discarded: a partial answer
     * the user chose to stop is still useful to them.
     */
    fun onStop() {
        ++generation
        generationJob?.cancel()
        generationJob = null
        _uiState.value = _uiState.value.copy(
            isGenerating = false,
            messages = _uiState.value.messages.map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
            },
        )
    }

    /** Regenerates the selected answer using its original context, preserving later turns and the draft. */
    fun onRetry(messageId: Long) {
        val state = _uiState.value
        if (state.isGenerating) return
        val index = state.messages.indexOfFirst { it.id == messageId && it.role == AskRole.MODEL }
        if (index < 0) return
        val userIndex = state.messages.take(index).indexOfLast { it.role == AskRole.USER }
        if (userIndex < 0) return
        val request = AskRequest(
            prompt = state.messages[userIndex].text,
            history = state.messages.take(userIndex)
                .filter { it.failure == null && it.text.isNotBlank() }
                .map { AskTurn(it.role, it.text) },
        )
        _uiState.value = state.copy(
            messages = state.messages.map {
                if (it.id == messageId) it.copy(text = "", isStreaming = true, failure = null) else it
            },
            isGenerating = true,
            savedMessageIds = state.savedMessageIds - messageId,
        )
        generate(messageId, request)
    }

    fun onNewConversation() {
        ++generation
        generationJob?.cancel()
        generationJob = null
        _uiState.value = AskUiState()
    }

    fun onSave(messageId: Long) {
        val state = _uiState.value
        val message = state.messages.firstOrNull { it.id == messageId } ?: return
        if (message.text.isBlank() || message.isStreaming || message.role != AskRole.MODEL || messageId in state.savedMessageIds) return
        val prompt = state.messages
            .lastOrNull { it.role == AskRole.USER && it.id < messageId }
            ?.text
            .orEmpty()

        historySave.save(
            item = HistoryItem(
                type = HistoryType.ASK,
                title = titleOf(prompt.ifBlank { message.text }),
                inputPreview = previewOf(prompt),
                output = message.text,
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
            isCurrent = { _uiState.value.messages.any { it == message } },
            slot = messageId,
        ) { saved ->
            val ids = _uiState.value.savedMessageIds
            _uiState.value = _uiState.value.copy(savedMessageIds = if (saved) ids + messageId else ids - messageId)
        }
    }

    fun onDownloadModel() = gate.download()

    fun onRetryCapabilityCheck() = gate.refresh()

    private fun updateMessage(id: Long, transform: (AskMessage) -> AskMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map { if (it.id == id) transform(it) else it },
        )
    }

    override fun onCleared() {
        // Inference stops and the AICore session is released when the screen goes away,
        // rather than being left open for a backgrounded app.
        generationJob?.cancel()
        gate.release()
    }
}
