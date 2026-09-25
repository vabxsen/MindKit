package com.localai.toolkit.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val query: String = "",
    val selectedTypes: Set<HistoryType> = emptySet(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
) {
    /** True only when the store itself is empty, as opposed to filtered down to nothing. */
    val isEmptyStore: Boolean
        get() = !isLoading && !loadFailed && items.isEmpty() && query.isBlank() && selectedTypes.isEmpty()

    val isFilteredEmpty: Boolean
        get() = !isLoading && !loadFailed && items.isEmpty() && (query.isNotBlank() || selectedTypes.isNotEmpty())
}

enum class HistoryDeletionState { IDLE, RUNNING, FAILED }

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: HistoryRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedTypes = MutableStateFlow(emptySet<HistoryType>())
    private val refreshVersion = MutableStateFlow(0)
    private val _deletionState = MutableStateFlow(HistoryDeletionState.IDLE)
    val deletionState = _deletionState.asStateFlow()
    private var failedDeletion: DeleteRequest? = null

    /** Mirrors the text field immediately; the query used for searching is debounced. */
    val queryText: StateFlow<String> = query.asStateFlow()

    val uiState: StateFlow<HistoryUiState> =
        combine(
            // Debounced so typing does not re-run a LIKE query per keystroke.
            query.debounce(200),
            selectedTypes,
            refreshVersion,
        ) { text, types, _ -> text to types }
            .flatMapLatest { (text, types) ->
                repository.observe(query = text, types = types).map { items ->
                    HistoryUiState(
                        items = items,
                        query = text,
                        selectedTypes = types,
                        isLoading = false,
                    )
                }.onStart {
                    emit(HistoryUiState(query = text, selectedTypes = types))
                }.catch {
                    emit(HistoryUiState(query = text, selectedTypes = types, isLoading = false, loadFailed = true))
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

    fun refresh() { refreshVersion.value++ }

    fun onDelete(id: Long) = delete(DeleteRequest.One(id))

    fun onDeleteAll() = delete(DeleteRequest.All)

    fun retryDeletion() { failedDeletion?.let(::delete) }

    private fun delete(request: DeleteRequest) {
        if (_deletionState.value == HistoryDeletionState.RUNNING) return
        _deletionState.value = HistoryDeletionState.RUNNING
        viewModelScope.launch {
            try {
                when (request) {
                    is DeleteRequest.One -> repository.delete(request.id)
                    DeleteRequest.All -> repository.deleteAll()
                }
                failedDeletion = null
                _deletionState.value = HistoryDeletionState.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedDeletion = request
                _deletionState.value = HistoryDeletionState.FAILED
            }
        }
    }

    private sealed interface DeleteRequest {
        data class One(val id: Long) : DeleteRequest
        data object All : DeleteRequest
    }
}
