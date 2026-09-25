package com.localai.toolkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the root composable needs before it can draw anything. */
sealed interface AppUiState {
    /** Settings have not been read yet; keep the splash window up rather than flashing. */
    data object Loading : AppUiState

    data class Ready(val settings: AppSettings) : AppUiState
    data class Failed(val previousSettings: AppSettings? = null) : AppUiState
}

/**
 * Holds the two decisions the root has to make: which theme to apply and whether to start
 * at onboarding or Home.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppUiState>(AppUiState.Loading)
    val uiState = _uiState.asStateFlow()
    private var lastKnown: AppSettings? = null
    private var readJob: Job? = null

    init { readSettings() }

    fun retry() {
        if (_uiState.value is AppUiState.Failed) readSettings()
    }

    private fun readSettings() {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            try {
                settingsRepository.settings.collect { settings ->
                    currentCoroutineContext().ensureActive()
                    if (settings.storageReadFailed) {
                        _uiState.value = AppUiState.Failed(lastKnown)
                    } else {
                        lastKnown = settings
                        _uiState.value = AppUiState.Ready(settings)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                _uiState.value = AppUiState.Failed(lastKnown)
            }
        }
    }
}
