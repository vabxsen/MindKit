package com.localai.toolkit.feature.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.mlkit.TranslationEngine
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.core.navigation.ToolHandoff
import com.localai.toolkit.core.util.previewOf
import com.localai.toolkit.core.util.titleOf
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.model.ToolId
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TranslateUiState(
    val input: String = "",
    val output: String = "",
    val languages: List<TranslationLanguage> = emptyList(),
    val downloadedLanguages: Set<String> = emptySet(),
    val sourceCode: String = DEFAULT_SOURCE,
    val targetCode: String = DEFAULT_TARGET,
    /** Non-null when the source language was guessed rather than chosen. */
    val detectedSourceCode: String? = null,
    val isTranslating: Boolean = false,
    /** Language code currently downloading, if any. */
    val downloadingCode: String? = null,
    val failure: AiFailure? = null,
    val savedToHistory: Boolean = false,
    val wifiOnlyDownloads: Boolean = false,
) {
    val hasResult: Boolean get() = output.isNotBlank()

    val sameLanguageSelected: Boolean get() = sourceCode == targetCode

    /** Codes that must be present before this pair can translate offline. */
    val missingModels: List<String>
        get() = listOf(sourceCode, targetCode).distinct()
            .filterNot { it in downloadedLanguages }

    val canTranslate: Boolean
        get() = input.isNotBlank() && !isTranslating && !sameLanguageSelected

    fun displayNameOf(code: String): String =
        languages.firstOrNull { it.code == code }?.displayName ?: code.uppercase(Locale.ROOT)

    companion object {
        const val DEFAULT_SOURCE = "en"
        const val DEFAULT_TARGET = "es"
    }
}

@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val translationEngine: TranslationEngine,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val toolHandoff: ToolHandoff,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslateUiState())
    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var translateJob: Job? = null
    private var detectJob: Job? = null

    init {
        viewModelScope.launch {
            val stored = settingsRepository.settings.first()
            _uiState.value = _uiState.value.copy(
                languages = translationEngine.supportedLanguages(),
                sourceCode = stored.lastTranslateSource ?: TranslateUiState.DEFAULT_SOURCE,
                targetCode = stored.lastTranslateTarget ?: defaultTargetFor(
                    stored.lastTranslateSource ?: TranslateUiState.DEFAULT_SOURCE,
                ),
            )
            refreshDownloadedLanguages()

            // Text handed over from Extract Text, a share, or another tool.
            toolHandoff.consume(ToolId.TRANSLATE)?.text?.let(::onInputChange)
        }
    }

    fun onInputChange(value: String) {
        _uiState.value = _uiState.value.copy(input = value, savedToHistory = false)
        scheduleDetection(value)
    }

    fun onSourceChange(code: String) {
        _uiState.value = _uiState.value.copy(sourceCode = code, detectedSourceCode = null)
        persistLanguages()
    }

    fun onTargetChange(code: String) {
        _uiState.value = _uiState.value.copy(targetCode = code)
        persistLanguages()
    }

    fun onSwapLanguages() {
        val current = _uiState.value
        _uiState.value = current.copy(
            sourceCode = current.targetCode,
            targetCode = current.sourceCode,
            // Swapping makes the previous guess meaningless.
            detectedSourceCode = null,
            // A swap usually means the user wants to translate the result back.
            input = current.output.ifBlank { current.input },
            output = "",
        )
        persistLanguages()
    }

    fun onWifiOnlyChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(wifiOnlyDownloads = enabled)
    }

    fun onTranslate() {
        val state = _uiState.value
        if (!state.canTranslate) return

        translateJob?.cancel()
        _uiState.value = state.copy(isTranslating = true, failure = null, savedToHistory = false)

        translateJob = viewModelScope.launch {
            try {
                val result = translationEngine.translate(
                    text = state.input,
                    source = state.sourceCode,
                    target = state.targetCode,
                )
                _uiState.value = _uiState.value.copy(isTranslating = false, output = result)
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(isTranslating = false, failure = e.failure)
            }
        }
    }

    /**
     * Downloads one language model.
     *
     * Always user-initiated: translation never downloads silently, because that would
     * make a network request the user did not ask for.
     */
    fun onDownloadLanguage(code: String) {
        if (_uiState.value.downloadingCode != null) return
        _uiState.value = _uiState.value.copy(downloadingCode = code, failure = null)

        viewModelScope.launch {
            try {
                translationEngine.downloadLanguage(code, _uiState.value.wifiOnlyDownloads)
                refreshDownloadedLanguages()
                _uiState.value = _uiState.value.copy(downloadingCode = null)
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(
                    downloadingCode = null,
                    failure = e.failure,
                )
            }
        }
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasResult) return
        viewModelScope.launch {
            val saved = historyRepository.save(
                HistoryItem(
                    type = HistoryType.TRANSLATION,
                    title = titleOf(state.input),
                    inputPreview = previewOf(state.input),
                    output = state.output,
                    createdAtEpochMillis = System.currentTimeMillis(),
                    metadata = "${state.sourceCode} to ${state.targetCode}",
                ),
            )
            _uiState.value = _uiState.value.copy(savedToHistory = saved != null)
        }
    }

    fun onClear() {
        translateJob?.cancel()
        _uiState.value = _uiState.value.copy(
            input = "",
            output = "",
            failure = null,
            detectedSourceCode = null,
            savedToHistory = false,
        )
    }

    private suspend fun refreshDownloadedLanguages() {
        try {
            _uiState.value = _uiState.value.copy(
                downloadedLanguages = translationEngine.downloadedLanguages(),
            )
        } catch (e: AiException) {
            _uiState.value = _uiState.value.copy(failure = e.failure)
        }
    }

    /**
     * Guesses the source language once the user has typed enough to be worth guessing.
     *
     * Short fragments identify badly, so below the threshold nothing is claimed at all.
     */
    private fun scheduleDetection(text: String) {
        detectJob?.cancel()
        if (text.length < MIN_CHARS_FOR_DETECTION) {
            _uiState.value = _uiState.value.copy(detectedSourceCode = null)
            return
        }
        detectJob = viewModelScope.launch {
            val detected = translationEngine.detectLanguage(text) ?: return@launch
            if (detected == _uiState.value.sourceCode) return@launch
            _uiState.value = _uiState.value.copy(detectedSourceCode = detected)
        }
    }

    /** Applies a detected language as the actual source. Always an explicit tap. */
    fun onAcceptDetectedLanguage() {
        val detected = _uiState.value.detectedSourceCode ?: return
        _uiState.value = _uiState.value.copy(sourceCode = detected, detectedSourceCode = null)
        persistLanguages()
    }

    private fun persistLanguages() {
        val state = _uiState.value
        viewModelScope.launch {
            settingsRepository.setTranslateLanguages(state.sourceCode, state.targetCode)
        }
    }

    private fun defaultTargetFor(source: String): String =
        if (source == TranslateUiState.DEFAULT_TARGET) {
            TranslateUiState.DEFAULT_SOURCE
        } else {
            TranslateUiState.DEFAULT_TARGET
        }

    override fun onCleared() {
        translateJob?.cancel()
        detectJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MIN_CHARS_FOR_DETECTION = 12
    }
}
