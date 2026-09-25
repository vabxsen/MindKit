package com.localai.toolkit.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.domain.model.AppSettings
import com.localai.toolkit.domain.model.ThemeMode
import com.localai.toolkit.domain.repository.SettingsRepository
import com.localai.toolkit.domain.usecase.SettingsActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot confirmations that should appear as a snackbar rather than as screen state. */
enum class SettingsEvent { HISTORY_CLEARED, DATA_CLEARED, SAVE_FAILED, HISTORY_CLEAR_FAILED, DATA_CLEAR_FAILED }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val settingsActions: SettingsActions,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings(),
        )

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _isClearing = MutableStateFlow(false)
    val isClearing = _isClearing.asStateFlow()

    /** Dynamic colour is an Android 12+ platform feature, not a preference we can fake. */
    val dynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun setThemeMode(mode: ThemeMode) {
        updatePreference { settingsActions.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        if (dynamicColorSupported) updatePreference { settingsActions.setDynamicColor(enabled) }
    }

    fun setSaveHistory(enabled: Boolean) {
        updatePreference { settingsActions.setSaveHistory(enabled) }
    }

    fun setVerboseErrors(enabled: Boolean) {
        updatePreference { settingsActions.setVerboseErrors(enabled) }
    }

    private fun updatePreference(submit: () -> Deferred<Unit>) {
        if (_isClearing.value) return
        val write = submit()
        viewModelScope.launch {
            try {
                write.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.send(SettingsEvent.SAVE_FAILED)
            }
        }
    }

    fun clearHistory() = clearData(resetPreferences = false)

    /**
     * Removes everything this app owns: stored results and preferences.
     *
     * Deliberately does not touch ML Kit or AICore model files. Those are installed and
     * managed by Google Play services for the whole device, so deleting them here would
     * reach outside this app's data and could break other apps.
     */
    fun clearAllLocalData() = clearData(resetPreferences = true)

    private fun clearData(resetPreferences: Boolean) {
        if (_isClearing.value) return
        _isClearing.value = true
        val clear = if (resetPreferences) settingsActions.clearAllLocalData() else settingsActions.clearHistory()
        viewModelScope.launch {
            try {
                clear.await()
                _events.send(if (resetPreferences) SettingsEvent.DATA_CLEARED else SettingsEvent.HISTORY_CLEARED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.send(if (resetPreferences) SettingsEvent.DATA_CLEAR_FAILED else SettingsEvent.HISTORY_CLEAR_FAILED)
            } finally {
                _isClearing.value = false
            }
        }
    }
}
