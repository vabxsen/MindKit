package com.localai.toolkit.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HistoryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: HistoryRepository,
) : ViewModel() {

    // Read from SavedStateHandle rather than passed in, so the row survives process death.
    private val itemId: Long = savedStateHandle.get<Long>(Destination.HISTORY_ID_ARG) ?: -1L

    val item: StateFlow<HistoryItem?> = repository.observeById(itemId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
}
