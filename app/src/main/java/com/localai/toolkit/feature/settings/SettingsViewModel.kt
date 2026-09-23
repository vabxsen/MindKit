package com.localai.toolkit.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot confirmations that should appear as a snackbar rather than as screen state. */
enum class SettingsEvent { HISTORY_CLEARED, DATA_CLEARED }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings(),
        )

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    /** Dynamic colour is an Android 12+ platform feature, not a preference we can fake. */
    val dynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setSaveHistory(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSaveHistory(enabled) }
    }

    fun setVerboseErrors(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVerboseErrors(enabled) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.deleteAll()
            _events.tryEmit(SettingsEvent.HISTORY_CLEARED)
        }
    }

    /**
     * Removes everything this app owns: stored results and preferences.
     *
     * Deliberately does not touch ML Kit or AICore model files. Those are installed and
     * managed by Google Play services for the whole device, so deleting them here would
     * reach outside this app's data and could break other apps.
     */
    fun clearAllLocalData() {
        viewModelScope.launch {
            historyRepository.deleteAll()
            settingsRepository.clear()
            _events.tryEmit(SettingsEvent.DATA_CLEARED)
        }
    }
}
