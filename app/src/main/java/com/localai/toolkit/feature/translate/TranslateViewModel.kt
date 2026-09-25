package com.localai.toolkit.feature.translate

import com.localai.toolkit.feature.common.HistorySaveController

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
import com.localai.toolkit.domain.usecase.SettingsActions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val isRefreshingModels: Boolean = false,
    val modelFailure: AiFailure? = null,
    val failedDownloadCode: String? = null,
    val languagePreferencesFailed: Boolean = false,
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
    private val settingsActions: SettingsActions,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslateUiState())
    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()

    private val historySave = HistorySaveController(historyRepository, viewModelScope)
    val saveFeedback = historySave.feedback

    val verboseErrors: StateFlow<Boolean> = settingsRepository.settings
        .map { it.verboseErrors }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var translateJob: Job? = null
    private var detectJob: Job? = null
    private var modelRefreshJob: Job? = null
    private var languageLoadJob: Job? = null
    private var languageRevision = 0L

    init {
        _uiState.value = _uiState.value.copy(languages = translationEngine.supportedLanguages())
        // Consume before any suspended storage read, so a late handoff cannot replace typing.
        toolHandoff.consume(ToolId.TRANSLATE)?.text?.let(::onInputChange)
        loadLanguages()
        onRefreshModels()
    }

    fun onInputChange(value: String) {
        invalidateTranslation()
        _uiState.value = _uiState.value.copy(input = value)
        scheduleDetection(value)
    }

    fun onSourceChange(code: String) {
        detectJob?.cancel()
        invalidateTranslation()
        _uiState.value = _uiState.value.copy(sourceCode = code, detectedSourceCode = null)
        persistLanguages()
    }

    fun onTargetChange(code: String) {
        invalidateTranslation()
        _uiState.value = _uiState.value.copy(targetCode = code)
        persistLanguages()
    }

    fun onSwapLanguages() {
        val current = _uiState.value
        detectJob?.cancel()
        invalidateTranslation()
        _uiState.value = _uiState.value.copy(
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
        if (!state.canTranslate || state.isRefreshingModels || state.modelFailure != null || state.missingModels.isNotEmpty()) return

        // Running explicitly accepts the displayed pair, even if restoration is still pending.
        if (languageRevision == 0L) persistLanguages()
        translateJob?.cancel()
        _uiState.value = _uiState.value.copy(isTranslating = true, failure = null, savedToHistory = false, failedDownloadCode = null)

        translateJob = viewModelScope.launch {
            try {
                val result = translationEngine.translate(
                    text = state.input,
                    source = state.sourceCode,
                    target = state.targetCode,
                )
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(isTranslating = false, output = result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val failure = (e as? AiException)?.failure ?: AiFailure.Unknown(e.message)
                _uiState.value = _uiState.value.copy(isTranslating = false, failure = failure)
                if (failure is AiFailure.ModelNotDownloaded) onRefreshModels()
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
        _uiState.value = _uiState.value.copy(downloadingCode = code, failure = null, failedDownloadCode = null)

        viewModelScope.launch {
            try {
                translationEngine.downloadLanguage(code, _uiState.value.wifiOnlyDownloads)
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(downloadingCode = null)
                modelRefreshJob?.cancel()
                onRefreshModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(
                    downloadingCode = null,
                    failure = (e as? AiException)?.failure ?: AiFailure.Unknown(e.message),
                    failedDownloadCode = code,
                )
            }
        }
    }

    fun onRetryFailure() {
        val downloadCode = _uiState.value.failedDownloadCode
        if (downloadCode != null) onDownloadLanguage(downloadCode) else onTranslate()
    }

    fun onSave() {
        val state = _uiState.value
        if (!state.hasResult || state.isTranslating || state.savedToHistory) return
        historySave.save(
            item = HistoryItem(
                type = HistoryType.TRANSLATION,
                title = titleOf(state.input),
                inputPreview = previewOf(state.input),
                output = state.output,
                createdAtEpochMillis = System.currentTimeMillis(),
                metadata = "${state.sourceCode} to ${state.targetCode}",
            ),
            isCurrent = {
                _uiState.value.output == state.output && _uiState.value.input == state.input &&
                    _uiState.value.sourceCode == state.sourceCode && _uiState.value.targetCode == state.targetCode &&
                    !_uiState.value.isTranslating
            },
        ) { saved ->
            _uiState.value = _uiState.value.copy(savedToHistory = saved)
        }
    }

    fun onClear() {
        detectJob?.cancel()
        invalidateTranslation()
        _uiState.value = _uiState.value.copy(
            input = "",
            output = "",
            failure = null,
            detectedSourceCode = null,
            savedToHistory = false,
            failedDownloadCode = null,
        )
    }

    /** Recheck on return to this screen: packs may have changed in Models or Android. */
    fun onRefreshModels() {
        if (modelRefreshJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(isRefreshingModels = true, modelFailure = null)
        modelRefreshJob = viewModelScope.launch {
            try {
                // Read state after suspension so typing/selection during this check survives.
                val downloaded = translationEngine.downloadedLanguages()
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(
                    downloadedLanguages = downloaded,
                    isRefreshingModels = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _uiState.value = _uiState.value.copy(
                    isRefreshingModels = false,
                    modelFailure = (e as? AiException)?.failure ?: AiFailure.Unknown(e.message),
                )
            }
        }
    }

    /**
     * Guesses the source language once the user has typed enough to be worth guessing.
     *
     * Short fragments identify badly, so below the threshold nothing is claimed at all.
     */
    private fun scheduleDetection(text: String) {
        detectJob?.cancel()
        _uiState.value = _uiState.value.copy(detectedSourceCode = null)
        if (text.length < MIN_CHARS_FOR_DETECTION) {
            _uiState.value = _uiState.value.copy(detectedSourceCode = null)
            return
        }
        detectJob = viewModelScope.launch {
            val detected = translationEngine.detectLanguage(text) ?: return@launch
            currentCoroutineContext().ensureActive()
            if (detected == _uiState.value.sourceCode) return@launch
            _uiState.value = _uiState.value.copy(detectedSourceCode = detected)
        }
    }

    /** Applies a detected language as the actual source. Always an explicit tap. */
    fun onAcceptDetectedLanguage() {
        val detected = _uiState.value.detectedSourceCode ?: return
        onSourceChange(detected)
    }

    /** Results belong to the exact input and language pair that produced them. */
    private fun invalidateTranslation() {
        translateJob?.cancel()
        translateJob = null
        _uiState.value = _uiState.value.copy(
            isTranslating = false,
            output = "",
            failure = null,
            savedToHistory = false,
        )
    }

    private fun persistLanguages() {
        val state = _uiState.value
        val revision = ++languageRevision
        languageLoadJob?.cancel()
        _uiState.value = state.copy(languagePreferencesFailed = false)
        val write = settingsActions.setTranslateLanguages(state.sourceCode, state.targetCode)
        viewModelScope.launch {
            try {
                write.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (revision == languageRevision) {
                    _uiState.value = _uiState.value.copy(languagePreferencesFailed = true)
                }
            }
        }
    }

    fun onRetryLanguagePreferences() {
        if (!_uiState.value.languagePreferencesFailed) return
        if (languageRevision > 0) persistLanguages() else loadLanguages()
    }

    private fun loadLanguages() {
        if (languageLoadJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(languagePreferencesFailed = false)
        languageLoadJob = viewModelScope.launch {
            try {
                // The recovery fallback is not the user's saved language pair.
                val stored = settingsRepository.settings.first { !it.storageReadFailed }
                currentCoroutineContext().ensureActive()
                if (languageRevision != 0L) return@launch
                _uiState.value = _uiState.value.copy(
                    sourceCode = stored.lastTranslateSource ?: TranslateUiState.DEFAULT_SOURCE,
                    targetCode = stored.lastTranslateTarget ?: defaultTargetFor(
                        stored.lastTranslateSource ?: TranslateUiState.DEFAULT_SOURCE,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (languageRevision == 0L) {
                    _uiState.value = _uiState.value.copy(languagePreferencesFailed = true)
                }
            }
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
    }

    private companion object {
        const val MIN_CHARS_FOR_DETECTION = 12
    }
}
