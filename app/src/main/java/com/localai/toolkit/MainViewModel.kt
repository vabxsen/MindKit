package com.localai.toolkit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the root composable needs before it can draw anything. */
sealed interface AppUiState {
    /** Settings have not been read yet; keep the splash window up rather than flashing. */
    data object Loading : AppUiState

    data class Ready(val settings: AppSettings) : AppUiState
}

/**
 * Holds the two decisions the root has to make: which theme to apply and whether to start
 * at onboarding or Home.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<AppUiState> = settingsRepository.settings
        .map<AppSettings, AppUiState> { AppUiState.Ready(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppUiState.Loading,
        )
}
