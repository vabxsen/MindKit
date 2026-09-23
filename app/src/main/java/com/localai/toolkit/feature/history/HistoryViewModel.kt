package com.localai.toolkit.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val query: String = "",
    val selectedTypes: Set<HistoryType> = emptySet(),
    val isLoading: Boolean = true,
) {
    /** True only when the store itself is empty, as opposed to filtered down to nothing. */
    val isEmptyStore: Boolean
        get() = !isLoading && items.isEmpty() && query.isBlank() && selectedTypes.isEmpty()

    val isFilteredEmpty: Boolean
        get() = !isLoading && items.isEmpty() && (query.isNotBlank() || selectedTypes.isNotEmpty())
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedTypes = MutableStateFlow(emptySet<HistoryType>())

    /** Mirrors the text field immediately; the query used for searching is debounced. */
    val queryText: StateFlow<String> = query.asStateFlow()

    val uiState: StateFlow<HistoryUiState> =
        combine(
            // Debounced so typing does not re-run a LIKE query per keystroke.
            query.debounce(200),
            selectedTypes,
        ) { text, types -> text to types }
            .flatMapLatest { (text, types) ->
                repository.observe(query = text, types = types).map { items ->
                    HistoryUiState(
                        items = items,
                        query = text,
                        selectedTypes = types,
                        isLoading = false,
                    )
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HistoryUiState(),
            )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onToggleType(type: HistoryType) {
        val current = selectedTypes.value
        selectedTypes.value = if (type in current) current - type else current + type
    }

    fun onClearFilters() {
        query.value = ""
        selectedTypes.value = emptySet()
    }

    fun onDelete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun onDeleteAll() {
        viewModelScope.launch { repository.deleteAll() }
    }
}
