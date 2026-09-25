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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.catch

data class HistoryDetailUiState(
    val item: HistoryItem? = null,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: HistoryRepository,
) : ViewModel() {

    // Read from SavedStateHandle rather than passed in, so the row survives process death.
    private val itemId: Long = savedStateHandle.get<Long>(Destination.HISTORY_ID_ARG) ?: -1L

    private val refreshVersion = MutableStateFlow(0)
    val uiState: StateFlow<HistoryDetailUiState> = refreshVersion.flatMapLatest {
        repository.observeById(itemId)
            .map { HistoryDetailUiState(item = it, isLoading = false) }
            .onStart { emit(HistoryDetailUiState()) }
            .catch { emit(HistoryDetailUiState(isLoading = false, loadFailed = true)) }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryDetailUiState(),
        )

    fun refresh() { refreshVersion.value++ }
}
