package com.localai.toolkit.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import com.localai.toolkit.domain.usecase.SettingsActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the user is in the first-run flow. */
enum class OnboardingStage { INTRO, CHECKING, RESULTS, ERROR }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val capabilityManager: DeviceAiCapabilityManager,
    private val settingsActions: SettingsActions,
) : ViewModel() {

    private val _stage = MutableStateFlow(OnboardingStage.INTRO)
    val stage: StateFlow<OnboardingStage> = _stage.asStateFlow()
    private val _isCompleting = MutableStateFlow(false)
    val isCompleting = _isCompleting.asStateFlow()
    private val _finishFailed = MutableStateFlow(false)
    val finishFailed = _finishFailed.asStateFlow()
    private var checkJob: Job? = null
    private var completed = false

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
        if (checkJob?.isActive == true || _isCompleting.value || completed) return
        _stage.value = OnboardingStage.CHECKING
        checkJob = viewModelScope.launch {
            try {
                capabilityManager.refresh(force = true)
                _stage.value = OnboardingStage.RESULTS
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _stage.value = OnboardingStage.ERROR
            }
        }
    }

    /** Marks onboarding done, both for "Get started" and for "Skip". */
    fun complete(onDone: () -> Unit) {
        if (_isCompleting.value || completed) return
        _isCompleting.value = true
        _finishFailed.value = false
        val write = settingsActions.setOnboardingCompleted(true)
        viewModelScope.launch {
            try {
                write.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _finishFailed.value = true
                _isCompleting.value = false
                return@launch
            }
            completed = true
            checkJob?.cancel()
            onDone()
        }
    }
}
