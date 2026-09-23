package com.localai.toolkit.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import com.localai.toolkit.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the user is in the first-run flow. */
enum class OnboardingStage { INTRO, CHECKING, RESULTS }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val capabilityManager: DeviceAiCapabilityManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _stage = MutableStateFlow(OnboardingStage.INTRO)
    val stage: StateFlow<OnboardingStage> = _stage.asStateFlow()

    val snapshot: StateFlow<DeviceAiSnapshot> = capabilityManager.snapshot
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DeviceAiSnapshot(),
        )

    /**
     * Runs the capability check the user explicitly asked for.
     *
     * Nothing is probed before this point: a first run should not reach for AICore until
     * the user taps the button.
     */
    fun checkDevice() {
        _stage.value = OnboardingStage.CHECKING
        viewModelScope.launch {
            capabilityManager.refresh(force = true)
            _stage.value = OnboardingStage.RESULTS
        }
    }

    /** Marks onboarding done, both for "Get started" and for "Skip". */
    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted(true)
            onDone()
        }
    }
}
