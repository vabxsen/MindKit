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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class DeviceAiViewModel @Inject constructor(
    private val capabilityManager: DeviceAiCapabilityManager,
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private val _refreshFailed = MutableStateFlow(false)
    val refreshFailed = _refreshFailed.asStateFlow()

    val snapshot: StateFlow<DeviceAiSnapshot> = capabilityManager.snapshot
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DeviceAiSnapshot(),
        )

    init {
        refresh(force = false)
    }

    fun refresh() {
        refresh(force = true)
    }

    private fun refresh(force: Boolean) {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        _refreshFailed.value = false
        viewModelScope.launch {
            try {
                capabilityManager.refresh(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _refreshFailed.value = true
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
