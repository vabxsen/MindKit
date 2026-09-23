package com.localai.toolkit.feature.capability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.ai.capability.DeviceAiCapabilityManager
import com.localai.toolkit.domain.model.DeviceAiSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DeviceAiViewModel @Inject constructor(
    private val capabilityManager: DeviceAiCapabilityManager,
) : ViewModel() {

    val snapshot: StateFlow<DeviceAiSnapshot> = capabilityManager.snapshot
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DeviceAiSnapshot(),
        )

    init {
        viewModelScope.launch { capabilityManager.refresh(force = false) }
    }

    fun refresh() {
        viewModelScope.launch { capabilityManager.refresh(force = true) }
    }
}
