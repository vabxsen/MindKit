package com.localai.toolkit.feature.history

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.core.navigation.Destination
import com.localai.toolkit.domain.model.*
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val rows = MemoryHistory()
    private fun item(text: String, type: HistoryType = HistoryType.OCR) = HistoryItem(
        type = type, title = text, inputPreview = text, output = text, createdAtEpochMillis = 1,
    )

    @Test fun `search type selection and Clear filters update the visible history`() = runTest {
        rows.save(item("Meeting notes"))
        rows.save(item("Meeting summary", HistoryType.SUMMARY))
        rows.save(item("Other item"))
        val vm = main.own(HistoryViewModel(rows))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceTimeBy(201)
        runCurrent()
        assertThat(vm.uiState.value.items).hasSize(3)
        vm.onQueryChange("Meeting")
        assertThat(vm.queryText.value).isEqualTo("Meeting")
        advanceTimeBy(201)
        runCurrent()
        assertThat(vm.uiState.value.items).hasSize(2)
        vm.onToggleType(HistoryType.SUMMARY)
        runCurrent()
        assertThat(vm.uiState.value.items.single().title).isEqualTo("Meeting summary")
        vm.onClearFilters()
        advanceTimeBy(201)
        runCurrent()
        assertThat(vm.uiState.value.items).hasSize(3)
        assertThat(vm.uiState.value.selectedTypes).isEmpty()
    }

    @Test fun `delete failure preserves rows and Retry repeats the intended deletion`() = runTest {
        val first = rows.save(item("First"))
        rows.save(item("Second"))
        var fail = true
        val repository = object : HistoryRepository by rows {
            override suspend fun delete(id: Long) {
                if (fail) error("storage")
                rows.delete(id)
            }
        }
        val vm = main.own(HistoryViewModel(repository))
        vm.onDelete(first)
        runCurrent()
        assertThat(vm.deletionState.value).isEqualTo(HistoryDeletionState.FAILED)
        assertThat(rows.items.value).hasSize(2)
        fail = false
        vm.retryDeletion()
        runCurrent()
        assertThat(rows.items.value.single().title).isEqualTo("Second")
        assertThat(vm.deletionState.value).isEqualTo(HistoryDeletionState.IDLE)
        vm.onDeleteAll()
        runCurrent()
        assertThat(rows.items.value).isEmpty()
    }

    @Test fun `a failed history query renders an error and can be refreshed`() = runTest {
        rows.save(item("Stored"))
        var fail = true
        val repository = object : HistoryRepository by rows {
            override fun observe(query: String, types: Set<HistoryType>) = flow {
                if (fail) error("database")
                emit(rows.items.value)
            }
        }
        val vm = main.own(HistoryViewModel(repository))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceTimeBy(201)
        runCurrent()
        assertThat(vm.uiState.value.loadFailed).isTrue()
        assertThat(vm.uiState.value.isEmptyStore).isFalse()
        fail = false
        vm.refresh()
        runCurrent()
        assertThat(vm.uiState.value.loadFailed).isFalse()
        assertThat(vm.uiState.value.items).hasSize(1)
    }

    @Test fun `history detail distinguishes loading from missing and recovers from a query failure`() = runTest {
        val pending = CompletableDeferred<Unit>()
        var fail = true
        val repository = object : HistoryRepository by rows {
            override fun observeById(id: Long) = flow<HistoryItem?> {
                pending.await()
                if (fail) error("database")
                emit(null)
            }
        }
        val vm = main.own(HistoryDetailViewModel(SavedStateHandle(mapOf(Destination.HISTORY_ID_ARG to 42L)), repository))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        assertThat(vm.uiState.value.isLoading).isTrue()
        assertThat(vm.uiState.value.loadFailed).isFalse()
        pending.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.loadFailed).isTrue()
        fail = false
        vm.refresh()
        runCurrent()
        assertThat(vm.uiState.value.isLoading).isFalse()
        assertThat(vm.uiState.value.loadFailed).isFalse()
        assertThat(vm.uiState.value.item).isNull()
    }
}
