package com.localai.toolkit.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.mlkit.TranslationEngine
import com.localai.toolkit.ai.mlkit.TranslationLanguage
import com.localai.toolkit.domain.model.AiException
import com.localai.toolkit.domain.model.AiFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelsUiState(
    val languages: List<TranslationLanguage> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val query: String = "",
    val busyCode: String? = null,
    val isLoading: Boolean = true,
    val failure: AiFailure? = null,
) {
    /** Downloaded languages float to the top; the rest stay alphabetical. */
    val visibleLanguages: List<TranslationLanguage>
        get() = languages
            .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<TranslationLanguage> { it.code in downloaded }
                    .thenBy { it.displayName },
            )
}

/**
 * Manages downloaded translation language packs.
 *
 * Only translation models appear here because they are the only models this app can
 * actually add and remove. Gemini Nano feature models belong to Android, so the screen
 * says so rather than offering a delete button that would not work.
 */
@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val translationEngine: TranslationEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    init {
        _uiState.value = _uiState.value.copy(languages = translationEngine.supportedLanguages())
        refresh()
    }

    fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }

    fun onDownload(code: String) {
        if (_uiState.value.busyCode != null || _uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(busyCode = code, failure = null)
        viewModelScope.launch {
            try {
                translationEngine.downloadLanguage(code)
                loadDownloaded()
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(failure = e.failure)
            } finally {
                _uiState.value = _uiState.value.copy(busyCode = null)
            }
        }
    }

    fun onDelete(code: String) {
        if (_uiState.value.busyCode != null || _uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(busyCode = code, failure = null)
        viewModelScope.launch {
            try {
                translationEngine.deleteLanguage(code)
                loadDownloaded()
            } catch (e: AiException) {
                _uiState.value = _uiState.value.copy(failure = e.failure)
            } finally {
                _uiState.value = _uiState.value.copy(busyCode = null)
            }
        }
    }

    fun refresh() {
        if (_uiState.value.busyCode != null || refreshJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(isLoading = true, failure = null)
        refreshJob = viewModelScope.launch { loadDownloaded() }
    }

    private suspend fun loadDownloaded() {
        try {
            _uiState.value = _uiState.value.copy(
                downloaded = translationEngine.downloadedLanguages(),
                isLoading = false,
                failure = null,
            )
        } catch (e: AiException) {
            _uiState.value = _uiState.value.copy(isLoading = false, failure = e.failure)
        }
    }
}
