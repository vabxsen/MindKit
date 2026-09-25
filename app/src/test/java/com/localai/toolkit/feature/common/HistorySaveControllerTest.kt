package com.localai.toolkit.feature.common

import com.google.common.truth.Truth.assertThat
import com.localai.toolkit.domain.model.HistoryItem
import com.localai.toolkit.domain.model.HistoryType
import com.localai.toolkit.domain.repository.HistoryRepository
import com.localai.toolkit.testing.MemoryHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySaveControllerTest {
    @Test fun `identical content in independent slots is saved independently`() = runTest {
        val backing = MemoryHistory()
        val write = CompletableDeferred<Unit>()
        var inserts = 0
        val repository = object : HistoryRepository by backing {
            override suspend fun save(item: HistoryItem): Long {
                inserts++
                write.await()
                return backing.save(item)
            }
        }
        val controller = HistorySaveController(repository, backgroundScope)
        val savedSlots = mutableSetOf<Int>()
        controller.save(item, { true }, slot = 1) { if (it) savedSlots += 1 }
        controller.save(item, { true }, slot = 2) { if (it) savedSlots += 2 }
        // A repeat tap on one answer is still a duplicate, not a third record.
        controller.save(item.copy(createdAtEpochMillis = 2), { true }, slot = 2) { if (it) savedSlots += 2 }
        runCurrent()
        assertThat(inserts).isEqualTo(2)
        write.complete(Unit)
        runCurrent()
        assertThat(savedSlots).containsExactly(1, 2)
        assertThat(backing.items.value).hasSize(2)
    }

    private val item = HistoryItem(type = HistoryType.SUMMARY, title = "Input", inputPreview = "Input",
        output = "Output", createdAtEpochMillis = 1)

    @Test fun `rapid taps insert once even with different timestamps`() = runTest {
        val wait = CompletableDeferred<Unit>()
        var inserts = 0
        var saved = 0
        val backing = MemoryHistory()
        val repository = object : HistoryRepository by backing {
            override suspend fun save(item: HistoryItem): Long {
                inserts++
                wait.await()
                return backing.save(item)
            }
        }
        val controller = HistorySaveController(repository, backgroundScope)
        controller.save(item, { true }) { if (it) saved++ }
        controller.save(item.copy(createdAtEpochMillis = 2), { true }) { if (it) saved++ }
        runCurrent()
        assertThat(inserts).isEqualTo(1)
        wait.complete(Unit)
        runCurrent()
        assertThat(saved).isEqualTo(1)
        assertThat(controller.feedback.first()).isEqualTo(HistorySaveFeedback.SAVED)
    }

    @Test fun `disabled history reports feedback without claiming a save`() = runTest {
        val repository = object : HistoryRepository by MemoryHistory() {
            override suspend fun save(item: HistoryItem): Long? = null
        }
        val controller = HistorySaveController(repository, backgroundScope)
        var saved = false
        controller.save(item, { true }) { saved = it }
        runCurrent()
        assertThat(saved).isFalse()
        assertThat(controller.feedback.first()).isEqualTo(HistorySaveFeedback.DISABLED)
    }

    @Test fun `storage failure is reported and a subsequent tap can retry`() = runTest {
        var fail = true
        val backing = MemoryHistory()
        val repository = object : HistoryRepository by backing {
            override suspend fun save(item: HistoryItem): Long {
                if (fail) error("storage unavailable")
                return backing.save(item)
            }
        }
        val controller = HistorySaveController(repository, backgroundScope)
        var saved = false
        controller.save(item, { true }) { saved = it }
        runCurrent()
        assertThat(controller.feedback.first()).isEqualTo(HistorySaveFeedback.FAILED)
        fail = false
        controller.save(item, { true }) { saved = it }
        runCurrent()
        assertThat(saved).isTrue()
    }

    @Test fun `late completion stores the requested result but does not mark a replacement saved`() = runTest {
        val wait = CompletableDeferred<Unit>()
        val repository = object : HistoryRepository by MemoryHistory() {
            override suspend fun save(item: HistoryItem): Long { wait.await(); return 1 }
        }
        val controller = HistorySaveController(repository, backgroundScope)
        var current = true
        var saved = false
        controller.save(item, { current }) { saved = it }
        runCurrent()
        current = false
        wait.complete(Unit)
        runCurrent()
        assertThat(saved).isFalse()
        assertThat(controller.feedback.first()).isEqualTo(HistorySaveFeedback.SAVED)
    }

    @Test fun `deleting an unrelated row leaves the result saved`() = runTest {
        val repository = MemoryHistory()
        val other = repository.save(item.copy(output = "Other"))
        val states = mutableListOf<Boolean>()
        HistorySaveController(repository, backgroundScope).save(item, { true }) { states += it }
        runCurrent()
        repository.delete(other)
        runCurrent()
        assertThat(states).containsExactly(true)
        repository.deleteAll()
        runCurrent()
        assertThat(states).containsExactly(true, false).inOrder()
    }

    @Test fun `deletion before the first observation is not missed`() = runTest {
        val backing = MemoryHistory()
        val repository = object : HistoryRepository by backing {
            override suspend fun save(item: HistoryItem): Long {
                val id = backing.save(item)
                backing.delete(id)
                return id
            }
        }
        val states = mutableListOf<Boolean>()
        HistorySaveController(repository, backgroundScope).save(item, { true }) { states += it }
        runCurrent()
        assertThat(states).containsExactly(true, false).inOrder()
    }

    @Test fun `late deletion cannot change a replaced result`() = runTest {
        val repository = MemoryHistory()
        var current = true
        val states = mutableListOf<Boolean>()
        HistorySaveController(repository, backgroundScope).save(item, { current }) { states += it }
        runCurrent()
        current = false
        repository.deleteAll()
        runCurrent()
        assertThat(states).containsExactly(true)
    }

    @Test fun `a new save in the same slot detaches the previous row`() = runTest {
        val repository = MemoryHistory()
        val controller = HistorySaveController(repository, backgroundScope)
        val states = mutableListOf<Boolean>()
        controller.save(item, { true }) { states += it }
        runCurrent()
        val oldId = repository.items.value.single().id
        controller.save(item.copy(output = "New"), { true }) { states += it }
        runCurrent()
        repository.delete(oldId)
        runCurrent()
        assertThat(states).containsExactly(true, true).inOrder()
        repository.deleteAll()
        runCurrent()
        assertThat(states).containsExactly(true, true, false).inOrder()
    }

    @Test fun `read failures keep the confirmed save and reconnect for deletion`() = runTest {
        val backing = MemoryHistory()
        var reads = 0
        val repository = object : HistoryRepository by backing {
            override fun observeById(id: Long) = flow {
                if (++reads == 1) error("temporary read failure")
                emitAll(backing.observeById(id))
            }
        }
        val states = mutableListOf<Boolean>()
        HistorySaveController(repository, backgroundScope).save(item, { true }) { states += it }
        runCurrent()
        assertThat(states).containsExactly(true)
        backing.deleteAll()
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(reads).isEqualTo(2)
        assertThat(states).containsExactly(true, false).inOrder()
    }

    @Test fun `disposing the owner cancels observation without marking deletion`() = runTest {
        val backing = MemoryHistory()
        var closed = false
        val repository = object : HistoryRepository by backing {
            override fun observeById(id: Long) = flow {
                try {
                    emit(backing.items.value.single())
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }
        }
        val states = mutableListOf<Boolean>()
        HistorySaveController(repository, backgroundScope).save(item, { true }) { states += it }
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        assertThat(closed).isTrue()
        assertThat(states).containsExactly(true)
    }
}
